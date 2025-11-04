/**
 * AWS Lambda Webhook Handler (API Gateway版本)
 * 
 * 功能：接收来自其他用户的通知请求，验证签名后通过API Gateway推送WebSocket消息
 * 
 * 触发方式：Lambda Function URL (HTTPS POST)
 * 输入：WebhookRequest { version, notification, signature }
 * 输出：WebhookResponse { statusCode, delivered, message }
 */

const { ApiGatewayManagementApiClient, PostToConnectionCommand } = require('@aws-sdk/client-apigatewaymanagementapi');
const { S3Client, GetObjectCommand } = require('@aws-sdk/client-s3');
const crypto = require('crypto');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const BUCKET_NAME = process.env.CONNECTIONS_BUCKET;
const AWS_REGION = process.env.AWS_REGION || 'us-east-1';
const SIGNATURE_MAX_AGE_MS = 5 * 60 * 1000;
const API_GATEWAY_ENDPOINT = process.env.API_GATEWAY_ENDPOINT;

const s3Client = new S3Client({
    region: AWS_REGION
});

function log(level, message, data = {}) {
    const levels = { DEBUG: 0, INFO: 1, WARN: 2, ERROR: 3 };
    if (levels[level] >= levels[LOG_LEVEL]) {
        console.log(JSON.stringify({
            level,
            message,
            timestamp: new Date().toISOString(),
            ...data
        }));
    }
}

function verifySignature(bodyString, signature, secret) {
    const hmac = crypto.createHmac('sha256', secret);
    hmac.update(bodyString);
    const expectedSignature = hmac.digest('hex');
    
    return crypto.timingSafeEqual(
        Buffer.from(signature),
        Buffer.from(expectedSignature)
    );
}

function validateTimestamp(timestamp) {
    const now = Date.now();
    const age = Math.abs(now - timestamp);
    return age < SIGNATURE_MAX_AGE_MS;
}

async function getConnectionId(userId) {
    if (!BUCKET_NAME) {
        log('ERROR', 'CONNECTIONS_BUCKET not configured');
        return null;
    }
    
    log('DEBUG', 'Querying connectionId from S3', { userId });
    
    try {
        const key = `tap-ws-connections/${userId}.json`;
        
        const command = new GetObjectCommand({
            Bucket: BUCKET_NAME,
            Key: key
        });
        
        const response = await s3Client.send(command);
        
        const data = JSON.parse(await streamToString(response.Body));
        
        // 检查TTL
        const now = Math.floor(Date.now() / 1000);
        if (data.ttl && data.ttl < now) {
            log('WARN', 'Connection expired', { userId, ttl: data.ttl, now });
            return null;
        }
        
        log('DEBUG', 'Found connectionId', { userId, connectionId: data.connectionId });
        return data.connectionId;
        
    } catch (error) {
        if (error.name === 'NoSuchKey') {
            log('WARN', 'Connection not found for user', { userId });
        } else {
            log('ERROR', 'Failed to get connectionId from S3', {
                userId,
                error: error.message
            });
        }
        return null;
    }
}

async function streamToString(stream) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        stream.on('data', chunk => chunks.push(chunk));
        stream.on('error', reject);
        stream.on('end', () => resolve(Buffer.concat(chunks).toString('utf-8')));
    });
}

async function pushToWebSocket(connectionId, notification) {
    if (!API_GATEWAY_ENDPOINT) {
        throw new Error('API_GATEWAY_ENDPOINT not configured');
    }
    
    log('DEBUG', 'Pushing to WebSocket', { connectionId, endpoint: API_GATEWAY_ENDPOINT });
    
    const apiGatewayClient = new ApiGatewayManagementApiClient({
        endpoint: API_GATEWAY_ENDPOINT
    });
    
    const payload = JSON.stringify(notification);
    
    const command = new PostToConnectionCommand({
        ConnectionId: connectionId,
        Data: Buffer.from(payload)
    });
    
    try {
        await apiGatewayClient.send(command);
        log('INFO', 'WebSocket message sent successfully', { connectionId });
    } catch (error) {
        if (error.statusCode === 410) {
            log('WARN', 'Connection gone (stale), should cleanup', { connectionId });
            throw new Error('Connection gone');
        }
        throw error;
    }
}

exports.handler = async (event) => {
    const requestId = event.requestContext?.requestId || 'unknown';
    
    try {
        log('INFO', 'Webhook request received', { requestId });
        
        if (!event.body) {
            log('WARN', 'Empty request body', { requestId });
            return {
                statusCode: 400,
                body: JSON.stringify({
                    statusCode: 400,
                    delivered: 0,
                    message: 'Empty request body'
                })
            };
        }
        
        const request = JSON.parse(event.body);
        log('DEBUG', 'Parsed request', { 
            requestId, 
            version: request.version,
            notificationType: request.notification?.type
        });
        
        if (!request.version || !request.notification || !request.signature) {
            log('WARN', 'Invalid request format', { requestId });
            return {
                statusCode: 400,
                body: JSON.stringify({
                    statusCode: 400,
                    delivered: 0,
                    message: 'Invalid request format'
                })
            };
        }
        
        const notifySecret = process.env.NOTIFY_SECRET;
        if (!notifySecret) {
            log('ERROR', 'NOTIFY_SECRET not configured', { requestId });
            return {
                statusCode: 500,
                body: JSON.stringify({
                    statusCode: 500,
                    delivered: 0,
                    message: 'Server configuration error'
                })
            };
        }
        
        if (!validateTimestamp(request.notification.timestamp)) {
            log('WARN', 'Timestamp expired', { 
                requestId,
                timestamp: request.notification.timestamp,
                now: Date.now()
            });
            return {
                statusCode: 403,
                body: JSON.stringify({
                    statusCode: 403,
                    delivered: 0,
                    message: 'Request expired'
                })
            };
        }
        
        // 从原始body中提取签名，然后移除signature字段后验证
        // 这样避免了重新序列化导致的顺序/格式差异
        const bodyObj = JSON.parse(event.body);
        const receivedSignature = bodyObj.signature;
        delete bodyObj.signature;
        const bodyForSignature = JSON.stringify(bodyObj);
        
        if (!verifySignature(bodyForSignature, receivedSignature, notifySecret)) {
            const expectedSig = crypto.createHmac('sha256', notifySecret)
                .update(bodyForSignature)
                .digest('hex');
            log('WARN', 'Invalid signature', { 
                requestId,
                expected: expectedSig.substring(0, 16) + '...',
                received: receivedSignature.substring(0, 16) + '...',
                bodyLength: bodyForSignature.length,
                bodyPreview: bodyForSignature.substring(0, 100) + '...'
            });
            return {
                statusCode: 403,
                body: JSON.stringify({
                    statusCode: 403,
                    delivered: 0,
                    message: 'Invalid signature'
                })
            };
        }
        
        const userId = request.notification.metadata?.userId;
        if (!userId) {
            log('ERROR', 'No userId in notification metadata', { requestId });
            return {
                statusCode: 400,
                body: JSON.stringify({
                    statusCode: 400,
                    delivered: 0,
                    message: 'Missing userId in metadata'
                })
            };
        }
        
        const connectionId = await getConnectionId(userId);
        if (!connectionId) {
            log('WARN', 'User not connected', { requestId, userId });
            return {
                statusCode: 200,
                body: JSON.stringify({
                    statusCode: 200,
                    delivered: 0,
                    message: 'User not connected (offline)'
                })
            };
        }
        
        await pushToWebSocket(connectionId, request.notification);
        
        log('INFO', 'Notification delivered successfully', { 
            requestId,
            userId,
            connectionId,
            senderId: request.notification.senderId
        });
        
        return {
            statusCode: 200,
            body: JSON.stringify({
                statusCode: 200,
                delivered: 1,
                message: 'ok'
            })
        };
        
    } catch (error) {
        log('ERROR', 'Webhook handler error', { 
            requestId,
            error: error.message,
            stack: error.stack
        });
        
        return {
            statusCode: 500,
            body: JSON.stringify({
                statusCode: 500,
                delivered: 0,
                message: 'Internal server error'
            })
        };
    }
};

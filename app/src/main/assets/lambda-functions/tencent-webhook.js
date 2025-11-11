/**
 * 腾讯云云函数 Webhook Handler (函数URL版本)
 * 
 * 功能：接收来自其他用户的通知请求，验证签名后通过WebSocket推送消息
 * 
 * 触发方式：函数URL (HTTPS POST)
 * 输入：WebhookRequest { version, notification, signature }
 * 输出：WebhookResponse { statusCode, delivered, message }
 */

const crypto = require('crypto');
const https = require('https');
const COS = require('cos-nodejs-sdk-v5');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const BUCKET_NAME = process.env.CONNECTIONS_BUCKET;
const REGION = process.env.REGION || 'ap-guangzhou';
const SIGNATURE_MAX_AGE_MS = 5 * 60 * 1000;
const WS_FUNCTION_URL = process.env.WS_FUNCTION_URL; // WebSocket函数的URL（用于推送）
const OFFLINE_PREFIX = 'tap-offline/';

const cos = new COS({
    SecretId: process.env.TAP_SECRET_ID,
    SecretKey: process.env.TAP_SECRET_KEY
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

function verifySignature(body, signature, secret) {
    const hmac = crypto.createHmac('sha256', secret);
    hmac.update(JSON.stringify(body));
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
    
    log('DEBUG', 'Querying connectionId from COS', { userId });
    
    try {
        const key = `tap-ws-connections/${userId}.json`;
        
        const result = await new Promise((resolve, reject) => {
            cos.getObject({
                Bucket: BUCKET_NAME,
                Region: REGION,
                Key: key
            }, (err, data) => {
                if (err) reject(err);
                else resolve(data);
            });
        });
        
        const connectionData = JSON.parse(result.Body);
        
        // 检查TTL
        const now = Math.floor(Date.now() / 1000);
        if (connectionData.ttl && connectionData.ttl < now) {
            log('WARN', 'Connection expired', { userId, ttl: connectionData.ttl, now });
            return null;
        }
        
        log('DEBUG', 'Found connectionId', { userId, connectionId: connectionData.connectionId });
        return connectionData.connectionId;
        
    } catch (error) {
        if (error.statusCode === 404) {
            log('WARN', 'Connection not found for user', { userId });
        } else {
            log('ERROR', 'Failed to get connectionId from COS', {
                userId,
                error: error.message
            });
        }
        return null;
    }
}

/**
 * P1修复：通过HTTP POST调用WebSocket函数的/push端点推送消息
 * 腾讯云函数URL的WebSocket推送机制：
 * 通过HTTP POST请求调用WebSocket函数的推送端点，函数内部处理推送逻辑
 */
async function pushToWebSocket(connectionId, notification, wsFunctionUrl, userId) {
    if (!wsFunctionUrl) {
        throw new Error('WebSocket function URL not configured');
    }
    
    log('DEBUG', 'Pushing to WebSocket via function URL', { 
        connectionId, 
        userId,
        wsFunctionUrl 
    });
    
    // P1修复：构造推送请求，包含connectionId、message和userId
    const payload = JSON.stringify({
        connectionId: connectionId,
        userId: userId,
        message: notification
    });
    
    // 使用HTTPS请求推送
    return new Promise((resolve, reject) => {
        const url = require('url');
        const parsedUrl = url.parse(wsFunctionUrl);
        
        // P1修复：推送端点路径 - 确保路径正确
        const pushPath = parsedUrl.path && parsedUrl.path !== '/' 
            ? `${parsedUrl.path}/push` 
            : '/push';
        
        const options = {
            hostname: parsedUrl.hostname,
            port: 443,
            path: pushPath,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload)
            },
            timeout: 10000
        };
        
        log('DEBUG', 'Sending push request', {
            hostname: options.hostname,
            path: options.path,
            connectionId
        });
        
        const req = https.request(options, (res) => {
            let data = '';
            
            res.on('data', chunk => {
                data += chunk;
            });
            
            res.on('end', () => {
                try {
                    const response = JSON.parse(data);
                    if (res.statusCode >= 200 && res.statusCode < 300 && response.success) {
                        log('INFO', 'WebSocket message sent successfully', { 
                            connectionId,
                            userId
                        });
                        resolve();
                    } else {
                        log('WARN', 'Push request returned non-success status', {
                            connectionId,
                            statusCode: res.statusCode,
                            response: response
                        });
                        reject(new Error('Push request failed'));
                    }
                } catch (e) {
                    log('WARN', 'Failed to parse push response', {
                        connectionId,
                        statusCode: res.statusCode,
                        response: data,
                        error: e.message
                    });
                    reject(new Error('Push response parse error'));
                }
            });
        });
        
        req.on('error', (error) => {
            log('ERROR', 'Push request failed', { 
                connectionId,
                error: error.message 
            });
            reject(error);
        });
        
        req.on('timeout', () => {
            req.destroy();
            log('WARN', 'Push request timeout', { connectionId });
            reject(new Error('Push request timeout'));
        });
        
        req.write(payload);
        req.end();
    });
}

/**
 * 保存通知到队列（COS）以供客户端轮询获取
 */
async function saveOfflineMessage(recipientHash, notification) {
    if (!BUCKET_NAME || !recipientHash) {
        log('WARN', '无法保存离线消息，缺少bucket或recipientHash', { recipientHash });
        return;
    }
    const safeHash = recipientHash.trim().toLowerCase();
    const messageId = notification.metadata?.message?.messageId || notification.metadata?.traceId || notification.timestamp;
    const key = `${OFFLINE_PREFIX}${safeHash}/${Date.now()}-${messageId || 'message'}.json`;

    await new Promise((resolve, reject) => {
        cos.putObject({
            Bucket: BUCKET_NAME,
            Region: REGION,
            Key: key,
            Body: JSON.stringify(notification),
            ContentType: 'application/json'
        }, (err) => {
            if (err) {
                log('ERROR', '保存离线消息失败', { key, error: err.message });
                reject(err);
            } else {
                log('INFO', '离线消息已保存', { key });
                resolve();
            }
        });
    });
}

exports.main_handler = async (event) => {
    const requestId = event.requestId || 'unknown';
    
    try {
        log('INFO', 'Webhook request received', { requestId });
        
        let body;
        if (typeof event.body === 'string') {
            body = event.body;
        } else if (event.body) {
            body = JSON.stringify(event.body);
        } else {
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
        
        const request = JSON.parse(body);
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
        
        const bodyForSignature = {
            version: request.version,
            notification: request.notification
        };
        
        if (!verifySignature(bodyForSignature, request.signature, notifySecret)) {
            const expectedSig = crypto.createHmac('sha256', notifySecret)
                .update(JSON.stringify(bodyForSignature))
                .digest('hex');
            log('WARN', 'Invalid signature', { 
                requestId,
                expected: expectedSig.substring(0, 16) + '...',
                received: request.signature.substring(0, 16) + '...',
                bodyLength: JSON.stringify(bodyForSignature).length
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
        
        const recipientHash = request.recipient?.hash || request.recipientHash || request.notification.metadata?.recipientHash || userId;
        const connectionId = await getConnectionId(userId);
        if (!connectionId) {
            log('WARN', 'User not connected', { requestId, userId });
            await saveOfflineMessage(recipientHash, request.notification);
            return {
                statusCode: 200,
                body: JSON.stringify({
                    statusCode: 200,
                    delivered: 0,
                    message: 'User not connected (offline stored)'
                })
            };
        }
        
        let delivered = 0;
        if (WS_FUNCTION_URL) {
            try {
                await pushToWebSocket(connectionId, request.notification, WS_FUNCTION_URL, userId);
                delivered = 1;
                log('INFO', 'Notification delivered via WebSocket', { 
                    requestId,
                    userId,
                    connectionId,
                    senderId: request.notification.senderId
                });
            } catch (error) {
                log('WARN', 'Failed to push via WebSocket, storing offline', {
                    requestId,
                    userId,
                    connectionId,
                    error: error.message
                });
                await saveOfflineMessage(recipientHash, request.notification);
            }
        } else {
            log('WARN', 'WS_FUNCTION_URL not configured, storing offline', { requestId });
            await saveOfflineMessage(recipientHash, request.notification);
        }
        
        return {
            statusCode: 200,
            body: JSON.stringify({
                statusCode: 200,
                delivered: delivered,
                message: delivered > 0 ? 'ok' : 'queued'
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

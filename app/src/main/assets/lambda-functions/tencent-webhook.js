/**
 * 腾讯云云函数 Webhook Handler (API网关版本)
 * 
 * 功能：接收来自其他用户的通知请求，验证签名后通过API网关推送WebSocket消息
 * 
 * 触发方式：HTTP触发器 (HTTPS POST)
 * 输入：WebhookRequest { version, notification, signature }
 * 输出：WebhookResponse { statusCode, delivered, message }
 */

const crypto = require('crypto');
const https = require('https');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const DATABASE_ENV = process.env.DATABASE_ENV;
const COLLECTION_NAME = 'tap-ws-connections';
const SIGNATURE_MAX_AGE_MS = 5 * 60 * 1000;
const API_GATEWAY_SERVICE_ID = process.env.API_GATEWAY_SERVICE_ID;
const API_GATEWAY_REGION = process.env.API_GATEWAY_REGION || 'ap-guangzhou';

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

function generateCloudBaseSignature(secretId, secretKey, action, params, region) {
    const timestamp = Math.floor(Date.now() / 1000);
    const date = new Date(timestamp * 1000).toISOString().split('T')[0];
    const service = 'tcb';
    
    const canonicalHeaders = `content-type:application/json\nhost:${service}.tencentcloudapi.com\n`;
    const signedHeaders = 'content-type;host';
    const payload = JSON.stringify(params);
    const hashedPayload = crypto.createHash('sha256').update(payload).digest('hex');
    
    const canonicalRequest = `POST\n/\n\n${canonicalHeaders}\n${signedHeaders}\n${hashedPayload}`;
    const hashedCanonicalRequest = crypto.createHash('sha256').update(canonicalRequest).digest('hex');
    
    const credentialScope = `${date}/${service}/tc3_request`;
    const stringToSign = `TC3-HMAC-SHA256\n${timestamp}\n${credentialScope}\n${hashedCanonicalRequest}`;
    
    const kDate = crypto.createHmac('sha256', `TC3${secretKey}`).update(date).digest();
    const kService = crypto.createHmac('sha256', kDate).update(service).digest();
    const kSigning = crypto.createHmac('sha256', kService).update('tc3_request').digest();
    const signature = crypto.createHmac('sha256', kSigning).update(stringToSign).digest('hex');
    
    const authorization = `TC3-HMAC-SHA256 Credential=${secretId}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
    
    return { authorization, timestamp, payload };
}

async function getConnectionId(userId) {
    const secretId = process.env.TENCENTCLOUD_SECRETID;
    const secretKey = process.env.TENCENTCLOUD_SECRETKEY;
    const region = process.env.REGION || 'ap-guangzhou';
    
    if (!secretId || !secretKey || !DATABASE_ENV) {
        throw new Error('CloudBase credentials not configured');
    }
    
    log('DEBUG', 'Querying connectionId from database', { userId });
    
    const query = `db.collection('${COLLECTION_NAME}').doc('${userId}').get()`;
    
    const params = {
        EnvId: DATABASE_ENV,
        Query: query
    };
    
    const { authorization, timestamp, payload } = generateCloudBaseSignature(
        secretId,
        secretKey,
        'ExecuteCloudFunction',
        params,
        region
    );
    
    return new Promise((resolve, reject) => {
        const options = {
            hostname: 'tcb.tencentcloudapi.com',
            port: 443,
            path: '/',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload),
                'Authorization': authorization,
                'X-TC-Action': 'ExecuteCloudFunction',
                'X-TC-Version': '2018-06-08',
                'X-TC-Timestamp': timestamp.toString(),
                'X-TC-Region': region
            },
            timeout: 10000
        };
        
        const req = https.request(options, (res) => {
            let data = '';
            
            res.on('data', chunk => {
                data += chunk;
            });
            
            res.on('end', () => {
                try {
                    const response = JSON.parse(data);
                    
                    if (response.Response && response.Response.Error) {
                        log('WARN', 'User connection not found', { userId });
                        resolve(null);
                    } else if (response.Response && response.Response.Data) {
                        const result = JSON.parse(response.Response.Data);
                        if (result.data && result.data.length > 0) {
                            const connectionId = result.data[0].connectionId;
                            log('DEBUG', 'Found connectionId', { userId, connectionId });
                            resolve(connectionId);
                        } else {
                            log('WARN', 'No connection data for user', { userId });
                            resolve(null);
                        }
                    } else {
                        log('WARN', 'Unexpected response format', { userId });
                        resolve(null);
                    }
                } catch (parseErr) {
                    log('ERROR', 'Failed to parse response', { data, error: parseErr.message });
                    reject(parseErr);
                }
            });
        });
        
        req.on('error', (error) => {
            log('ERROR', 'Request failed', { error: error.message });
            reject(error);
        });
        
        req.on('timeout', () => {
            req.destroy();
            log('ERROR', 'Request timeout');
            reject(new Error('Request timeout'));
        });
        
        req.write(payload);
        req.end();
    });
}

function generateApiGatewaySignature(secretId, secretKey, serviceId, connectionId, data, region) {
    const timestamp = Math.floor(Date.now() / 1000);
    const date = new Date(timestamp * 1000).toISOString().split('T')[0];
    const service = 'apigw';
    
    const host = `${serviceId}.${region}.apigatewayserviceapi.tencentcloudapi.com`;
    const path = `/push/${connectionId}`;
    
    const canonicalHeaders = `content-type:application/json\nhost:${host}\n`;
    const signedHeaders = 'content-type;host';
    const payload = JSON.stringify(data);
    const hashedPayload = crypto.createHash('sha256').update(payload).digest('hex');
    
    const canonicalRequest = `POST\n${path}\n\n${canonicalHeaders}\n${signedHeaders}\n${hashedPayload}`;
    const hashedCanonicalRequest = crypto.createHash('sha256').update(canonicalRequest).digest('hex');
    
    const credentialScope = `${date}/${service}/tc3_request`;
    const stringToSign = `TC3-HMAC-SHA256\n${timestamp}\n${credentialScope}\n${hashedCanonicalRequest}`;
    
    const kDate = crypto.createHmac('sha256', `TC3${secretKey}`).update(date).digest();
    const kService = crypto.createHmac('sha256', kDate).update(service).digest();
    const kSigning = crypto.createHmac('sha256', kService).update('tc3_request').digest();
    const signature = crypto.createHmac('sha256', kSigning).update(stringToSign).digest('hex');
    
    const authorization = `TC3-HMAC-SHA256 Credential=${secretId}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
    
    return { authorization, timestamp, payload, host, path };
}

async function pushToWebSocket(connectionId, notification) {
    const secretId = process.env.TENCENTCLOUD_SECRETID;
    const secretKey = process.env.TENCENTCLOUD_SECRETKEY;
    
    if (!secretId || !secretKey || !API_GATEWAY_SERVICE_ID) {
        throw new Error('API Gateway credentials not configured');
    }
    
    log('DEBUG', 'Pushing to WebSocket via API Gateway', { connectionId });
    
    const { authorization, timestamp, payload, host, path } = generateApiGatewaySignature(
        secretId,
        secretKey,
        API_GATEWAY_SERVICE_ID,
        connectionId,
        notification,
        API_GATEWAY_REGION
    );
    
    return new Promise((resolve, reject) => {
        const options = {
            hostname: host,
            port: 443,
            path: path,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload),
                'Authorization': authorization,
                'X-TC-Timestamp': timestamp.toString(),
                'X-TC-Region': API_GATEWAY_REGION
            },
            timeout: 10000
        };
        
        const req = https.request(options, (res) => {
            let data = '';
            
            res.on('data', chunk => {
                data += chunk;
            });
            
            res.on('end', () => {
                if (res.statusCode >= 200 && res.statusCode < 300) {
                    log('INFO', 'WebSocket message sent successfully', { connectionId });
                    resolve();
                } else {
                    log('ERROR', 'Failed to push WebSocket message', {
                        connectionId,
                        statusCode: res.statusCode,
                        response: data
                    });
                    reject(new Error(`Push failed with status ${res.statusCode}`));
                }
            });
        });
        
        req.on('error', (error) => {
            log('ERROR', 'Push request failed', { error: error.message });
            reject(error);
        });
        
        req.on('timeout', () => {
            req.destroy();
            log('ERROR', 'Push request timeout');
            reject(new Error('Request timeout'));
        });
        
        req.write(payload);
        req.end();
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

/**
 * 腾讯云云函数 Webhook Handler
 * 
 * 功能：接收来自其他用户的通知请求，验证签名后推送到腾讯云IoT Hub
 * 
 * 触发方式：HTTP触发器 (HTTPS POST)
 * 输入：WebhookRequest { version, notification, signature }
 * 输出：WebhookResponse { statusCode, delivered, message }
 */

const crypto = require('crypto');
const https = require('https');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const TOPIC_PREFIX = 'tap/notifications';
const SIGNATURE_MAX_AGE_MS = 5 * 60 * 1000;

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

function generateTencentCloudSignature(secretId, secretKey, action, params, region, service = 'iotcloud') {
    const timestamp = Math.floor(Date.now() / 1000);
    const date = new Date(timestamp * 1000).toISOString().split('T')[0];
    
    const canonicalQueryString = '';
    const canonicalHeaders = `content-type:application/json\nhost:${service}.tencentcloudapi.com\n`;
    const signedHeaders = 'content-type;host';
    const payload = JSON.stringify(params);
    const hashedPayload = crypto.createHash('sha256').update(payload).digest('hex');
    
    const canonicalRequest = `POST\n/\n${canonicalQueryString}\n${canonicalHeaders}\n${signedHeaders}\n${hashedPayload}`;
    const hashedCanonicalRequest = crypto.createHash('sha256').update(canonicalRequest).digest('hex');
    
    const credentialScope = `${date}/${service}/tc3_request`;
    const stringToSign = `TC3-HMAC-SHA256\n${timestamp}\n${credentialScope}\n${hashedCanonicalRequest}`;
    
    const kDate = crypto.createHmac('sha256', `TC3${secretKey}`).update(date).digest();
    const kService = crypto.createHmac('sha256', kDate).update(service).digest();
    const kSigning = crypto.createHmac('sha256', kService).update('tc3_request').digest();
    const signature = crypto.createHmac('sha256', kSigning).update(stringToSign).digest('hex');
    
    const authorization = `TC3-HMAC-SHA256 Credential=${secretId}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
    
    return {
        authorization,
        timestamp,
        payload
    };
}

async function publishToIoTHub(productId, deviceName, topicId, notification) {
    const topic = `${TOPIC_PREFIX}/${topicId}`;
    const payload = JSON.stringify(notification);
    
    log('DEBUG', 'Publishing to IoT Hub via REST API', { topic, payloadSize: payload.length });
    
    const secretId = process.env.TENCENTCLOUD_SECRETID;
    const secretKey = process.env.TENCENTCLOUD_SECRETKEY;
    const region = process.env.REGION || 'ap-guangzhou';
    
    if (!secretId || !secretKey) {
        throw new Error('Tencent Cloud credentials not configured');
    }
    
    const params = {
        ProductId: productId,
        DeviceName: deviceName,
        Topic: topic,
        Payload: Buffer.from(payload).toString('base64'),
        Qos: 1
    };
    
    const { authorization, timestamp, payload: requestPayload } = generateTencentCloudSignature(
        secretId,
        secretKey,
        'PublishMessage',
        params,
        region,
        'iotcloud'
    );
    
    return new Promise((resolve, reject) => {
        const options = {
            hostname: 'iotcloud.tencentcloudapi.com',
            port: 443,
            path: '/',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(requestPayload),
                'Authorization': authorization,
                'X-TC-Action': 'PublishMessage',
                'X-TC-Version': '2021-04-08',
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
                        const error = response.Response.Error;
                        log('ERROR', 'IoT Hub API error', {
                            code: error.Code,
                            message: error.Message
                        });
                        reject(new Error(`IoT Hub API error: ${error.Code} - ${error.Message}`));
                    } else {
                        log('INFO', 'Published to IoT Hub successfully', { topic });
                        resolve();
                    }
                } catch (parseErr) {
                    log('ERROR', 'Failed to parse IoT Hub response', { data, error: parseErr.message });
                    reject(parseErr);
                }
            });
        });
        
        req.on('error', (error) => {
            log('ERROR', 'IoT Hub request failed', { error: error.message });
            reject(error);
        });
        
        req.on('timeout', () => {
            req.destroy();
            log('ERROR', 'IoT Hub request timeout');
            reject(new Error('Request timeout'));
        });
        
        req.write(requestPayload);
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
            log('WARN', 'Invalid signature', { requestId });
            return {
                statusCode: 403,
                body: JSON.stringify({
                    statusCode: 403,
                    delivered: 0,
                    message: 'Invalid signature'
                })
            };
        }
        
        const topicId = process.env.TOPIC_ID || request.notification.metadata?.topicId;
        if (!topicId) {
            log('ERROR', 'Topic ID not configured', { requestId });
            return {
                statusCode: 500,
                body: JSON.stringify({
                    statusCode: 500,
                    delivered: 0,
                    message: 'Server configuration error'
                })
            };
        }
        
        const productId = process.env.PRODUCT_ID;
        const deviceName = process.env.DEVICE_NAME;
        
        if (!productId || !deviceName) {
            log('ERROR', 'IoT Hub credentials not configured', { requestId });
            return {
                statusCode: 500,
                body: JSON.stringify({
                    statusCode: 500,
                    delivered: 0,
                    message: 'Server configuration error'
                })
            };
        }
        
        await publishToIoTHub(productId, deviceName, topicId, request.notification);
        
        log('INFO', 'Notification delivered successfully', { 
            requestId,
            topicId,
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


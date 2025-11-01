/**
 * AWS Lambda Webhook Handler
 * 
 * 功能：接收来自其他用户的通知请求，验证签名后推送到AWS IoT Core
 * 
 * 触发方式：Lambda Function URL (HTTPS POST)
 * 输入：WebhookRequest { version, notification, signature }
 * 输出：WebhookResponse { statusCode, delivered, message }
 */

const { IoTDataPlaneClient, PublishCommand } = require('@aws-sdk/client-iot-data-plane');
const crypto = require('crypto');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const TOPIC_PREFIX = 'tap/notifications';
const SIGNATURE_MAX_AGE_MS = 5 * 60 * 1000; // 5分钟

const iotClient = new IoTDataPlaneClient({
    region: process.env.AWS_REGION
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

async function publishToIoT(topicId, notification) {
    const topic = `${TOPIC_PREFIX}/${topicId}`;
    const payload = JSON.stringify(notification);
    
    log('DEBUG', 'Publishing to IoT', { topic, payloadSize: payload.length });
    
    const command = new PublishCommand({
        topic: topic,
        qos: 1,
        payload: Buffer.from(payload)
    });
    
    await iotClient.send(command);
    log('INFO', 'Published to IoT successfully', { topic });
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
        
        await publishToIoT(topicId, request.notification);
        
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



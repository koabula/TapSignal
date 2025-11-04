/**
 * AWS Lambda S3 Event Trigger (Function F_A)
 * 
 * 功能：S3上传事件触发后，读取联系人webhook配置，发送通知
 * 
 * 触发方式：S3 Event Notification (ObjectCreated)
 * 输入：S3 Event { Records: [{ s3: { object: { key } } }] }
 * 输出：{ statusCode, deliveredCount, results }
 */

const { S3Client, GetObjectCommand, ListObjectsV2Command } = require('@aws-sdk/client-s3');
const crypto = require('crypto');
const https = require('https');
const http = require('http');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const CONFIG_BUCKET = process.env.CONFIG_BUCKET;
const CONFIG_KEY = process.env.CONFIG_KEY || 'notification-config.json';
const CONTACTS_PREFIX = 'tap-state/contacts/';
const CHANNEL_PREFIX = 'v2-channels/';

const s3Client = new S3Client({
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

async function getS3Object(bucket, key) {
    log('DEBUG', 'Fetching S3 object', { bucket, key });
    
    const command = new GetObjectCommand({
        Bucket: bucket,
        Key: key
    });
    
    const response = await s3Client.send(command);
    const bodyContents = await streamToString(response.Body);
    return JSON.parse(bodyContents);
}

async function streamToString(stream) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        stream.on('data', chunk => chunks.push(chunk));
        stream.on('error', reject);
        stream.on('end', () => resolve(Buffer.concat(chunks).toString('utf-8')));
    });
}

async function listContactConfigs(bucket) {
    log('DEBUG', 'Listing contact configurations', { bucket });
    
    const command = new ListObjectsV2Command({
        Bucket: bucket,
        Prefix: CONTACTS_PREFIX
    });
    
    const response = await s3Client.send(command);
    return response.Contents || [];
}

async function sendWebhookNotification(webhookUrl, notification, notifySecret) {
    log('DEBUG', 'Sending webhook notification', { webhookUrl });
    
    const requestBody = {
        version: '2.0',
        notification: notification
    };
    
    const signature = crypto
        .createHmac('sha256', notifySecret)
        .update(JSON.stringify(requestBody))
        .digest('hex');
    
    const fullRequest = {
        ...requestBody,
        signature: signature
    };
    
    const payload = JSON.stringify(fullRequest);
    const url = new URL(webhookUrl);
    const protocol = url.protocol === 'https:' ? https : http;
    
    return new Promise((resolve, reject) => {
        const options = {
            hostname: url.hostname,
            port: url.port || (url.protocol === 'https:' ? 443 : 80),
            path: url.pathname + url.search,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload),
                'User-Agent': 'TapNotification/1.0'
            },
            timeout: 10000
        };
        
        const req = protocol.request(options, (res) => {
            let data = '';
            
            res.on('data', chunk => {
                data += chunk;
            });
            
            res.on('end', () => {
                if (res.statusCode >= 200 && res.statusCode < 300) {
                    log('INFO', 'Webhook notification sent successfully', { 
                        webhookUrl,
                        statusCode: res.statusCode
                    });
                    resolve({
                        success: true,
                        statusCode: res.statusCode,
                        response: data
                    });
                } else {
                    log('WARN', 'Webhook returned error status', {
                        webhookUrl,
                        statusCode: res.statusCode,
                        response: data
                    });
                    resolve({
                        success: false,
                        statusCode: res.statusCode,
                        error: `HTTP ${res.statusCode}`
                    });
                }
            });
        });
        
        req.on('error', (error) => {
            log('ERROR', 'Webhook request failed', {
                webhookUrl,
                error: error.message
            });
            resolve({
                success: false,
                error: error.message
            });
        });
        
        req.on('timeout', () => {
            req.destroy();
            log('ERROR', 'Webhook request timeout', { webhookUrl });
            resolve({
                success: false,
                error: 'Request timeout'
            });
        });
        
        req.write(payload);
        req.end();
    });
}

function extractSenderIdFromKey(key) {
    const match = key.match(/v2-channels\/([^/]+)\//);
    if (!match) return null;
    
    // 提取channelId，格式: {hash}_{timestamp}
    const channelId = match[1];
    
    // 修复: 只返回hash部分，去掉timestamp后缀
    // 这样Android端可以直接通过hash查找通道，不需要额外处理
    const hashOnly = channelId.split('_')[0];
    
    return hashOnly;
}

function hashString(str) {
    return crypto.createHash('sha256').update(str).digest('hex').substring(0, 16);
}

exports.handler = async (event) => {
    const requestId = event.requestId || 'unknown';
    
    try {
        log('INFO', 'S3 event trigger activated', { 
            requestId,
            recordCount: event.Records?.length || 0
        });
        
        if (!event.Records || event.Records.length === 0) {
            log('WARN', 'No S3 records in event', { requestId });
            return {
                statusCode: 200,
                deliveredCount: 0,
                results: []
            };
        }
        
        const results = [];
        
        for (const record of event.Records) {
            try {
                const bucket = record.s3.bucket.name;
                const key = decodeURIComponent(record.s3.object.key.replace(/\+/g, ' '));
                
                log('DEBUG', 'Processing S3 record', { bucket, key });
                
                if (!key.startsWith(CHANNEL_PREFIX)) {
                    log('DEBUG', 'Skipping non-channel object', { key });
                    continue;
                }
                
                const senderId = extractSenderIdFromKey(key);
                if (!senderId) {
                    log('WARN', 'Cannot extract sender ID from key', { key });
                    continue;
                }
                
                const configBucket = CONFIG_BUCKET || bucket;
                
                let contactConfigs = [];
                try {
                    const objects = await listContactConfigs(configBucket);
                    
                    for (const obj of objects) {
                        try {
                            const config = await getS3Object(configBucket, obj.Key);
                            contactConfigs.push(config);
                        } catch (err) {
                            log('WARN', 'Failed to load contact config', { 
                                key: obj.Key,
                                error: err.message
                            });
                        }
                    }
                } catch (err) {
                    log('ERROR', 'Failed to list contact configs', { 
                        bucket: configBucket,
                        error: err.message
                    });
                }
                
                log('INFO', 'Sending notifications to contacts', {
                    senderId,
                    contactCount: contactConfigs.length
                });
                
                for (const contactConfig of contactConfigs) {
                    try {
                        if (!contactConfig.webhookUrl || !contactConfig.notifySecret || !contactConfig.userId) {
                            log('WARN', 'Invalid contact config', {
                                contactId: contactConfig.contactId,
                                missingFields: {
                                    webhookUrl: !contactConfig.webhookUrl,
                                    notifySecret: !contactConfig.notifySecret,
                                    userId: !contactConfig.userId
                                }
                            });
                            continue;
                        }
                        
                        const notification = {
                            type: 'new_message',
                            senderId: senderId,
                            timestamp: Date.now(),
                            metadata: {
                                bucket: bucket,
                                key: key,
                                eventTime: record.eventTime,
                                userId: contactConfig.userId
                            }
                        };
                        
                        const result = await sendWebhookNotification(
                            contactConfig.webhookUrl,
                            notification,
                            contactConfig.notifySecret
                        );
                        
                        results.push({
                            contactId: contactConfig.contactId,
                            success: result.success,
                            statusCode: result.statusCode,
                            error: result.error
                        });
                        
                    } catch (err) {
                        log('ERROR', 'Failed to send notification to contact', {
                            contactId: contactConfig.contactId,
                            error: err.message
                        });
                        results.push({
                            contactId: contactConfig.contactId,
                            success: false,
                            error: err.message
                        });
                    }
                }
                
            } catch (err) {
                log('ERROR', 'Error processing S3 record', {
                    error: err.message,
                    stack: err.stack
                });
            }
        }
        
        const deliveredCount = results.filter(r => r.success).length;
        
        log('INFO', 'Event processing completed', {
            requestId,
            totalResults: results.length,
            deliveredCount: deliveredCount
        });
        
        return {
            statusCode: 200,
            deliveredCount: deliveredCount,
            results: results
        };
        
    } catch (error) {
        log('ERROR', 'Handler error', {
            requestId,
            error: error.message,
            stack: error.stack
        });
        
        return {
            statusCode: 500,
            deliveredCount: 0,
            error: error.message
        };
    }
};


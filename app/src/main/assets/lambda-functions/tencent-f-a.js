/**
 * 腾讯云云函数 COS Event Trigger (Function F_A)
 * 
 * 功能：COS上传事件触发后，读取联系人webhook配置，发送通知
 * 
 * 触发方式：COS Event Notification (ObjectCreated)
 * 输入：COS Event { Records: [{ cos: { cosObject: { key } } }] }
 * 输出：{ statusCode, deliveredCount, results }
 */

const COS = require('cos-nodejs-sdk-v5');
const crypto = require('crypto');
const https = require('https');
const http = require('http');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const CONFIG_BUCKET = process.env.CONFIG_BUCKET;
const CONFIG_KEY = process.env.CONFIG_KEY || 'notification-config.json';
const REGION = process.env.REGION || 'ap-guangzhou';
const CONTACTS_PREFIX = 'tap-state/contacts/';
const CHANNEL_PREFIX = 'v2-channels/';

const cos = new COS({
    SecretId: process.env.TENCENTCLOUD_SECRETID,
    SecretKey: process.env.TENCENTCLOUD_SECRETKEY
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

async function getCosObject(bucket, region, key) {
    log('DEBUG', 'Fetching COS object', { bucket, region, key });
    
    return new Promise((resolve, reject) => {
        cos.getObject({
            Bucket: bucket,
            Region: region,
            Key: key
        }, (err, data) => {
            if (err) {
                reject(err);
            } else {
                try {
                    const content = data.Body.toString('utf-8');
                    resolve(JSON.parse(content));
                } catch (parseErr) {
                    reject(parseErr);
                }
            }
        });
    });
}

async function listContactConfigs(bucket, region) {
    log('DEBUG', 'Listing contact configurations', { bucket, region });
    
    return new Promise((resolve, reject) => {
        cos.getBucket({
            Bucket: bucket,
            Region: region,
            Prefix: CONTACTS_PREFIX
        }, (err, data) => {
            if (err) {
                reject(err);
            } else {
                resolve(data.Contents || []);
            }
        });
    });
}

async function sendWebhookNotification(webhookUrl, notification, notifySecret) {
    log('DEBUG', 'Sending webhook notification', { webhookUrl });
    
    const requestBody = {
        version: '1.0',
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
    return match ? match[1] : null;
}

function hashString(str) {
    return crypto.createHash('sha256').update(str).digest('hex').substring(0, 16);
}

exports.main_handler = async (event) => {
    const requestId = event.requestId || 'unknown';
    
    try {
        log('INFO', 'COS event trigger activated', { 
            requestId,
            recordCount: event.Records?.length || 0
        });
        
        if (!event.Records || event.Records.length === 0) {
            log('WARN', 'No COS records in event', { requestId });
            return {
                statusCode: 200,
                deliveredCount: 0,
                results: []
            };
        }
        
        const results = [];
        
        for (const record of event.Records) {
            try {
                const bucket = record.cos.cosBucket.name;
                const bucketRegion = record.cos.cosBucket.appid ? REGION : 'ap-guangzhou';
                const key = decodeURIComponent(record.cos.cosObject.key.replace(/\+/g, ' '));
                
                log('DEBUG', 'Processing COS record', { bucket, bucketRegion, key });
                
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
                const configRegion = bucketRegion;
                
                let contactConfigs = [];
                try {
                    const objects = await listContactConfigs(configBucket, configRegion);
                    
                    for (const obj of objects) {
                        try {
                            const config = await getCosObject(configBucket, configRegion, obj.Key);
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
                                eventTime: record.event.eventTime,
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
                log('ERROR', 'Error processing COS record', {
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


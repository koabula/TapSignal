/**
 * AWS Lambda S3 Event Trigger (Function F_A)
 * 
 * 功能：S3上传事件触发后，读取联系人webhook配置，发送通知
 * 
 * 触发方式：S3 Event Notification (ObjectCreated)
 * 输入：S3 Event { Records: [{ s3: { object: { key } } }] }
 * 输出：{ statusCode, deliveredCount, results }
 */

const { S3Client, GetObjectCommand, ListObjectsV2Command, PutObjectCommand } = require('@aws-sdk/client-s3');
const crypto = require('crypto');
const https = require('https');
const http = require('http');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const CONFIG_BUCKET = process.env.CONFIG_BUCKET;
const CONFIG_KEY = process.env.CONFIG_KEY || 'notification-config.json';
const CONTACTS_PREFIX = 'tap-state/contacts/';
const CHANNEL_PREFIX = 'v2-channels/';
const DEDUP_PREFIX = 'tap-dedup/';
const DEDUP_TTL_MS = 24 * 60 * 60 * 1000; // 24小时

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

function generateTraceId() {
    return typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : crypto.randomBytes(16).toString('hex');
}

async function loadContactConfig(bucket, hash) {
    const key = `${CONTACTS_PREFIX}${hash}.json`;
    try {
        return await getS3Object(bucket, key);
    } catch (error) {
        if (error.name === 'NoSuchKey') {
            log('WARN', 'Contact config not found', { bucket, key });
            return null;
        }
        throw error;
    }
}

async function checkDuplicate(bucket, traceId) {
    if (!traceId) return false;
    
    const key = `${DEDUP_PREFIX}${traceId}.marker`;
    
    try {
        const response = await s3Client.send(new GetObjectCommand({
            Bucket: bucket,
            Key: key
        }));
        
        const metadata = response.Metadata || {};
        const timestamp = parseInt(metadata.timestamp || '0', 10);
        const age = Date.now() - timestamp;
        
        if (age < DEDUP_TTL_MS) {
            log('INFO', 'Duplicate request detected', { traceId, age });
            return true;
        } else {
            log('DEBUG', 'Dedup marker expired, treating as new', { traceId, age });
            return false;
        }
    } catch (error) {
        if (error.name === 'NoSuchKey') {
            return false;
        }
        log('WARN', 'Failed to check duplicate', { traceId, error: error.message });
        return false;
    }
}

async function markProcessed(bucket, traceId) {
    if (!traceId) return;
    
    const key = `${DEDUP_PREFIX}${traceId}.marker`;
    const now = Date.now();
    
    try {
        await s3Client.send(new PutObjectCommand({
            Bucket: bucket,
            Key: key,
            Body: JSON.stringify({ traceId, processedAt: now }),
            Metadata: {
                timestamp: now.toString(),
                ttl: (now + DEDUP_TTL_MS).toString()
            }
        }));
        log('DEBUG', 'Request marked as processed', { traceId });
    } catch (error) {
        log('WARN', 'Failed to mark request as processed', { traceId, error: error.message });
    }
}

async function handleDirectMessage(event) {
    const requestId = event.traceId || event.requestId || generateTraceId();
    log('INFO', 'Handling direct message dispatch', { requestId });

    const recipientHash = event.recipientHash || event.recipient?.hash;
    if (!recipientHash) {
        log('WARN', 'Missing recipient hash for direct dispatch', { requestId });
        return {
            statusCode: 400,
            deliveredCount: 0,
            error: 'recipientHash missing'
        };
    }

    const bucket = event.configBucket || CONFIG_BUCKET;
    if (!bucket) {
        log('ERROR', 'CONFIG_BUCKET not configured for direct dispatch', { requestId });
        return {
            statusCode: 500,
            deliveredCount: 0,
            error: 'CONFIG_BUCKET missing'
        };
    }

    // 去重检查
    const isDuplicate = await checkDuplicate(bucket, requestId);
    if (isDuplicate) {
        log('INFO', 'Duplicate request, returning cached result', { requestId });
        return {
            statusCode: 200,
            deliveredCount: 1,
            cached: true,
            results: [
                {
                    contactId: recipientHash,
                    success: true,
                    cached: true
                }
            ]
        };
    }

    let contactConfig;
    try {
        contactConfig = await loadContactConfig(bucket, recipientHash);
    } catch (error) {
        log('ERROR', 'Failed to load contact config for direct dispatch', {
            bucket,
            recipientHash,
            error: error.message
        });
        return {
            statusCode: 500,
            deliveredCount: 0,
            error: error.message
        };
    }

    if (!contactConfig || !contactConfig.webhookUrl || !contactConfig.notifySecret) {
        log('WARN', 'Contact config incomplete for direct dispatch', { recipientHash });
        return {
            statusCode: 404,
            deliveredCount: 0,
            error: 'contact config missing'
        };
    }

    const notification = {
        type: 'new_message',
        senderId: event.sender?.hash || event.sender?.aci || 'unknown',
        timestamp: Date.now(),
        metadata: {
            delivery: 'direct',
            traceId: requestId,
            recipientHash,
            userId: contactConfig.userId,
            message: event.message,
            attachmentsPresigned: event.attachmentsPresigned || [],
            deliveryHint: event.deliveryHint || {}
        }
    };

    const result = await sendWebhookNotification(
        contactConfig.webhookUrl,
        notification,
        contactConfig.notifySecret
    );

    // 标记为已处理
    if (result.success) {
        await markProcessed(bucket, requestId);
    }

    if (!result.success) {
        throw new Error(`Webhook delivery failed: ${result.error || result.statusCode || 'unknown error'}`);
    }

    return {
        statusCode: 200,
        deliveredCount: 1,
        results: [
            {
                contactId: contactConfig.contactId || recipientHash,
                success: true,
                statusCode: result.statusCode,
                error: null
            }
        ]
    };
}

exports.handler = async (event) => {
    const requestId = event.requestId || 'unknown';
    
    try {
        if (event && event.operation === 'direct_message') {
            return await handleDirectMessage(event);
        }

        log('WARN', 'Unsupported operation for F_A', {
            requestId,
            operation: event?.operation
        });

        return {
            statusCode: 400,
            deliveredCount: 0,
            error: 'unsupported operation'
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

/**
 * AWS Lambda WebSocket $connect Handler
 * 
 * 功能：处理WebSocket连接事件，保存connectionId到S3
 * 
 * 触发方式：API Gateway WebSocket $connect 路由
 * 输入：event.requestContext.connectionId, event.queryStringParameters.userId
 * 输出：{ statusCode: 200 } 或错误响应
 */

const { S3Client, PutObjectCommand } = require('@aws-sdk/client-s3');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const BUCKET_NAME = process.env.CONNECTIONS_BUCKET;
const AWS_REGION = process.env.AWS_REGION || 'us-east-1';
const TTL_HOURS = 24;

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

exports.handler = async (event) => {
    const connectionId = event.requestContext?.connectionId;
    const requestId = event.requestContext?.requestId || 'unknown';
    
    try {
        log('INFO', 'WebSocket connection request', { requestId, connectionId });
        
        if (!connectionId) {
            log('ERROR', 'No connectionId in event', { requestId });
            return {
                statusCode: 400,
                body: 'Missing connectionId'
            };
        }
        
        const userId = event.queryStringParameters?.userId;
        if (!userId) {
            log('WARN', 'No userId provided', { requestId, connectionId });
            return {
                statusCode: 400,
                body: 'Missing userId parameter'
            };
        }
        
        if (!BUCKET_NAME) {
            log('ERROR', 'CONNECTIONS_BUCKET not configured', { requestId });
            return {
                statusCode: 500,
                body: 'Server configuration error'
            };
        }
        
        const now = Math.floor(Date.now() / 1000);
        const ttl = now + (TTL_HOURS * 3600);
        
        const connectionData = {
            userId: userId,
            connectionId: connectionId,
            connectedAt: now,
            ttl: ttl
        };
        
        const key = `tap-ws-connections/${userId}.json`;
        
        const command = new PutObjectCommand({
            Bucket: BUCKET_NAME,
            Key: key,
            Body: JSON.stringify(connectionData),
            ContentType: 'application/json'
        });
        
        await s3Client.send(command);
        
        log('INFO', 'Connection saved to S3', {
            requestId,
            userId,
            connectionId,
            bucket: BUCKET_NAME,
            key: key,
            ttl
        });
        
        return {
            statusCode: 200,
            body: 'Connected'
        };
        
    } catch (error) {
        log('ERROR', 'Failed to save connection', {
            requestId,
            connectionId,
            error: error.message,
            stack: error.stack
        });
        
        return {
            statusCode: 500,
            body: 'Internal server error'
        };
    }
};


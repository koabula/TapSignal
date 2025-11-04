/**
 * AWS Lambda WebSocket $disconnect Handler
 * 
 * 功能：处理WebSocket断开事件，删除S3中的connectionId
 * 
 * 触发方式：API Gateway WebSocket $disconnect 路由
 * 输入：event.requestContext.connectionId
 * 输出：{ statusCode: 200 } 或错误响应
 */

const { S3Client, DeleteObjectCommand, GetObjectCommand, ListObjectsV2Command } = require('@aws-sdk/client-s3');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const BUCKET_NAME = process.env.CONNECTIONS_BUCKET;
const AWS_REGION = process.env.AWS_REGION || 'us-east-1';

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

async function findUserIdByConnectionId(connectionId) {
    if (!BUCKET_NAME) {
        return null;
    }
    
    try {
        const prefix = 'tap-ws-connections/';
        const command = new ListObjectsV2Command({
            Bucket: BUCKET_NAME,
            Prefix: prefix
        });
        
        const response = await s3Client.send(command);
        
        if (!response.Contents || response.Contents.length === 0) {
            return null;
        }
        
        for (const object of response.Contents) {
            const getCommand = new GetObjectCommand({
                Bucket: BUCKET_NAME,
                Key: object.Key
            });
            
            const getResponse = await s3Client.send(getCommand);
            const data = JSON.parse(await streamToString(getResponse.Body));
            
            if (data.connectionId === connectionId) {
                return data.userId;
            }
        }
        
        return null;
    } catch (error) {
        log('ERROR', 'Failed to find userId by connectionId', {
            connectionId,
            error: error.message
        });
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

exports.handler = async (event) => {
    const connectionId = event.requestContext?.connectionId;
    const requestId = event.requestContext?.requestId || 'unknown';
    
    try {
        log('INFO', 'WebSocket disconnect request', { requestId, connectionId });
        
        if (!connectionId) {
            log('ERROR', 'No connectionId in event', { requestId });
            return {
                statusCode: 400,
                body: 'Missing connectionId'
            };
        }
        
        if (!BUCKET_NAME) {
            log('ERROR', 'CONNECTIONS_BUCKET not configured', { requestId });
            return {
                statusCode: 500,
                body: 'Server configuration error'
            };
        }
        
        const userId = await findUserIdByConnectionId(connectionId);
        
        if (!userId) {
            log('WARN', 'Connection not found in S3', { requestId, connectionId });
            return {
                statusCode: 200,
                body: 'Connection not found (already cleaned)'
            };
        }
        
        const key = `tap-ws-connections/${userId}.json`;
        
        const command = new DeleteObjectCommand({
            Bucket: BUCKET_NAME,
            Key: key
        });
        
        await s3Client.send(command);
        
        log('INFO', 'Connection deleted from S3', {
            requestId,
            userId,
            connectionId,
            bucket: BUCKET_NAME,
            key: key
        });
        
        return {
            statusCode: 200,
            body: 'Disconnected'
        };
        
    } catch (error) {
        log('ERROR', 'Failed to delete connection', {
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


/**
 * AWS Lambda WebSocket $default Handler
 * 
 * 功能：处理WebSocket默认路由消息（心跳、未知消息等）
 * 
 * 触发方式：API Gateway WebSocket $default 路由
 * 输入：event.requestContext.connectionId, event.body
 * 输出：{ statusCode: 200 } 或错误响应
 */

const { ApiGatewayManagementApiClient, PostToConnectionCommand } = require('@aws-sdk/client-apigatewaymanagementapi');
const { S3Client, GetObjectCommand, PutObjectCommand } = require('@aws-sdk/client-s3');

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

async function updateLastActivity(userId) {
    if (!BUCKET_NAME) {
        return; // 如果 bucket 未配置，跳过更新
    }
    
    try {
        const key = `tap-ws-connections/${userId}.json`;
        
        // 读取现有数据
        let connectionData;
        try {
            const getCommand = new GetObjectCommand({
                Bucket: BUCKET_NAME,
                Key: key
            });
            const response = await s3Client.send(getCommand);
            const data = JSON.parse(await streamToString(response.Body));
            connectionData = data;
        } catch (e) {
            // 如果文件不存在，跳过更新
            return;
        }
        
        // 更新 lastActivity
        connectionData.lastActivity = Math.floor(Date.now() / 1000);
        
        // 写回 S3
        const putCommand = new PutObjectCommand({
            Bucket: BUCKET_NAME,
            Key: key,
            Body: JSON.stringify(connectionData),
            ContentType: 'application/json'
        });
        
        await s3Client.send(putCommand);
    } catch (error) {
        log('WARN', 'Failed to update last activity', {
            userId,
            error: error.message
        });
        // 不抛出错误，因为这是可选功能
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

async function sendToConnection(connectionId, message, endpoint) {
    const apiGatewayClient = new ApiGatewayManagementApiClient({
        endpoint: endpoint
    });
    
    const command = new PostToConnectionCommand({
        ConnectionId: connectionId,
        Data: Buffer.from(JSON.stringify(message))
    });
    
    await apiGatewayClient.send(command);
}

exports.handler = async (event) => {
    const connectionId = event.requestContext?.connectionId;
    const requestId = event.requestContext?.requestId || 'unknown';
    const domainName = event.requestContext?.domainName;
    const stage = event.requestContext?.stage;
    
    try {
        log('DEBUG', 'WebSocket message received', { 
            requestId, 
            connectionId,
            body: event.body
        });
        
        if (!connectionId) {
            log('ERROR', 'No connectionId in event', { requestId });
            return {
                statusCode: 400,
                body: 'Missing connectionId'
            };
        }
        
        const userId = event.queryStringParameters?.userId;
        
        let message = {};
        if (event.body) {
            try {
                message = JSON.parse(event.body);
            } catch (e) {
                log('WARN', 'Failed to parse message body', { requestId, body: event.body });
            }
        }
        
        if (message.type === 'ping') {
            log('DEBUG', 'Received ping, sending pong', { requestId, connectionId });
            
            if (userId) {
                await updateLastActivity(userId);
            }
            
            const endpoint = `https://${domainName}/${stage}`;
            await sendToConnection(connectionId, { type: 'pong', timestamp: Date.now() }, endpoint);
            
            return {
                statusCode: 200,
                body: 'Pong sent'
            };
        }
        
        log('DEBUG', 'Default handler - no action needed', { 
            requestId, 
            connectionId,
            messageType: message.type || 'unknown'
        });
        
        return {
            statusCode: 200,
            body: 'OK'
        };
        
    } catch (error) {
        log('ERROR', 'Default handler error', {
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


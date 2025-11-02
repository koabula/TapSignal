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
const { DynamoDBClient, UpdateItemCommand } = require('@aws-sdk/client-dynamodb');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const TABLE_NAME = process.env.CONNECTIONS_TABLE || 'tap-ws-connections';

const dynamodbClient = new DynamoDBClient({
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

async function updateLastActivity(userId) {
    const now = Math.floor(Date.now() / 1000);
    
    const command = new UpdateItemCommand({
        TableName: TABLE_NAME,
        Key: {
            userId: { S: userId }
        },
        UpdateExpression: 'SET lastActivity = :now',
        ExpressionAttributeValues: {
            ':now': { N: now.toString() }
        }
    });
    
    await dynamodbClient.send(command);
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


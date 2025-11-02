/**
 * AWS Lambda WebSocket $connect Handler
 * 
 * 功能：处理WebSocket连接事件，保存connectionId到DynamoDB
 * 
 * 触发方式：API Gateway WebSocket $connect 路由
 * 输入：event.requestContext.connectionId, event.queryStringParameters.userId
 * 输出：{ statusCode: 200 } 或错误响应
 */

const { DynamoDBClient, PutItemCommand } = require('@aws-sdk/client-dynamodb');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const TABLE_NAME = process.env.CONNECTIONS_TABLE || 'tap-ws-connections';
const TTL_HOURS = 24;

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
        
        const now = Math.floor(Date.now() / 1000);
        const ttl = now + (TTL_HOURS * 3600);
        
        const command = new PutItemCommand({
            TableName: TABLE_NAME,
            Item: {
                userId: { S: userId },
                connectionId: { S: connectionId },
                connectedAt: { N: now.toString() },
                ttl: { N: ttl.toString() }
            }
        });
        
        await dynamodbClient.send(command);
        
        log('INFO', 'Connection saved to DynamoDB', {
            requestId,
            userId,
            connectionId,
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


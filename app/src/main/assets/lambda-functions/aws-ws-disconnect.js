/**
 * AWS Lambda WebSocket $disconnect Handler
 * 
 * 功能：处理WebSocket断开事件，删除DynamoDB中的connectionId
 * 
 * 触发方式：API Gateway WebSocket $disconnect 路由
 * 输入：event.requestContext.connectionId
 * 输出：{ statusCode: 200 } 或错误响应
 */

const { DynamoDBClient, ScanCommand, DeleteItemCommand } = require('@aws-sdk/client-dynamodb');

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

async function findUserIdByConnectionId(connectionId) {
    const command = new ScanCommand({
        TableName: TABLE_NAME,
        FilterExpression: 'connectionId = :connId',
        ExpressionAttributeValues: {
            ':connId': { S: connectionId }
        },
        ProjectionExpression: 'userId'
    });
    
    const response = await dynamodbClient.send(command);
    
    if (response.Items && response.Items.length > 0) {
        return response.Items[0].userId.S;
    }
    
    return null;
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
        
        const userId = await findUserIdByConnectionId(connectionId);
        
        if (!userId) {
            log('WARN', 'Connection not found in DynamoDB', { requestId, connectionId });
            return {
                statusCode: 200,
                body: 'Connection not found (already cleaned)'
            };
        }
        
        const command = new DeleteItemCommand({
            TableName: TABLE_NAME,
            Key: {
                userId: { S: userId }
            }
        });
        
        await dynamodbClient.send(command);
        
        log('INFO', 'Connection deleted from DynamoDB', {
            requestId,
            userId,
            connectionId
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


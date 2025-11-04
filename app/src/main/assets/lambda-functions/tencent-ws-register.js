/**
 * 腾讯云云函数 WebSocket Register Handler
 * 
 * 功能：处理WebSocket连接事件，保存connectionId到COS
 * 
 * 触发方式：API网关 WebSocket连接路由
 * 输入：event.websocket.connectionID, event.queryString.userId
 * 输出：{ errNo: 0, errMsg: 'ok' } 或错误响应
 */

const COS = require('cos-nodejs-sdk-v5');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const BUCKET_NAME = process.env.CONNECTIONS_BUCKET;
const REGION = process.env.REGION || 'ap-guangzhou';
const TTL_HOURS = 24;

const cos = new COS({
    SecretId: process.env.TAP_SECRET_ID,
    SecretKey: process.env.TAP_SECRET_KEY
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

async function saveConnectionToCos(userId, connectionId) {
    if (!BUCKET_NAME) {
        throw new Error('CONNECTIONS_BUCKET not configured');
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
    
    return new Promise((resolve, reject) => {
        cos.putObject({
            Bucket: BUCKET_NAME,
            Region: REGION,
            Key: key,
            Body: JSON.stringify(connectionData),
            ContentType: 'application/json'
        }, (err, data) => {
            if (err) {
                log('ERROR', 'Failed to save connection to COS', {
                    userId,
                    connectionId,
                    error: err.message
                });
                reject(err);
            } else {
                log('INFO', 'Connection saved to COS', {
                    userId,
                    connectionId,
                    bucket: BUCKET_NAME,
                    key: key,
                    ttl
                });
                resolve();
            }
        });
    });
}

exports.main_handler = async (event) => {
    const requestId = event.requestId || 'unknown';
    
    try {
        log('INFO', 'WebSocket connection request', { requestId });
        
        const connectionId = event.websocket?.connectionID;
        if (!connectionId) {
            log('ERROR', 'No connectionID in event', { requestId });
            return {
                errNo: 400,
                errMsg: 'Missing connectionID'
            };
        }
        
        const userId = event.queryString?.userId;
        if (!userId) {
            log('WARN', 'No userId provided', { requestId, connectionId });
            return {
                errNo: 400,
                errMsg: 'Missing userId parameter'
            };
        }
        
        await saveConnectionToCos(userId, connectionId);
        
        log('INFO', 'WebSocket connection registered', {
            requestId,
            userId,
            connectionId
        });
        
        return {
            errNo: 0,
            errMsg: 'ok'
        };
        
    } catch (error) {
        log('ERROR', 'Failed to register connection', {
            requestId,
            error: error.message,
            stack: error.stack
        });
        
        return {
            errNo: 500,
            errMsg: 'Internal server error'
        };
    }
};


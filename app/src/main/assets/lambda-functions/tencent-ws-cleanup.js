/**
 * 腾讯云云函数 WebSocket Cleanup Handler
 * 
 * 功能：处理WebSocket断开事件，删除COS中的connectionId
 * 
 * 触发方式：API网关 WebSocket断开路由
 * 输入：event.websocket.connectionID
 * 输出：{ errNo: 0, errMsg: 'ok' } 或错误响应
 */

const COS = require('cos-nodejs-sdk-v5');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const BUCKET_NAME = process.env.CONNECTIONS_BUCKET;
const REGION = process.env.REGION || 'ap-guangzhou';

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

async function findUserIdByConnectionId(connectionId) {
    if (!BUCKET_NAME) {
        return null;
    }
    
    try {
        const prefix = 'tap-ws-connections/';
        const result = await new Promise((resolve, reject) => {
            cos.getBucket({
                Bucket: BUCKET_NAME,
                Region: REGION,
                Prefix: prefix,
                MaxKeys: 1000
            }, (err, data) => {
                if (err) reject(err);
                else resolve(data);
            });
        });
        
        if (!result.Contents || result.Contents.length === 0) {
            return null;
        }
        
        for (const object of result.Contents) {
            const getResult = await new Promise((resolve, reject) => {
                cos.getObject({
                    Bucket: BUCKET_NAME,
                    Region: REGION,
                    Key: object.Key
                }, (err, data) => {
                    if (err) reject(err);
                    else resolve(data);
                });
            });
            
            const connectionData = JSON.parse(getResult.Body);
            if (connectionData.connectionId === connectionId) {
                return connectionData.userId;
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

async function deleteConnectionFromCos(userId) {
    if (!BUCKET_NAME) {
        throw new Error('CONNECTIONS_BUCKET not configured');
    }
    
    const key = `tap-ws-connections/${userId}.json`;
    
    return new Promise((resolve, reject) => {
        cos.deleteObject({
            Bucket: BUCKET_NAME,
            Region: REGION,
            Key: key
        }, (err, data) => {
            if (err) {
                // 文件不存在不算错误
                if (err.statusCode === 404) {
                    log('WARN', 'Connection file not found (already cleaned)', {
                        userId,
                        key
                    });
                    resolve();
                } else {
                    log('ERROR', 'Failed to delete connection from COS', {
                        userId,
                        key,
                        error: err.message
                    });
                    reject(err);
                }
            } else {
                log('INFO', 'Connection deleted from COS', {
                    userId,
                    connectionId: userId,
                    bucket: BUCKET_NAME,
                    key: key
                });
                resolve();
            }
        });
    });
}

exports.main_handler = async (event) => {
    const requestId = event.requestId || 'unknown';
    
    try {
        log('INFO', 'WebSocket disconnect request', { requestId });
        
        const connectionId = event.websocket?.connectionID;
        if (!connectionId) {
            log('ERROR', 'No connectionID in event', { requestId });
            return {
                errNo: 400,
                errMsg: 'Missing connectionID'
            };
        }
        
        const userId = await findUserIdByConnectionId(connectionId);
        
        if (!userId) {
            log('WARN', 'Connection not found in COS', { requestId, connectionId });
            return {
                errNo: 0,
                errMsg: 'Connection not found (already cleaned)'
            };
        }
        
        await deleteConnectionFromCos(userId);
        
        log('INFO', 'WebSocket connection cleaned up', {
            requestId,
            connectionId
        });
        
        return {
            errNo: 0,
            errMsg: 'ok'
        };
        
    } catch (error) {
        log('ERROR', 'Failed to cleanup connection', {
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


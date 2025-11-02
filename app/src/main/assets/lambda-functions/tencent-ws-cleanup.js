/**
 * 腾讯云云函数 WebSocket Cleanup Handler
 * 
 * 功能：处理WebSocket断开事件，删除云数据库中的connectionId
 * 
 * 触发方式：API网关 WebSocket断开路由
 * 输入：event.websocket.connectionID
 * 输出：{ errNo: 0, errMsg: 'ok' } 或错误响应
 */

const crypto = require('crypto');
const https = require('https');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const DATABASE_ENV = process.env.DATABASE_ENV;
const COLLECTION_NAME = 'tap-ws-connections';

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

function generateCloudBaseSignature(secretId, secretKey, action, params, region) {
    const timestamp = Math.floor(Date.now() / 1000);
    const date = new Date(timestamp * 1000).toISOString().split('T')[0];
    const service = 'tcb';
    
    const canonicalHeaders = `content-type:application/json\nhost:${service}.tencentcloudapi.com\n`;
    const signedHeaders = 'content-type;host';
    const payload = JSON.stringify(params);
    const hashedPayload = crypto.createHash('sha256').update(payload).digest('hex');
    
    const canonicalRequest = `POST\n/\n\n${canonicalHeaders}\n${signedHeaders}\n${hashedPayload}`;
    const hashedCanonicalRequest = crypto.createHash('sha256').update(canonicalRequest).digest('hex');
    
    const credentialScope = `${date}/${service}/tc3_request`;
    const stringToSign = `TC3-HMAC-SHA256\n${timestamp}\n${credentialScope}\n${hashedCanonicalRequest}`;
    
    const kDate = crypto.createHmac('sha256', `TC3${secretKey}`).update(date).digest();
    const kService = crypto.createHmac('sha256', kDate).update(service).digest();
    const kSigning = crypto.createHmac('sha256', kService).update('tc3_request').digest();
    const signature = crypto.createHmac('sha256', kSigning).update(stringToSign).digest('hex');
    
    const authorization = `TC3-HMAC-SHA256 Credential=${secretId}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
    
    return { authorization, timestamp, payload };
}

async function deleteConnectionFromDatabase(connectionId) {
    const secretId = process.env.TENCENTCLOUD_SECRETID;
    const secretKey = process.env.TENCENTCLOUD_SECRETKEY;
    const region = process.env.REGION || 'ap-guangzhou';
    
    if (!secretId || !secretKey || !DATABASE_ENV) {
        throw new Error('CloudBase credentials not configured');
    }
    
    const query = `db.collection('${COLLECTION_NAME}').where({
        connectionId: '${connectionId}'
    }).remove()`;
    
    const params = {
        EnvId: DATABASE_ENV,
        Query: query
    };
    
    const { authorization, timestamp, payload } = generateCloudBaseSignature(
        secretId,
        secretKey,
        'ExecuteCloudFunction',
        params,
        region
    );
    
    return new Promise((resolve, reject) => {
        const options = {
            hostname: 'tcb.tencentcloudapi.com',
            port: 443,
            path: '/',
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(payload),
                'Authorization': authorization,
                'X-TC-Action': 'ExecuteCloudFunction',
                'X-TC-Version': '2018-06-08',
                'X-TC-Timestamp': timestamp.toString(),
                'X-TC-Region': region
            },
            timeout: 10000
        };
        
        const req = https.request(options, (res) => {
            let data = '';
            
            res.on('data', chunk => {
                data += chunk;
            });
            
            res.on('end', () => {
                try {
                    const response = JSON.parse(data);
                    
                    if (response.Response && response.Response.Error) {
                        const error = response.Response.Error;
                        log('WARN', 'CloudBase API error (may not exist)', {
                            code: error.Code,
                            message: error.Message
                        });
                        resolve();
                    } else {
                        log('INFO', 'Connection deleted from database', { connectionId });
                        resolve();
                    }
                } catch (parseErr) {
                    log('ERROR', 'Failed to parse response', { data, error: parseErr.message });
                    reject(parseErr);
                }
            });
        });
        
        req.on('error', (error) => {
            log('ERROR', 'Request failed', { error: error.message });
            reject(error);
        });
        
        req.on('timeout', () => {
            req.destroy();
            log('ERROR', 'Request timeout');
            reject(new Error('Request timeout'));
        });
        
        req.write(payload);
        req.end();
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
        
        await deleteConnectionFromDatabase(connectionId);
        
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


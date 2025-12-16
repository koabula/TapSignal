/**
 * 腾讯云 Web 函数（函数 URL）模式：在 9000 端口启动 WebSocket 与 HTTP 服务
 * - WebSocket：wss://<function-url-host>/ 由函数 URL 透传到本地 9000 端口
 * - HTTP：/push 用于从 Webhook 写入通知到 COS 队列
 */

const http = require('http');
const url = require('url');
const WebSocket = require('ws');
const COS = require('cos-nodejs-sdk-v5');

const LOG_LEVEL = process.env.LOG_LEVEL || 'INFO';
const BUCKET_NAME = process.env.CONNECTIONS_BUCKET;
const REGION = process.env.REGION || 'ap-guangzhou';
const TTL_HOURS = 24;
const QUEUE_PREFIX = 'tap-ws-queue';
const CONNECTION_PREFIX = 'tap-ws-connections';

const cos = new COS({
    SecretId: process.env.TAP_SECRET_ID,
    SecretKey: process.env.TAP_SECRET_KEY
});

function log(level, message, data = {}) {
    const levels = { DEBUG: 0, INFO: 1, WARN: 2, ERROR: 3 };
    if (levels[level] >= levels[LOG_LEVEL]) {
        console.log(JSON.stringify({ level, message, timestamp: new Date().toISOString(), ...data }));
    }
}

async function putObject(Key, Body, ContentType = 'application/json') {
    return new Promise((resolve, reject) => {
        cos.putObject({ Bucket: BUCKET_NAME, Region: REGION, Key, Body, ContentType }, (err, data) => {
            if (err) return reject(err);
            resolve(data);
        });
    });
}

async function deleteObject(Key) {
    return new Promise((resolve) => {
        cos.deleteObject({ Bucket: BUCKET_NAME, Region: REGION, Key }, () => resolve());
    });
}

async function listQueueKeys(userId, maxKeys = 10) {
    const prefix = `${QUEUE_PREFIX}/${userId}/`;
    return new Promise((resolve, reject) => {
        cos.getBucket({ Bucket: BUCKET_NAME, Region: REGION, Prefix: prefix, MaxKeys: maxKeys }, (err, data) => {
            if (err) return reject(err);
            const keys = (data.Contents || [])
                .map(o => o.Key)
                .filter(k => k && k.startsWith(prefix));
            resolve(keys);
        });
    });
}

async function getObject(Key) {
    return new Promise((resolve, reject) => {
        cos.getObject({ Bucket: BUCKET_NAME, Region: REGION, Key }, (err, data) => {
            if (err) return reject(err);
            resolve(data && data.Body ? data.Body.toString('utf8') : null);
        });
    });
}

async function saveConnectionToCos(userId, connectionId) {
    if (!BUCKET_NAME) throw new Error('CONNECTIONS_BUCKET not configured');
    const now = Math.floor(Date.now() / 1000);
    const ttl = now + (TTL_HOURS * 3600);
    const key = `${CONNECTION_PREFIX}/${userId}.json`;
    await putObject(key, JSON.stringify({ userId, connectionId, connectedAt: now, ttl }));
    log('INFO', 'Connection saved to COS', { userId, connectionId, key });
}

async function removeConnectionFromCos(userId) {
    if (!BUCKET_NAME) return;
    const key = `${CONNECTION_PREFIX}/${userId}.json`;
    await deleteObject(key);
    log('DEBUG', 'Connection removed from COS', { userId, key });
}

async function enqueuePush(userId, message) {
    const key = `${QUEUE_PREFIX}/${userId}/${Date.now()}-${Math.random().toString(36).slice(2)}.json`;
    await putObject(key, JSON.stringify({ userId, message, timestamp: Date.now() }));
    return key;
}

function createServer() {
    const server = http.createServer(async (req, res) => {
        try {
            const parsed = url.parse(req.url, true);
            if (req.method === 'POST' && parsed.pathname === '/push') {
                let body = '';
                req.on('data', chunk => body += chunk);
                req.on('end', async () => {
                    try {
                        const data = body ? JSON.parse(body) : {};
                        const userId = data.userId;
                        const message = data.message;
                        if (!userId || !message) {
                            res.writeHead(400, { 'Content-Type': 'application/json' });
                            res.end(JSON.stringify({ success: false, error: 'Missing userId or message' }));
                            return;
                        }
                        const key = await enqueuePush(userId, message);
                        res.writeHead(200, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: true, key }));
                    } catch (e) {
                        log('ERROR', 'Push enqueue error', { error: e.message });
                        res.writeHead(500, { 'Content-Type': 'application/json' });
                        res.end(JSON.stringify({ success: false }));
                    }
                });
                return;
            }
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: true }));
        } catch (e) {
            log('ERROR', 'HTTP handler error', { error: e.message });
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ ok: false }));
        }
    });

    const wss = new WebSocket.Server({ server });
    const clients = new Map(); // userId -> { socket, pollTimer }

    function startPolling(userId, socket) {
        const intervalMs = 1000; // 快速轮询以近实时推送
        const timer = setInterval(async () => {
            try {
                const keys = await listQueueKeys(userId, 10);
                for (const key of keys) {
                    const content = await getObject(key);
                    if (content && socket.readyState === WebSocket.OPEN) {
                        socket.send(content);
                    }
                    await deleteObject(key);
                }
            } catch (e) {
                log('WARN', 'Polling error', { userId, error: e.message });
            }
        }, intervalMs);
        return timer;
    }

    wss.on('connection', async (socket, request) => {
        try {
            const parsed = url.parse(request.url, true);
            const userId = parsed.query && parsed.query.userId;
            if (!userId) {
                socket.close(1008, 'Missing userId');
                return;
            }
            const connectionId = `${userId}-${Date.now()}`;
            await saveConnectionToCos(userId, connectionId);

            const pollTimer = startPolling(userId, socket);
            clients.set(userId, { socket, pollTimer });
            log('INFO', 'WebSocket connected', { userId });

            socket.on('message', (data) => {
                log('DEBUG', 'WS incoming', { userId, length: ('' + data).length });
            });

            socket.on('close', async () => {
                clearInterval(pollTimer);
                clients.delete(userId);
                await removeConnectionFromCos(userId);
                log('INFO', 'WebSocket closed', { userId });
            });

            socket.on('error', (err) => {
                log('WARN', 'WebSocket error', { userId, error: err.message });
            });
        } catch (e) {
            log('ERROR', 'Connection setup error', { error: e.message });
            try { socket.close(1011, 'Internal error'); } catch (_) {}
        }
    });

    const PORT = 9000;
    server.listen(PORT, '0.0.0.0', () => {
        log('INFO', 'Server started', { port: PORT });
    });
}

// Web 函数入口：启动一次服务器进程
if (!global.__WS_SERVER_STARTED__) {
    global.__WS_SERVER_STARTED__ = true;
    createServer();
}

// 兼容 Web 函数返回（被忽略，HTTP 由自建服务器处理）
exports.main_handler = async function () {
    return { statusCode: 200, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ok: true }) };
};


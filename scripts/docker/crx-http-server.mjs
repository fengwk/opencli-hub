#!/usr/bin/env node
//
// crx-http-server.mjs
//
// F1 PoC: 容器内本地 HTTP server，仅监听 127.0.0.1，serve：
//   GET /extension.crx       -> CRX3 file
//   GET /updates.xml         -> Chrome update manifest (XML)
//   GET /info                -> build-info.json（人读）
//   GET /healthz             -> 200 OK
//
// 不使用任何 npm 依赖（仅 Node.js 内置 http/fs/path）。
//
// 用法（容器内）：
//   OPENCLI_CRX_HTTP_PORT=18181 OPENCLI_UPDATE_BASE_URL=http://127.0.0.1:18181 \
//     crx-http-server.mjs /opt/opencli/extension.crx /opt/opencli/updates.xml /opt/opencli/build-info.json
//
// 设计动机：Chrome ExtensionInstallForcelist/ExtensionSettings 支持 update_url 使用 http scheme
//   （Chrome Enterprise 文档明确说明），通过 managed policy 从 127.0.0.1 拉取 CRX3
//   与 update manifest，规避 "google-chrome-stable" 直接拒绝 --load-extension 的限制。

import * as http from 'node:http';
import * as fs from 'node:fs';
import * as path from 'node:path';

function die(msg, code = 1) {
    console.error(`[crx-http-server] ${msg}`);
    process.exit(code);
}

if (process.argv.length < 5) {
    die(`usage: crx-http-server.mjs <crx-path> <update-manifest> <info-json>`, 2);
}

const CRX_PATH = path.resolve(process.argv[2]);
const MANIFEST_PATH = path.resolve(process.argv[3]);
const INFO_PATH = path.resolve(process.argv[4]);

for (const p of [CRX_PATH, MANIFEST_PATH, INFO_PATH]) {
    if (!fs.existsSync(p)) die(`required file missing: ${p}`);
}

// 占位符；运行期由 -e 替换为实际端口（脚本启动时已知）
let UPDATE_BASE = process.env.OPENCLI_UPDATE_BASE_URL || '';
if (!UPDATE_BASE) die('OPENCLI_UPDATE_BASE_URL env is required');

const HOST = '127.0.0.1';
const PORT = Number(process.env.OPENCLI_CRX_HTTP_PORT || 18181);

let manifestRaw = fs.readFileSync(MANIFEST_PATH, 'utf8');
// 一次性把 __UPDATE_BASE__ 替换为真实 base
manifestRaw = manifestRaw.replace('__UPDATE_BASE__', UPDATE_BASE);
const infoJson = fs.readFileSync(INFO_PATH, 'utf8');
const crxBuf = fs.readFileSync(CRX_PATH);
const crxSize = crxBuf.length;

const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://${HOST}:${PORT}`);
    // 仅 GET；其它一律 405
    if (req.method !== 'GET') {
        res.writeHead(405, { 'Content-Type': 'text/plain' });
        res.end('Method Not Allowed');
        return;
    }
    // 严格只服务下面三条路径；其余 404 防止任何意外代理
    if (url.pathname === '/healthz') {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('ok\n');
        return;
    }
    if (url.pathname === '/extension.crx') {
        res.writeHead(200, {
            'Content-Type': 'application/x-chrome-extension',
            'Content-Length': crxSize,
            'Cache-Control': 'no-store',
        });
        res.end(crxBuf);
        return;
    }
    if (url.pathname === '/updates.xml') {
        res.writeHead(200, {
            'Content-Type': 'application/xml',
            'Cache-Control': 'no-store',
        });
        res.end(manifestRaw);
        return;
    }
    if (url.pathname === '/info') {
        res.writeHead(200, {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
        });
        res.end(infoJson);
        return;
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not Found');
});

server.listen(PORT, HOST, () => {
    console.log(`[crx-http-server] listening on http://${HOST}:${PORT}`);
    console.log(`[crx-http-server]   extension.crx  (${crxSize} bytes)`);
    console.log(`[crx-http-server]   updates.xml`);
    console.log(`[crx-http-server]   info / healthz`);
});

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT',  () => server.close(() => process.exit(0)));

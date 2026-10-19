#!/usr/bin/env node
// relay-bridge.mjs —— 桌面侧 MCP over relay 桥（轴 C 最小闭环，契约 v0.1.8）。
//
// 与 stdio-bridge.mjs 同形：stdin 每行一条 JSON-RPC → 经 relay 定向中转发给手机 →
// 轮询收应答 → stdout 回写。区别：上游从「直连 HTTP :8765」换成「relay REST 中转」，
// 外网可达（手机不在线即失败——schedule-app.md:331 已接受的口径）。
//
// 鉴权双保险：relay 账号 JWT（本脚本登录获取）+ MCP x-api-key（--token，app「MCP 服务」页）
// 前提：桌面 relay 账号与手机 relay 账号互为好友（relay 侧 ACL）。
//
// 用法：
//   node relay-bridge.mjs \
//     --relay https://relay.example.com \
//     --user desktop_bot --pass 'secret' \
//     --peer <手机端 relay user_id> \
//     --token <mcp x-api-key>
//   （亦可用环境变量 RELAY_URL / RELAY_USER / RELAY_PASS / SHIGUANG_PEER / SHIGUANG_TOKEN）
//
// 首次接入：先不带 --peer 跑一次，脚本会打印自己的 user_id（把手机端加为好友时用）。

import { randomUUID } from 'node:crypto';

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, v, i, a) => {
  if (v.startsWith('--')) acc.push([v.slice(2), a[i + 1]]);
  return acc;
}, []));

const RELAY = (args.relay || process.env.RELAY_URL || '').replace(/\/+$/, '');
const USER = args.user || process.env.RELAY_USER || '';
const PASS = args.pass || process.env.RELAY_PASS || '';
const PEER = args.peer || process.env.SHIGUANG_PEER || '';
const TOKEN = args.token || process.env.SHIGUANG_TOKEN || '';

if (!RELAY || !USER || !PASS) {
  console.error('用法: relay-bridge.mjs --relay <url> --user <名> --pass <密> [--peer <user_id>] [--token <mcp token>]');
  process.exit(1);
}

const TTL = 120;              // 单据 TTL（秒），与 RelayClient/契约注释同源
const POLL_INTERVAL = 500;    // 应答轮询间隔
const POLL_TIMEOUT = 60_000;  // 应答等待上限

let accessToken = null;

async function rest(method, path, body, bearer) {
  const res = await fetch(RELAY + path, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(bearer ? { authorization: `Bearer ${bearer}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 非 JSON 响应体 */ }
  return { status: res.status, json };
}

async function login(force = false) {
  if (accessToken && !force) return accessToken;
  const r = await rest('POST', '/v1/auth/login', { username: USER, password: PASS });
  if (r.status !== 200 || !r.json?.access_token) {
    throw new Error(`relay 登录失败: ${r.status}`);
  }
  accessToken = r.json.access_token;
  return accessToken;
}

// 自证身份：无 --peer 时打印本端 user_id（建好友用），退出。
if (!PEER) {
  await login();
  const me = await rest('GET', '/v1/users/me', undefined, accessToken);
  console.error(`[relay-bridge] 本端 user_id = ${me.json?.user_id ?? '(未取到)'}`);
  console.error('[relay-bridge] 请与手机端互加好友后，用 --peer <手机 user_id> 重跑。');
  process.exit(0);
}

// 发单 → 在线直推（手机端 WSS）→ 轮询 pending 收 role=resp 的应答。
async function callPeer(line, msgId) {
  const corrId = randomUUID();
  const token = await login();
  const sent = await rest('POST', '/v1/relay/msg', {
    recipient_user_id: PEER,
    kind: 'mcp',
    cipher: Buffer.from(line, 'utf8').toString('base64'),
    ttl_seconds: TTL,
    header: { role: 'req', corr_id: corrId, token: TOKEN },
  }, token);

  if (sent.status === 401) {
    // token 过期 → 重登一次重试
    await login(true);
    return callPeer(line, msgId);
  }
  if (sent.status !== 202) {
    throw new Error(`relay 发单失败: ${sent.status} ${JSON.stringify(sent.json)}`);
  }
  if (sent.json?.delivered_online === false) {
    return { jsonrpc: '2.0', id: msgId, error: { code: -32002, message: '手机不在线（MCP 服务未开或 relay 未连）' } };
  }

  const deadline = Date.now() + POLL_TIMEOUT;
  while (Date.now() < deadline) {
    await new Promise(r => setTimeout(r, POLL_INTERVAL));
    const pend = await rest('GET', '/v1/relay/pending', undefined, accessToken);
    if (pend.status === 401) await login(true);
    const msgs = pend.json?.messages ?? [];
    // 应答匹配：JSON-RPC 自带 id 回显（手机端 handleJsonRpc 原样返回请求 id），
    // 无需 header/corr_id（REST pending 不回 header——MVP 零 DDL 口径）。
    // 请求串行（单在途），pending 中 id 匹配者即本单应答，其余 mcp 单按陈旧清理。
    let hit = null;
    const stale = [];
    for (const m of msgs) {
      if (m.kind !== 'mcp') continue;
      let parsed = null;
      try { parsed = JSON.parse(Buffer.from(m.cipher, 'base64').toString('utf8')); } catch { /* 非 JSON */ }
      if (!hit && parsed && parsed.id === msgId && !Array.isArray(parsed)) hit = m;
      else stale.push(m.msg_id);
    }
    if (stale.length) await rest('POST', '/v1/relay/ack', { message_ids: stale }, accessToken);
    if (hit) {
      const respText = Buffer.from(hit.cipher, 'base64').toString('utf8');
      await rest('POST', '/v1/relay/ack', { message_ids: [hit.msg_id] }, accessToken);
      try { return JSON.parse(respText); } catch { return respText; }
    }
  }
  return { jsonrpc: '2.0', id: msgId, error: { code: -32001, message: '应答超时（60s）' } };
}

// 请求串行队列（与 stdio-bridge 同纪律：保序）
let queue = Promise.resolve();
let buffer = '';

process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, idx).trim();
    buffer = buffer.slice(idx + 1);
    if (!line) continue;
    let msgId = null;
    try { msgId = JSON.parse(line).id ?? null; } catch { /* 坏行走 null id */ }
    queue = queue
      .then(() => callPeer(line, msgId))
      .then(resp => process.stdout.write(JSON.stringify(resp) + '\n'))
      .catch(err => process.stdout.write(JSON.stringify({
        jsonrpc: '2.0', id: msgId, error: { code: -32000, message: String(err.message ?? err) },
      }) + '\n'));
  }
});
process.stdin.on('end', () => { /* 队列自然排空后随进程退出 */ });

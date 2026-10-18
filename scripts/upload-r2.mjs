#!/usr/bin/env node
// upload-r2.mjs — 零依赖上传/列出/删除 Cloudflare R2 对象（AWS SigV4，region=auto）
// 用法: node scripts/upload-r2.mjs <localFile> <remoteKey> [contentType] [cacheControl]
//       node scripts/upload-r2.mjs --list <prefix>          列出对象 key（逐行输出）
//       node scripts/upload-r2.mjs --delete <remoteKey>     删除单个对象
// 凭证/桶从环境变量读取: R2_ACCOUNT_ID R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY R2_BUCKET
import { readFileSync } from 'node:fs';
import { createHash, createHmac } from 'node:crypto';

const [, , arg1, arg2, contentType = 'application/octet-stream', cacheControl] =
  process.argv;
const MODE = arg1 === '--list' ? 'list' : arg1 === '--delete' ? 'delete' : 'put';
const remoteKey = arg2;
if (!remoteKey) {
  console.error(
    '用法: node scripts/upload-r2.mjs <localFile> <remoteKey> [contentType] [cacheControl]\n' +
      '      node scripts/upload-r2.mjs --list <prefix>\n' +
      '      node scripts/upload-r2.mjs --delete <remoteKey>'
  );
  process.exit(2);
}
const localPath = arg1;
const accountId = process.env.R2_ACCOUNT_ID;
const ak = process.env.R2_ACCESS_KEY_ID;
const sk = process.env.R2_SECRET_ACCESS_KEY;
const bucket = process.env.R2_BUCKET;
if (!accountId || !ak || !sk || !bucket) {
  console.error(
    'FAIL: 缺少 R2 环境变量（R2_ACCOUNT_ID / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY / R2_BUCKET）'
  );
  process.exit(1);
}

const host = `${accountId}.r2.cloudflarestorage.com`;
const region = 'auto';
const service = 's3';
const algorithm = 'AWS4-HMAC-SHA256';
const amzDate = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '');
const dateStamp = amzDate.slice(0, 8);
const credentialScope = `${dateStamp}/${region}/${service}/aws4_request`;

function hmac(key, data) {
  return createHmac('sha256', key).update(data).digest();
}
function sha256hex(s) {
  return createHash('sha256').update(s).digest('hex');
}
function sign(method, path, query, payloadHash, extraHeaders = {}) {
  const canonicalHeaders =
    Object.entries(extraHeaders)
      .map(([k, v]) => `${k}:${v}\n`)
      .sort()
      .join('') +
    `host:${host}\nx-amz-content-sha256:${payloadHash}\nx-amz-date:${amzDate}\n`;
  const signedHeaders =
    Object.keys(extraHeaders)
      .map((k) => k.toLowerCase())
      .sort()
      .concat(['host', 'x-amz-content-sha256', 'x-amz-date'])
      .join(';');
  const canonicalRequest = `${method}\n/${bucket}/${path}\n${query}\n${canonicalHeaders}\n${signedHeaders}\n${payloadHash}`;
  const stringToSign = `${algorithm}\n${amzDate}\n${credentialScope}\n${sha256hex(canonicalRequest)}`;
  const signingKey = hmac(
    hmac(hmac(hmac(`AWS4${sk}`, dateStamp), region), service),
    'aws4_request'
  );
  const signature = createHmac('sha256', signingKey)
    .update(stringToSign)
    .digest('hex');
  return `${algorithm} Credential=${ak}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
}

const baseHeaders = {
  Authorization: '',
  'x-amz-date': amzDate,
  'x-amz-content-sha256': 'UNSIGNED-PAYLOAD',
};

if (MODE === 'put') {
  // 大文件用 UNSIGNED-PAYLOAD，避免整段计算哈希（R2 支持）
  const payloadHash = 'UNSIGNED-PAYLOAD';
  // cache-control 参与签名（固定 key 对象须显式 no-cache，防公开域 CDN 启发式缓存串版，
  // 2026-10-15 实测踩坑：APK 固定 key 被缓存旧字节导致客户端 sha256 校验失败）
  const extra = cacheControl ? { 'cache-control': cacheControl } : {};
  const res = await fetch(`https://${host}/${bucket}/${remoteKey}`, {
    method: 'PUT',
    headers: {
      ...baseHeaders,
      'Content-Type': contentType,
      ...(cacheControl ? { 'Cache-Control': cacheControl } : {}),
      Authorization: sign('PUT', remoteKey, '', payloadHash, extra),
    },
    body: readFileSync(localPath),
  });
  if (!res.ok) {
    const txt = await res.text();
    console.error(`FAIL: 上传失败 HTTP ${res.status}: ${txt}`);
    process.exit(1);
  }
  console.log(`OK: 上传 ${localPath} → s3://${bucket}/${remoteKey}`);
} else if (MODE === 'list') {
  // ListObjectsV2：query 参与签名（按 key 升序）
  const query = `list-type=2&prefix=${encodeURIComponent(remoteKey)}`;
  const res = await fetch(`https://${host}/${bucket}/?${query}`, {
    method: 'GET',
    headers: {
      ...baseHeaders,
      Authorization: sign('GET', '', query, 'UNSIGNED-PAYLOAD'),
    },
  });
  if (!res.ok) {
    const txt = await res.text();
    console.error(`FAIL: 列举失败 HTTP ${res.status}: ${txt}`);
    process.exit(1);
  }
  const xml = await res.text();
  for (const m of xml.matchAll(/<Key>([^<]+)<\/Key>/g)) {
    console.log(m[1]);
  }
} else {
  // delete
  const res = await fetch(`https://${host}/${bucket}/${remoteKey}`, {
    method: 'DELETE',
    headers: {
      ...baseHeaders,
      Authorization: sign('DELETE', remoteKey, '', 'UNSIGNED-PAYLOAD'),
    },
  });
  if (!res.ok && res.status !== 204) {
    const txt = await res.text();
    console.error(`FAIL: 删除失败 HTTP ${res.status}: ${txt}`);
    process.exit(1);
  }
  console.log(`OK: 已删除 s3://${bucket}/${remoteKey}`);
}

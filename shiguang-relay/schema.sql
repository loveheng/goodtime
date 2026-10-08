-- ============================================================================
-- shiguang-relay · 数据库契约 (PostgreSQL)  v0.1.0-contract
--
-- 2026-10-09 服务端架构审查修正内建：
--   - friendships 仅 4 个有意义列 (user_id_a, user_id_b, status, created_at)，零 nickname/phone
--   - users 零 display_name；昵称/备注仅存 encrypted_profile（端侧全密文）
--   - 全库零 entity_key 列（不接收/不存储车次/预约码等业务代号）
--   - team_schedule_events 走全局 seq_id（UNIQUE(bundle_id, seq_id)），服务端绝对定序
--   - relay_messages TTL 即焚（expires_at + status），与持久化事件流解耦
--
-- 注意：本文件为 DDL 契约，不含任何 Go 业务代码（Phase 0 停火协议）。
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- 提供 gen_random_uuid()

-- 1) 用户：服务端零明文昵称 ------------------------------------------------
CREATE TABLE users (
  user_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  identity_key_pub   BYTEA NOT NULL,            -- Ed25519 身份公钥
  signed_prekey_pub  BYTEA NOT NULL,            -- X25519 签名预密钥
  signed_prekey_sig  BYTEA NOT NULL,            -- 身份密钥对预密钥的签名
  push_token         TEXT,                      -- FCM/APNs；服务端不解析内容
  encrypted_profile  BYTEA,                     -- 端侧全密文（昵称/备注/头像），服务端不可读
  recovery_vault     BYTEA,                     -- 端侧加密的主密钥恢复金库
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  -- 注册约束（应用层强制）：必须携带 signed_pop + pow_solution，否则拒收（防女巫/存储轰炸）
  CONSTRAINT users_identity_key_uniq UNIQUE (identity_key_pub)
);

-- 2) 好友关系：仅 4 个有意义列，零 nickname/phone -------------------------
CREATE TABLE friendships (
  user_id_a   UUID NOT NULL REFERENCES users(user_id),
  user_id_b   UUID NOT NULL REFERENCES users(user_id),
  status      TEXT NOT NULL CHECK (status IN ('pending', 'accepted', 'blocked')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id_a, user_id_b),
  CONSTRAINT no_self_friend CHECK (user_id_a <> user_id_b)
);
CREATE INDEX idx_friendships_b ON friendships (user_id_b, status);

-- 3) 方案包 bundles：持有 max_seq_id 行锁定序 ------------------------------
CREATE TABLE bundles (
  bundle_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  initiator_id  UUID NOT NULL REFERENCES users(user_id),
  status        TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
  max_seq_id    BIGINT NOT NULL DEFAULT 0,      -- 写事件前 SELECT ... FOR UPDATE 行锁取号
  encrypted_meta BYTEA,                         -- 端侧加密的 bundle 元信息
  key_version   INT NOT NULL DEFAULT 1,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bundles_initiator ON bundles (initiator_id, status);

-- 4) 拓扑骨架 bundle_skeletons：Quorum 票数仲裁 ----------------------------
CREATE TABLE bundle_skeletons (
  skeleton_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  bundle_id     UUID NOT NULL REFERENCES bundles(bundle_id),
  topology      JSONB NOT NULL,                 -- 谁可投票/可见（UUID 边，零明文）
  quorum        INT NOT NULL DEFAULT 1,          -- 落槌门限
  version       INT NOT NULL DEFAULT 1,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (bundle_id, skeleton_id)
);

-- 5) 协同事件流 team_schedule_events：事实/排程统一通道 --------------------
CREATE TABLE team_schedule_events (
  event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  bundle_id       UUID NOT NULL REFERENCES bundles(bundle_id),
  seq_id          BIGINT NOT NULL,              -- 服务端定序，UNIQUE(bundle_id, seq_id)
  action_type     VARCHAR(32) NOT NULL,         -- Envelope.ActionType 枚举值（含 ATTACH_FACT 等）
  sender_user_id  UUID NOT NULL REFERENCES users(user_id),
  cipher          BYTEA NOT NULL,               -- AEAD(K_bundle, payload)，≤256KB
  topology_ref    UUID REFERENCES bundle_skeletons(skeleton_id),
  client_msg_id   TEXT NOT NULL,                -- 幂等去重键
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (bundle_id, seq_id)
);
CREATE INDEX idx_events_bundle_seq ON team_schedule_events (bundle_id, seq_id);
CREATE INDEX idx_events_client_msg ON team_schedule_events (bundle_id, client_msg_id);

-- 6) 密钥信封 key_envelopes（X3DH OPK 池）--------------------------------
CREATE TABLE key_envelopes (
  user_id    UUID NOT NULL REFERENCES users(user_id),
  key_id     TEXT NOT NULL,
  prekey_pub BYTEA NOT NULL,
  signature  BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, key_id)
);

-- 7) P2P 瞬时信令 relay_messages：TTL 即焚（非 bundle 事件）---------------
CREATE TABLE relay_messages (
  msg_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recipient_user_id UUID NOT NULL REFERENCES users(user_id),
  sender_user_id    UUID NOT NULL REFERENCES users(user_id),
  kind              TEXT NOT NULL,
  cipher            BYTEA NOT NULL,              -- ≤256KB 网关硬限
  status            TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'delivered', 'acked')),
  expires_at        TIMESTAMPTZ NOT NULL,        -- TTL 即焚；后台清理 cron 删除过期/acked
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_relay_recipient ON relay_messages (recipient_user_id, status, expires_at);

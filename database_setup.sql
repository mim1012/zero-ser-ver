-- ============================================================
-- Zero Server — Supabase 테이블 생성 스크립트
-- Control DB (hdtjkaieulphqwmcjhcx) 에서 실행
-- ============================================================

-- 1. device_groups (기기 그룹)
CREATE TABLE IF NOT EXISTS device_groups (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    leader_device_id VARCHAR(100),
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. devices (기기)
CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(100) PRIMARY KEY,
    group_id BIGINT REFERENCES device_groups(id) ON DELETE SET NULL,
    role VARCHAR(20) DEFAULT 'follower',
    status VARCHAR(20) DEFAULT 'active',
    last_heartbeat TIMESTAMPTZ DEFAULT NOW(),
    current_ip VARCHAR(50),
    tasks_completed INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. distributedTasks (작업 큐) — 이미 있으면 스킵
CREATE TABLE IF NOT EXISTS "distributedTasks" (
    id BIGSERIAL PRIMARY KEY,
    "experimentId" BIGINT,
    "taskType" VARCHAR(50) DEFAULT 'production',
    "targetNodeType" VARCHAR(50) DEFAULT 'worker',
    "assignedNodeId" VARCHAR(100),
    "productId" BIGINT,
    keyword VARCHAR(200),
    "nvMid" VARCHAR(100),
    "productName" VARCHAR(500),
    "productUrl" TEXT,
    variables TEXT DEFAULT '{}',
    status VARCHAR(20) DEFAULT 'pending',
    priority INT DEFAULT 1,
    result TEXT,
    "beforeRank" INT,
    "afterRank" INT,
    "createdAt" TIMESTAMPTZ DEFAULT NOW(),
    "assignedAt" TIMESTAMPTZ,
    "startedAt" TIMESTAMPTZ,
    "completedAt" TIMESTAMPTZ
);

-- 4. task_logs (작업 로그)
CREATE TABLE IF NOT EXISTS task_logs (
    id BIGSERIAL PRIMARY KEY,
    traffic_id BIGINT,
    device_id VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    message TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ============================================================
-- 인덱스
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_dt_status ON "distributedTasks"(status);
CREATE INDEX IF NOT EXISTS idx_dt_priority ON "distributedTasks"(priority DESC);
CREATE INDEX IF NOT EXISTS idx_dt_assigned ON "distributedTasks"("assignedNodeId");
CREATE INDEX IF NOT EXISTS idx_devices_group ON devices(group_id);
CREATE INDEX IF NOT EXISTS idx_devices_heartbeat ON devices(last_heartbeat);
CREATE INDEX IF NOT EXISTS idx_task_logs_traffic ON task_logs(traffic_id);
CREATE INDEX IF NOT EXISTS idx_task_logs_device ON task_logs(device_id);
CREATE INDEX IF NOT EXISTS idx_task_logs_created ON task_logs(created_at DESC);

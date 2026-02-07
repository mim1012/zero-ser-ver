-- ============================================================
-- Zero Traffic v2 — 추가 DB 스키마 (Supabase)
-- 기존 테이블: devices, traffic_navershopping, slot_naver, task_logs
-- ============================================================

-- 시나리오 테이블
CREATE TABLE IF NOT EXISTS scenarios (
    id TEXT PRIMARY KEY,                    -- "route1-search-shopping"
    name TEXT NOT NULL,                     -- "검색 → 쇼핑탭 → 상품"
    version INT DEFAULT 1,                  -- 버전 (APK 캐시 비교용)
    scenario_json JSONB NOT NULL,           -- JSON DSL 전체
    enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- 기기-시나리오 매핑 (가중치 기반 경로 분배)
CREATE TABLE IF NOT EXISTS device_scenarios (
    device_id TEXT NOT NULL,                -- devices.id 참조
    scenario_id TEXT NOT NULL REFERENCES scenarios(id) ON DELETE CASCADE,
    weight INT DEFAULT 1,                   -- 가중치 (route1:3, route2:1 → 75% route1)
    PRIMARY KEY (device_id, scenario_id)
);

-- JS 스크립트 (서버에서 APK에 동적 주입)
CREATE TABLE IF NOT EXISTS scripts (
    name TEXT PRIMARY KEY,                  -- "captcha-solver", "product-finder"
    version INT DEFAULT 1,
    content TEXT NOT NULL,                  -- JS 코드
    hash TEXT,                              -- SHA256 앞 16자리
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- CAPTCHA 해결 로그 (비용 추적)
CREATE TABLE IF NOT EXISTS captcha_logs (
    id BIGSERIAL PRIMARY KEY,
    device_id TEXT NOT NULL,
    traffic_id INT,
    question TEXT,
    answer TEXT,
    confidence TEXT,                        -- "high", "medium", "low"
    model TEXT,                             -- "claude-sonnet-4-20250514"
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 인덱스
CREATE INDEX IF NOT EXISTS idx_scenarios_enabled ON scenarios(enabled);
CREATE INDEX IF NOT EXISTS idx_device_scenarios_device ON device_scenarios(device_id);
CREATE INDEX IF NOT EXISTS idx_captcha_logs_device ON captcha_logs(device_id);
CREATE INDEX IF NOT EXISTS idx_captcha_logs_created ON captcha_logs(created_at);

-- ============================================================
-- 초기 데이터: 시나리오 2개 등록
-- ============================================================

INSERT INTO scenarios (id, name, version, scenario_json, enabled)
VALUES (
    'route1-search-shopping',
    '검색 → 쇼핑탭 → 상품',
    1,
    -- route1 JSON은 별도 파일 참조
    '{"id":"route1-search-shopping","name":"검색 → 쇼핑탭 → 상품","version":1,"steps":[]}'::jsonb,
    true
) ON CONFLICT (id) DO NOTHING;

INSERT INTO scenarios (id, name, version, scenario_json, enabled)
VALUES (
    'route2-store-search',
    '스토어 → 쇼핑홈 → 검색 → 상품',
    1,
    '{"id":"route2-store-search","name":"스토어 → 쇼핑홈 → 검색 → 상품","version":1,"steps":[]}'::jsonb,
    true
) ON CONFLICT (id) DO NOTHING;

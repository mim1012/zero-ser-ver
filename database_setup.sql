-- ============================================================
-- Supabase 테이블 생성 스크립트
-- ============================================================

-- 1. slot_naver 테이블 (상품 슬롯 정보)
CREATE TABLE IF NOT EXISTS slot_naver (
    id BIGSERIAL PRIMARY KEY,
    product_name VARCHAR(500) NOT NULL,
    nv_mid VARCHAR(100) NOT NULL,
    short_keyword VARCHAR(100),
    target_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. traffic_navershopping 테이블 (작업 큐)
CREATE TABLE IF NOT EXISTS traffic_navershopping (
    id BIGSERIAL PRIMARY KEY,
    slot_id BIGINT REFERENCES slot_naver(id),
    status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'claimed', 'completed', 'failed')),
    priority INT DEFAULT 0,
    claimed_by VARCHAR(100),
    claimed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. task_logs 테이블 (작업 로그)
CREATE TABLE IF NOT EXISTS task_logs (
    id BIGSERIAL PRIMARY KEY,
    traffic_id BIGINT REFERENCES traffic_navershopping(id),
    device_id VARCHAR(100) NOT NULL,
    action VARCHAR(50) NOT NULL,
    message TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_traffic_status ON traffic_navershopping(status);
CREATE INDEX IF NOT EXISTS idx_traffic_priority ON traffic_navershopping(priority DESC);
CREATE INDEX IF NOT EXISTS idx_traffic_claimed_by ON traffic_navershopping(claimed_by);
CREATE INDEX IF NOT EXISTS idx_task_logs_traffic_id ON task_logs(traffic_id);
CREATE INDEX IF NOT EXISTS idx_task_logs_device_id ON task_logs(device_id);

-- ============================================================
-- 테스트 데이터 생성
-- ============================================================

-- slot_naver 테이블에 테스트 상품 10개 생성
INSERT INTO slot_naver (product_name, nv_mid, short_keyword, target_url) VALUES
('갤럭시 S24 Ultra 512GB', '12345678', '갤럭시', 'https://msearch.shopping.naver.com/product/12345678'),
('갤럭시 S24 256GB', '12345679', '갤럭시', 'https://msearch.shopping.naver.com/product/12345679'),
('갤럭시 Z Fold5', '12345680', '갤럭시폴드', 'https://msearch.shopping.naver.com/product/12345680'),
('아이폰 15 Pro Max', '12345681', '아이폰', 'https://msearch.shopping.naver.com/product/12345681'),
('아이폰 15 Pro 256GB', '12345682', '아이폰', 'https://msearch.shopping.naver.com/product/12345682'),
('갤럭시 버즈2 Pro', '12345683', '갤럭시버즈', 'https://msearch.shopping.naver.com/product/12345683'),
('에어팟 프로 2세대', '12345684', '에어팟', 'https://msearch.shopping.naver.com/product/12345684'),
('갤럭시 워치6 Classic', '12345685', '갤럭시워치', 'https://msearch.shopping.naver.com/product/12345685'),
('애플워치 시리즈9', '12345686', '애플워치', 'https://msearch.shopping.naver.com/product/12345686'),
('갤럭시 A54 5G', '12345687', '갤럭시', 'https://msearch.shopping.naver.com/product/12345687');

-- traffic_navershopping 테이블에 작업 10개 생성
INSERT INTO traffic_navershopping (slot_id, status, priority)
SELECT id, 'pending', 1 FROM slot_naver ORDER BY id LIMIT 10;

-- ============================================================
-- 확인 쿼리
-- ============================================================

-- 생성된 슬롯 확인
-- SELECT * FROM slot_naver;

-- pending 작업 확인
-- SELECT * FROM traffic_navershopping WHERE status = 'pending';

-- 배치 작업 가져오기 테스트 (10개)
-- SELECT t.*, s.*
-- FROM traffic_navershopping t
-- JOIN slot_naver s ON t.slot_id = s.id
-- WHERE t.status = 'pending'
-- ORDER BY t.id
-- LIMIT 10;

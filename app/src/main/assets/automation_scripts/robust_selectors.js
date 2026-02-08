/**
 * 견고한 셀렉터 전략 - 네이버 쇼핑 모바일
 * 여러 전략을 시도하여 DOM 변경에도 대응
 */

const RobustSelectors = {
    /**
     * 자동완성 항목 찾기 (5가지 전략)
     */
    findAutocompleteItems(keyword) {
        console.log('[RobustSelectors] 자동완성 탐색 시작:', keyword);

        // 전략 1: 기존 셀렉터 (PC/모바일 공통)
        let items = document.querySelectorAll('#sb-ac-recomm-wrap li.u_atcp_l[data-area="top"] a.u_atcp_a');
        if (items.length > 0) {
            console.log('[전략 1] 성공 - 기존 셀렉터:', items.length);
            return Array.from(items);
        }

        // 전략 2: 간소화된 셀렉터
        items = document.querySelectorAll('#sb-ac-recomm-wrap li a');
        if (items.length > 0) {
            console.log('[전략 2] 성공 - 간소화 셀렉터:', items.length);
            return Array.from(items);
        }

        // 전략 3: aria-autocomplete 기반
        const searchInput = document.querySelector('input[aria-autocomplete]');
        if (searchInput) {
            const listboxId = searchInput.getAttribute('aria-controls');
            if (listboxId) {
                items = document.querySelectorAll(`#${listboxId} a`);
                if (items.length > 0) {
                    console.log('[전략 3] 성공 - aria-autocomplete:', items.length);
                    return Array.from(items);
                }
            }
        }

        // 전략 4: 위치 기반 (검색창 아래 200px 이내의 링크)
        const searchBox = document.querySelector('input[type="search"], input[name="query"], input.search_input');
        if (searchBox) {
            const rect = searchBox.getBoundingClientRect();
            const allLinks = document.querySelectorAll('a');
            items = Array.from(allLinks).filter(a => {
                const aRect = a.getBoundingClientRect();
                const isBelow = aRect.top > rect.bottom && aRect.top < rect.bottom + 200;
                const hasText = a.textContent.trim().length > 0;
                return isBelow && hasText;
            });

            if (items.length > 0) {
                console.log('[전략 4] 성공 - 위치 기반:', items.length);
                return items;
            }
        }

        // 전략 5: 텍스트 매칭 (최후의 수단)
        if (keyword) {
            const allLinks = document.querySelectorAll('a');
            items = Array.from(allLinks).filter(a => {
                const text = a.textContent.toLowerCase().trim();
                const kw = keyword.toLowerCase().trim();
                return text.includes(kw) && text.length < 50; // 너무 긴 텍스트 제외
            });

            if (items.length > 0) {
                console.log('[전략 5] 성공 - 텍스트 매칭:', items.length);
                return items.slice(0, 10); // 최대 10개
            }
        }

        console.warn('[RobustSelectors] 자동완성 찾기 실패');
        return [];
    },

    /**
     * 상품 MID 찾기 (5가지 전략)
     */
    findProductByMID(mid) {
        console.log('[RobustSelectors] MID 탐색:', mid);

        // 전략 1: URL 파라미터 (가격비교) - 가장 안정적
        let link = document.querySelector(`a[href*="nv_mid=${mid}"]`);
        if (link && this.isVisible(link)) {
            console.log('[전략 1] MID 발견 - URL 파라미터');
            return { element: link, strategy: 'url_param' };
        }

        // 전략 2: URL 경로 (스마트스토어)
        link = document.querySelector(`a[href*="/products/${mid}"]`);
        if (link && this.isVisible(link)) {
            console.log('[전략 2] MID 발견 - URL 경로');
            return { element: link, strategy: 'url_path' };
        }

        // 전략 3: data-product-id 속성
        link = document.querySelector(`[data-product-id="${mid}"]`);
        if (link && this.isVisible(link)) {
            console.log('[전략 3] MID 발견 - data-product-id');
            return { element: link, strategy: 'data_attr' };
        }

        // 전략 4: ID 속성 + 이전 형제 요소
        const container = document.querySelector(`[id="nstore_productId_${mid}"]`);
        if (container && this.isVisible(container)) {
            const prevLink = container.previousElementSibling;
            if (prevLink && prevLink.tagName === 'A' && this.isVisible(prevLink)) {
                console.log('[전략 4] MID 발견 - ID 속성');
                return { element: prevLink, strategy: 'id_attr' };
            }
        }

        // 전략 5: 전체 링크 순회 (가장 느림)
        const allLinks = document.querySelectorAll('a[href]');
        for (let a of allLinks) {
            if (!this.isVisible(a)) continue;

            const href = a.getAttribute('href');
            if (!href) continue;

            // nv_mid 확인
            if (href.includes(`nv_mid=${mid}`)) {
                console.log('[전략 5] MID 발견 - 전체 순회 (nv_mid)');
                return { element: a, strategy: 'full_scan_param' };
            }

            // products/ 확인
            if (href.includes(`/products/${mid}`)) {
                console.log('[전략 5] MID 발견 - 전체 순회 (products)');
                return { element: a, strategy: 'full_scan_path' };
            }
        }

        console.warn('[RobustSelectors] MID 찾기 실패:', mid);
        return null;
    },

    /**
     * 요소 가시성 확인
     */
    isVisible(element) {
        if (!element) return false;

        const rect = element.getBoundingClientRect();
        return (
            rect.width > 0 &&
            rect.height > 0 &&
            rect.top >= -100 && // 화면 위 100px까지 허용
            rect.bottom <= window.innerHeight + 100 // 화면 아래 100px까지 허용
        );
    },

    /**
     * 현재 페이지의 모든 MID 수집 (디버깅용)
     */
    collectAllMIDs() {
        const mids = new Set();

        // nv_mid 파라미터
        document.querySelectorAll('a[href*="nv_mid="]').forEach(a => {
            const match = a.href.match(/nv_mid=(\d+)/);
            if (match) mids.add(match[1]);
        });

        // products/ 경로
        document.querySelectorAll('a[href*="/products/"]').forEach(a => {
            const match = a.href.match(/\/products\/(\d+)/);
            if (match) mids.add(match[1]);
        });

        // data-product-id
        document.querySelectorAll('[data-product-id]').forEach(el => {
            const id = el.getAttribute('data-product-id');
            if (id && /^\d+$/.test(id)) mids.add(id);
        });

        // nstore_productId_
        document.querySelectorAll('[id^="nstore_productId_"]').forEach(el => {
            const match = el.id.match(/nstore_productId_(\d+)/);
            if (match) mids.add(match[1]);
        });

        return Array.from(mids);
    },

    /**
     * 검색창 찾기 (여러 전략)
     */
    findSearchInput() {
        // 전략 1: type="search"
        let input = document.querySelector('input[type="search"]');
        if (input) return input;

        // 전략 2: name="query"
        input = document.querySelector('input[name="query"]');
        if (input) return input;

        // 전략 3: class 패턴
        input = document.querySelector('input.search_input, input[class*="search"]');
        if (input) return input;

        // 전략 4: placeholder 패턴
        input = document.querySelector('input[placeholder*="검색"], input[placeholder*="Search"]');
        if (input) return input;

        // 전략 5: 첫 번째 input
        return document.querySelector('input');
    },

    /**
     * 검색 버튼 찾기
     */
    findSearchButton() {
        // 전략 1: type="submit"
        let btn = document.querySelector('button[type="submit"]');
        if (btn && this.isVisible(btn)) return btn;

        // 전략 2: class 패턴
        btn = document.querySelector('button.btn_search, button[class*="search"]');
        if (btn && this.isVisible(btn)) return btn;

        // 전략 3: aria-label
        btn = document.querySelector('button[aria-label*="검색"], button[aria-label*="Search"]');
        if (btn && this.isVisible(btn)) return btn;

        // 전략 4: 검색창 근처의 버튼
        const searchInput = this.findSearchInput();
        if (searchInput) {
            const parent = searchInput.closest('form') || searchInput.parentElement;
            btn = parent?.querySelector('button');
            if (btn && this.isVisible(btn)) return btn;
        }

        return null;
    }
};

// 전역으로 노출
window.RobustSelectors = RobustSelectors;

console.log('[RobustSelectors] 로드 완료');

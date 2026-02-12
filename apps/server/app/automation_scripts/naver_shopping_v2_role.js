/**
 * 네이버 쇼핑 자동화 스크립트 v2 (대장봇/쫄병봇 차별화)
 * Android WebView 환경에서 실행되는 네이버 쇼핑 트래픽 자동화
 *
 * 변경사항 (v1 → v2):
 * - Role 기반 설정 (leader vs follower)
 * - 대장봇: 천천히, 정교하게, 길게 체류
 * - 쫄병봇: 빠르게, 무작위로, 짧게 체류
 */

(function() {
  'use strict';

  const AUTOMATION = {
    // ============ 기본 설정 (follower 기준) ============
    config: {
      scrollSpeed: 600,
      clickDelay: [600, 1000],
      dwellTime: [3000, 5000],
      maxScrollAttempts: 8,
      scrollStep: 600,
      typingDelay: [20, 50],
      autocompleteRange: [0, 5]
    },

    // 현재 작업 상태
    currentTask: {
      productName: null,
      mid: null,
      shortKeyword: null,
      trafficId: null,
      role: 'follower'  // 기본값: follower
    },

    /**
     * Role에 따른 설정 가져오기 (대장봇/쫄병봇 차별화)
     * @param {string} role - "leader" 또는 "follower"
     * @returns {object} role에 맞는 설정
     */
    getConfigByRole(role) {
      if (role === 'leader') {
        // 대장봇: 천천히, 정교하게, 길게
        this.log('🎖️ 대장봇 모드 활성화 (Leader Mode)');
        return {
          scrollSpeed: 1000,              // 느리게 스크롤 (1000ms)
          clickDelay: [1000, 1500],       // 클릭 간격 길게
          dwellTime: [5000, 8000],        // 체류 시간 길게 (5~8초)
          maxScrollAttempts: 12,          // 스크롤 많이 (최대 12번)
          scrollStep: 400,                // 한 번에 적게 스크롤
          typingDelay: [40, 80],          // 타이핑 느리게
          autocompleteRange: [1, 2]       // 자동완성 2~3번째 선택
        };
      } else {
        // 쫄병봇: 빠르게, 무작위로, 짧게
        this.log('⚡ 쫄병봇 모드 활성화 (Follower Mode)');
        return {
          scrollSpeed: 600,               // 빠르게 스크롤 (600ms)
          clickDelay: [600, 1000],        // 클릭 간격 짧게
          dwellTime: [3000, 5000],        // 체류 시간 짧게 (3~5초)
          maxScrollAttempts: 8,           // 스크롤 적게 (최대 8번)
          scrollStep: 600,                // 한 번에 많이 스크롤
          typingDelay: [20, 50],          // 타이핑 빠르게
          autocompleteRange: [0, 5]       // 자동완성 무작위 선택
        };
      }
    },

    // ============ 유틸리티 함수 ============

    randomBetween(min, max) {
      return Math.floor(Math.random() * (max - min + 1)) + min;
    },

    sleep(ms) {
      return new Promise(resolve => setTimeout(resolve, ms));
    },

    log(message, level = 'info') {
      const timestamp = new Date().toISOString();
      const roleTag = this.currentTask.role === 'leader' ? '[대장]' : '[쫄병]';
      const logMessage = `[${timestamp}] ${roleTag} ${message}`;

      console.log(logMessage);

      if (window.AndroidInterface && window.AndroidInterface.reportProgress) {
        try {
          window.AndroidInterface.reportProgress('log', JSON.stringify({
            message: message,
            level: level,
            role: this.currentTask.role,
            timestamp: timestamp
          }));
        } catch (e) {
          console.error('Failed to send log to Android:', e);
        }
      }
    },

    reportProgress(status, data = {}) {
      this.log(`Status: ${status} - ${JSON.stringify(data)}`);

      if (window.AndroidInterface && window.AndroidInterface.reportProgress) {
        try {
          window.AndroidInterface.reportProgress(status, JSON.stringify({
            ...data,
            role: this.currentTask.role,
            timestamp: Date.now()
          }));
        } catch (e) {
          console.error('Failed to report progress:', e);
        }
      }
    },

    reportError(errorMessage, errorData = {}) {
      this.log(`ERROR: ${errorMessage}`, 'error');

      if (window.AndroidInterface && window.AndroidInterface.reportError) {
        try {
          window.AndroidInterface.reportError(JSON.stringify({
            error: errorMessage,
            ...errorData,
            role: this.currentTask.role,
            timestamp: Date.now()
          }));
        } catch (e) {
          console.error('Failed to report error:', e);
        }
      }
    },

    reportComplete(metadata = {}) {
      this.log(`Task completed: ${JSON.stringify(metadata)}`);

      if (window.AndroidInterface && window.AndroidInterface.reportComplete) {
        try {
          window.AndroidInterface.reportComplete(JSON.stringify({
            ...metadata,
            role: this.currentTask.role,
            timestamp: Date.now()
          }));
        } catch (e) {
          console.error('Failed to report completion:', e);
        }
      }
    },

    // ============ 3차 베지어 곡선 스크롤 ============

    cubicBezier(t, p0, p1, p2, p3) {
      const t2 = t * t;
      const t3 = t2 * t;
      const mt = 1 - t;
      const mt2 = mt * mt;
      const mt3 = mt2 * mt;

      return mt3 * p0 + 3 * mt2 * t * p1 + 3 * mt * t2 * p2 + t3 * p3;
    },

    async bezierScroll(targetY) {
      const startY = window.scrollY;
      const distance = Math.abs(targetY - startY);

      const steps = Math.min(40, Math.max(20, Math.floor(distance / 20)));
      const duration = this.randomBetween(800, 1500);
      const startTime = Date.now();

      const curvature = Math.min(distance * 0.3, 100);
      const cp1y = startY + (targetY - startY) * 0.1 + (Math.random() - 0.5) * curvature;
      const cp2y = startY + (targetY - startY) * 0.9 + (Math.random() - 0.5) * curvature;

      return new Promise((resolve) => {
        const animate = () => {
          const elapsed = Date.now() - startTime;
          let t = Math.min(elapsed / duration, 1);

          t = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;

          const currentY = this.cubicBezier(t, startY, cp1y, cp2y, targetY);

          const noise = (Math.random() - 0.5) * 2;
          window.scrollTo(0, currentY + noise);

          if (t < 1) {
            requestAnimationFrame(animate);
          } else {
            window.scrollTo(0, targetY);
            resolve();
          }
        };

        requestAnimationFrame(animate);
      });
    },

    // ============ 인간화 타이핑 ============

    async humanType(element, text) {
      if (!element) {
        throw new Error('Input element not found');
      }

      element.value = '';
      element.focus();

      // Role에 따른 타이핑 속도
      const typingDelay = this.config.typingDelay;

      for (const char of text) {
        element.value += char;

        const inputEvent = new Event('input', { bubbles: true, cancelable: true });
        element.dispatchEvent(inputEvent);

        const changeEvent = new Event('change', { bubbles: true, cancelable: true });
        element.dispatchEvent(changeEvent);

        await this.sleep(this.randomBetween(...typingDelay));
      }

      this.log(`Typed: "${text}" (속도: ${typingDelay[0]}-${typingDelay[1]}ms)`);
    },

    // ============ MID 탐색 및 클릭 (3가지 전략) ============

    async findAndClickMID(mid) {
      this.log(`MID 탐색 시작: ${mid} (최대 ${this.config.maxScrollAttempts}번 스크롤)`);
      const maxScroll = this.config.maxScrollAttempts;

      for (let i = 0; i < maxScroll; i++) {
        if (i === 0) {
          this.log(`스크롤 ${i+1}/${maxScroll} - 3가지 전략으로 MID 탐색`);
        }

        // 전략 1: 가격비교 - URL 파라미터
        let link = document.querySelector(`a[href*="nv_mid=${mid}"]`);

        if (link && this.isElementVisible(link)) {
          this.log(`MID 발견 (전략 1: 가격비교 URL 파라미터)`);
          link.click();
          this.reportProgress('mid_clicked', {
            strategy: 'url_param',
            mid: mid,
            scroll_count: i + 1
          });
          return true;
        }

        // 전략 2: 플러스스토어 - URL 경로
        link = document.querySelector(`a[href*="/products/${mid}"]`);

        if (link && this.isElementVisible(link)) {
          this.log(`MID 발견 (전략 2: 플러스스토어 URL 경로)`);
          link.click();
          this.reportProgress('mid_clicked', {
            strategy: 'url_path',
            mid: mid,
            scroll_count: i + 1
          });
          return true;
        }

        // 전략 3: 플러스스토어 - ID 속성 (폴백)
        const container = document.querySelector(`[id="nstore_productId_${mid}"]`);

        if (container && this.isElementVisible(container)) {
          this.log(`MID 발견 (전략 3: 플러스스토어 ID 속성)`);

          const prevLink = container.previousElementSibling;

          if (prevLink && prevLink.tagName === 'A' && this.isElementVisible(prevLink)) {
            prevLink.click();
            this.reportProgress('mid_clicked', {
              strategy: 'id_attr',
              mid: mid,
              scroll_count: i + 1
            });
            return true;
          }
        }

        // 스크롤 (role에 따른 속도)
        const currentY = window.scrollY;
        await this.bezierScroll(currentY + this.config.scrollStep);
        await this.sleep(this.randomBetween(300, 500));

        // 스크롤 끝 감지
        const reachedBottom = (window.scrollY + window.innerHeight) >= (document.body.scrollHeight - 100);

        if (reachedBottom && i > 3) {
          this.log(`스크롤 끝 도달 (${i+1}번 시도)`, 'warn');
          break;
        }
      }

      const foundMids = this.collectVisibleMids();
      this.log(`MID 찾기 실패. 발견된 MID (최대 10개): ${foundMids.join(', ')}`, 'warn');

      return false;
    },

    isElementVisible(element) {
      if (!element) return false;

      const rect = element.getBoundingClientRect();
      return (
        rect.top >= 0 &&
        rect.left >= 0 &&
        rect.bottom <= (window.innerHeight || document.documentElement.clientHeight) &&
        rect.right <= (window.innerWidth || document.documentElement.clientWidth) &&
        rect.width > 0 &&
        rect.height > 0
      );
    },

    collectVisibleMids() {
      const mids = [];

      document.querySelectorAll('a[href*="nv_mid="]').forEach(a => {
        const match = a.getAttribute('href').match(/nv_mid=(\d+)/);
        if (match && mids.length < 10) {
          mids.push(match[1]);
        }
      });

      document.querySelectorAll('a[href*="/products/"]').forEach(a => {
        const match = a.getAttribute('href').match(/\/products\/(\d+)/);
        if (match && mids.length < 10 && !mids.includes(match[1])) {
          mids.push(match[1]);
        }
      });

      return mids;
    },

    // ============ 메인 자동화 로직 ============

    /**
     * 네이버 쇼핑 자동화 실행
     *
     * @param {string} productName - 상품명 (전체)
     * @param {string} mid - 네이버 상품 ID
     * @param {string} shortKeyword - 짧은 키워드 (자동완성용, optional)
     * @param {string} role - "leader" 또는 "follower" (기본값: follower)
     */
    async run(productName, mid, shortKeyword, role = 'follower') {
      try {
        this.currentTask.productName = productName;
        this.currentTask.mid = mid;
        this.currentTask.shortKeyword = shortKeyword;
        this.currentTask.role = role;

        // Role에 따른 설정 적용
        this.config = this.getConfigByRole(role);

        this.reportProgress('started', {
          productName: productName,
          mid: mid,
          shortKeyword: shortKeyword || 'auto',
          role: role
        });

        // ============ 1. 대기 ============
        this.log('Step 1: 초기 대기...');
        await this.sleep(this.randomBetween(1500, 2500));

        // ============ 2. 검색창 클릭 ============
        this.log('Step 2: 검색창 클릭...');
        window.scrollTo(0, 0);

        const searchFakeBox = document.querySelector('#MM_SEARCH_FAKE');
        if (!searchFakeBox) {
          throw new Error('검색창을 찾을 수 없습니다 (#MM_SEARCH_FAKE)');
        }

        searchFakeBox.click();
        await this.sleep(this.randomBetween(...this.config.clickDelay));

        // ============ 3. 짧은 키워드 입력 ============
        const searchKeyword = shortKeyword || productName.split(' ')[0].substring(0, 10);
        this.log(`Step 3: "${searchKeyword}" 입력... (role: ${role})`);

        const inputField = document.querySelector('#query.sch_input');
        if (!inputField) {
          throw new Error('검색 입력창을 찾을 수 없습니다 (#query.sch_input)');
        }

        await this.humanType(inputField, searchKeyword);
        await this.sleep(this.randomBetween(1500, 2500));

        // ============ 4. 자동완성 항목 클릭 (Role에 따라 다르게) ============
        this.log('Step 4: 자동완성 선택...');

        const autocompleteSelector = '#sb-ac-recomm-wrap li.u_atcp_l[data-area="top"] a.u_atcp_a';
        const autocompleteItems = document.querySelectorAll(autocompleteSelector);

        let autocompleteClicked = false;

        if (autocompleteItems.length > 1) {
          // Role에 따른 선택 범위
          const [minIdx, maxIdx] = this.config.autocompleteRange;
          const validMax = Math.min(maxIdx, autocompleteItems.length - 1);
          const validMin = Math.min(minIdx, validMax);

          const randomIndex = this.randomBetween(validMin, validMax);
          const selectedItem = autocompleteItems[randomIndex];
          const keywordText = selectedItem.textContent.trim();

          this.log(`자동완성 ${autocompleteItems.length}개 중 ${randomIndex+1}번째 선택: "${keywordText}" (range: ${minIdx}~${maxIdx})`);
          selectedItem.click();
          autocompleteClicked = true;

        } else if (autocompleteItems.length === 1) {
          this.log('자동완성 1개 선택');
          autocompleteItems[0].click();
          autocompleteClicked = true;

        } else {
          this.log('자동완성 없음, 검색 버튼 클릭', 'warn');

          const searchBtn = document.querySelector('button[type="submit"], .btn_search, [class*="search_btn"]');
          if (searchBtn) {
            searchBtn.click();
            autocompleteClicked = true;
          } else {
            const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13 });
            inputField.dispatchEvent(enterEvent);
            autocompleteClicked = true;
          }
        }

        if (!autocompleteClicked) {
          throw new Error('자동완성 클릭 실패');
        }

        await this.sleep(this.randomBetween(2000, 3000));

        // ============ 5. ackey 추출 및 상품명으로 재검색 ============
        this.log('Step 5: ackey 추출 및 재검색...');

        const url = new URL(window.location.href);
        const ackey = url.searchParams.get('ackey');
        const sm = url.searchParams.get('sm');

        this.log(`ackey=${ackey}, sm=${sm}`);
        this.reportProgress('ackey_extracted', { ackey: ackey, sm: sm });

        url.searchParams.set('query', productName);

        this.log(`상품명으로 재검색: "${productName}"`);
        window.location.href = url.toString();

        await this.sleep(3000);

        // ============ 6. CAPTCHA 감지 ============
        this.log('Step 6: CAPTCHA 감지...');

        const bodyText = document.body.innerText || '';
        const captchaDetected = bodyText.includes('보안 확인') || bodyText.includes('자동입력방지');

        if (captchaDetected) {
          this.log('CAPTCHA 감지됨!', 'error');
          throw new Error('CAPTCHA_DETECTED');
        }

        const ipBlocked = bodyText.includes('비정상적인 접근') ||
                         bodyText.includes('자동화된 접근') ||
                         bodyText.includes('접근이 제한') ||
                         bodyText.includes('잠시 후 다시') ||
                         bodyText.includes('비정상적인 요청') ||
                         bodyText.includes('이용이 제한');

        if (ipBlocked) {
          this.log('IP 차단 감지됨!', 'error');
          throw new Error('IP_BLOCKED');
        }

        // ============ 7. MID 탐색 및 클릭 ============
        this.log('Step 7: MID 탐색 및 클릭...');

        const midClicked = await this.findAndClickMID(mid);

        if (!midClicked) {
          throw new Error('NO_MID_MATCH');
        }

        await this.sleep(2000);

        // ============ 8. 상세페이지 확인 및 체류 ============
        this.log('Step 8: 상세페이지 체류...');

        const currentPageUrl = window.location.href;
        const isProductPage = currentPageUrl.includes('smartstore.naver.com') ||
                             currentPageUrl.includes('brand.naver.com');

        this.log(`현재 페이지: ${currentPageUrl.substring(0, 60)}...`);

        // Role에 따른 체류 시간
        const dwellTime = this.randomBetween(...this.config.dwellTime);
        this.log(`상세페이지 체류 ${(dwellTime / 1000).toFixed(1)}초... (${role})`);

        this.reportProgress('dwelling', {
          dwell_time_ms: dwellTime,
          product_page_entered: isProductPage,
          role: role
        });

        await this.sleep(dwellTime);

        // ============ 9. 완료 보고 ============
        this.reportComplete({
          success: true,
          product_page_entered: isProductPage,
          dwell_time_ms: dwellTime,
          final_url: currentPageUrl,
          mid: mid,
          product_name: productName,
          role: role,
          config_used: {
            dwell_time_range: this.config.dwellTime,
            scroll_attempts: this.config.maxScrollAttempts,
            autocomplete_range: this.config.autocompleteRange
          }
        });

        this.log('자동화 완료!');

      } catch (error) {
        this.log(`자동화 실패: ${error.message}`, 'error');

        this.reportError(error.message, {
          product_name: productName,
          mid: mid,
          short_keyword: shortKeyword,
          role: role,
          current_url: window.location.href,
          error_stack: error.stack
        });
      }
    }
  };

  // ============ 전역으로 노출 ============
  window.NaverShoppingAutomation = AUTOMATION;

  console.log('[NaverShoppingAutomation] v2 (Role-based) loaded successfully');
  console.log('[NaverShoppingAutomation] Available methods: run(productName, mid, shortKeyword, role)');
  console.log('[NaverShoppingAutomation] Roles: leader (대장봇), follower (쫄병봇)');

  if (window.AndroidInterface) {
    console.log('[NaverShoppingAutomation] Android bridge detected');
  } else {
    console.log('[NaverShoppingAutomation] Running in standalone mode (no Android bridge)');
  }

})();

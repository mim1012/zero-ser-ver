/**
 * 네이버 쇼핑 자동화 스크립트 v1
 * Android WebView 환경에서 실행되는 네이버 쇼핑 트래픽 자동화
 *
 * 원본: D:/Project/turafic_update/unified-runner.ts
 * 변환: Playwright/Patchright → WebView JavaScript
 */

(function() {
  'use strict';

  const AUTOMATION = {
    // ============ 설정 ============
    config: {
      scrollSpeed: 800,
      clickDelay: [800, 1200],
      dwellTime: [3000, 6000],
      maxScrollAttempts: 15,
      scrollStep: 500,
      typingDelay: [30, 60]
    },

    // 현재 작업 상태
    currentTask: {
      productName: null,
      mid: null,
      shortKeyword: null,
      trafficId: null
    },

    // ============ 유틸리티 함수 ============

    /**
     * 랜덤 숫자 생성 (min, max 포함)
     */
    randomBetween(min, max) {
      return Math.floor(Math.random() * (max - min + 1)) + min;
    },

    /**
     * Sleep 함수
     */
    sleep(ms) {
      return new Promise(resolve => setTimeout(resolve, ms));
    },

    /**
     * 로그 출력 (Android 브릿지 또는 콘솔)
     */
    log(message, level = 'info') {
      const timestamp = new Date().toISOString();
      const logMessage = `[${timestamp}] [NaverAutomation] ${message}`;

      console.log(logMessage);

      // Android 브릿지로 로그 전송
      if (window.AndroidInterface && window.AndroidInterface.reportProgress) {
        try {
          window.AndroidInterface.reportProgress('log', JSON.stringify({
            message: message,
            level: level,
            timestamp: timestamp
          }));
        } catch (e) {
          console.error('Failed to send log to Android:', e);
        }
      }
    },

    /**
     * Android 브릿지에 진행 상태 보고
     */
    reportProgress(status, data = {}) {
      this.log(`Status: ${status} - ${JSON.stringify(data)}`);

      if (window.AndroidInterface && window.AndroidInterface.reportProgress) {
        try {
          window.AndroidInterface.reportProgress(status, JSON.stringify({
            ...data,
            timestamp: Date.now()
          }));
        } catch (e) {
          console.error('Failed to report progress:', e);
        }
      }
    },

    /**
     * Android 브릿지에 에러 보고
     */
    reportError(errorMessage, errorData = {}) {
      this.log(`ERROR: ${errorMessage}`, 'error');

      if (window.AndroidInterface && window.AndroidInterface.reportError) {
        try {
          window.AndroidInterface.reportError(JSON.stringify({
            error: errorMessage,
            ...errorData,
            timestamp: Date.now()
          }));
        } catch (e) {
          console.error('Failed to report error:', e);
        }
      }
    },

    /**
     * Android 브릿지에 완료 보고
     */
    reportComplete(metadata = {}) {
      this.log(`Task completed: ${JSON.stringify(metadata)}`);

      if (window.AndroidInterface && window.AndroidInterface.reportComplete) {
        try {
          window.AndroidInterface.reportComplete(JSON.stringify({
            ...metadata,
            timestamp: Date.now()
          }));
        } catch (e) {
          console.error('Failed to report completion:', e);
        }
      }
    },

    // ============ 3차 베지어 곡선 스크롤 ============
    // unified-runner.ts의 bezierMouseMove를 스크롤로 변환

    /**
     * 3차 베지어 곡선 계산
     */
    cubicBezier(t, p0, p1, p2, p3) {
      const t2 = t * t;
      const t3 = t2 * t;
      const mt = 1 - t;
      const mt2 = mt * mt;
      const mt3 = mt2 * mt;

      return mt3 * p0 + 3 * mt2 * t * p1 + 3 * mt * t2 * p2 + t3 * p3;
    },

    /**
     * 베지어 스크롤 (부드럽고 자연스러운 스크롤)
     * @param {number} targetY - 목표 Y 좌표 (절대 위치)
     */
    async bezierScroll(targetY) {
      const startY = window.scrollY;
      const distance = Math.abs(targetY - startY);

      // 거리에 따라 스텝 수 조정 (20~40 스텝)
      const steps = Math.min(40, Math.max(20, Math.floor(distance / 20)));
      const duration = this.randomBetween(800, 1500);
      const startTime = Date.now();

      // 베지어 곡선 제어점 생성 (랜덤 요소 추가로 사람처럼)
      const curvature = Math.min(distance * 0.3, 100);
      const cp1y = startY + (targetY - startY) * 0.1 + (Math.random() - 0.5) * curvature;
      const cp2y = startY + (targetY - startY) * 0.9 + (Math.random() - 0.5) * curvature;

      return new Promise((resolve) => {
        const animate = () => {
          const elapsed = Date.now() - startTime;
          let t = Math.min(elapsed / duration, 1);

          // Ease-in-out 곡선 적용
          t = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;

          // 3차 베지어 곡선으로 현재 Y 좌표 계산
          const currentY = this.cubicBezier(t, startY, cp1y, cp2y, targetY);

          // 랜덤 노이즈 추가 (사람처럼 완벽하지 않은 스크롤)
          const noise = (Math.random() - 0.5) * 2;
          window.scrollTo(0, currentY + noise);

          if (t < 1) {
            requestAnimationFrame(animate);
          } else {
            // 최종 위치로 정확히 이동
            window.scrollTo(0, targetY);
            resolve();
          }
        };

        requestAnimationFrame(animate);
      });
    },

    // ============ 인간화 타이핑 ============

    /**
     * 사람처럼 한 글자씩 입력
     * @param {HTMLInputElement} element - 입력 필드
     * @param {string} text - 입력할 텍스트
     */
    async humanType(element, text) {
      if (!element) {
        throw new Error('Input element not found');
      }

      // 기존 값 초기화
      element.value = '';
      element.focus();

      for (const char of text) {
        element.value += char;

        // input 이벤트 발생 (자동완성 트리거)
        const inputEvent = new Event('input', { bubbles: true, cancelable: true });
        element.dispatchEvent(inputEvent);

        // change 이벤트도 발생
        const changeEvent = new Event('change', { bubbles: true, cancelable: true });
        element.dispatchEvent(changeEvent);

        // 랜덤 딜레이 (30~60ms)
        await this.sleep(this.randomBetween(...this.config.typingDelay));
      }

      this.log(`Typed: "${text}"`);
    },

    // ============ MID 탐색 및 클릭 (3가지 전략) ============

    /**
     * 네이버 쇼핑 MID를 찾아서 클릭
     * unified-runner.ts의 3가지 전략 구현
     *
     * @param {string} mid - 네이버 상품 ID
     * @returns {Promise<boolean>} - 클릭 성공 여부
     */
    async findAndClickMID(mid) {
      this.log(`MID 탐색 시작: ${mid}`);
      const maxScroll = this.config.maxScrollAttempts;

      for (let i = 0; i < maxScroll; i++) {
        if (i === 0) {
          this.log(`스크롤 ${i+1}/${maxScroll} - 3가지 전략으로 MID 탐색`);
        }

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 전략 1: 가격비교 - URL 파라미터
        // 예: https://cr3.shopping.naver.com/...?nv_mid=90379584423
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 전략 2: 플러스스토어 - URL 경로
        // 예: https://smartstore.naver.com/main/products/9211038096
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 전략 3: 플러스스토어 - ID 속성 (폴백)
        // 예: id="nstore_productId_9211038096"
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        const container = document.querySelector(`[id="nstore_productId_${mid}"]`);

        if (container && this.isElementVisible(container)) {
          this.log(`MID 발견 (전략 3: 플러스스토어 ID 속성)`);

          // 이전 형제 요소 중 <a> 태그 찾기
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

        // 스크롤 (베지어 곡선으로 부드럽게)
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

      // MID를 찾지 못함 - 디버깅 정보 수집
      const foundMids = this.collectVisibleMids();
      this.log(`MID 찾기 실패. 발견된 MID (최대 10개): ${foundMids.join(', ')}`, 'warn');

      return false;
    },

    /**
     * 요소가 화면에 보이는지 확인
     */
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

    /**
     * 현재 페이지에서 보이는 MID 수집 (디버깅용)
     */
    collectVisibleMids() {
      const mids = [];

      // 가격비교 URL
      document.querySelectorAll('a[href*="nv_mid="]').forEach(a => {
        const match = a.getAttribute('href').match(/nv_mid=(\d+)/);
        if (match && mids.length < 10) {
          mids.push(match[1]);
        }
      });

      // 플러스스토어 URL
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
     * unified-runner.ts의 runPatchrightEngine 변환
     *
     * @param {string} productName - 상품명 (전체)
     * @param {string} mid - 네이버 상품 ID
     * @param {string} shortKeyword - 짧은 키워드 (자동완성용, optional)
     */
    async run(productName, mid, shortKeyword) {
      try {
        this.currentTask.productName = productName;
        this.currentTask.mid = mid;
        this.currentTask.shortKeyword = shortKeyword;

        this.reportProgress('started', {
          productName: productName,
          mid: mid,
          shortKeyword: shortKeyword || 'auto'
        });

        // ============ 1. 대기 (m.naver.com이 이미 로드됨) ============
        this.log('Step 1: 초기 대기...');
        await this.sleep(this.randomBetween(1500, 2500));

        // ============ 2. 검색창 클릭 ============
        this.log('Step 2: 검색창 클릭...');
        window.scrollTo(0, 0); // 페이지 맨 위로

        const searchFakeBox = document.querySelector('#MM_SEARCH_FAKE');
        if (!searchFakeBox) {
          throw new Error('검색창을 찾을 수 없습니다 (#MM_SEARCH_FAKE)');
        }

        searchFakeBox.click();
        await this.sleep(this.randomBetween(800, 1200));

        // ============ 3. 짧은 키워드 입력 ============
        // shortKeyword가 없으면 상품명의 첫 단어 사용
        const searchKeyword = shortKeyword || productName.split(' ')[0].substring(0, 10);
        this.log(`Step 3: "${searchKeyword}" 입력...`);

        const inputField = document.querySelector('#query.sch_input');
        if (!inputField) {
          throw new Error('검색 입력창을 찾을 수 없습니다 (#query.sch_input)');
        }

        await this.humanType(inputField, searchKeyword);
        await this.sleep(this.randomBetween(1500, 2500));

        // ============ 4. 자동완성 항목 랜덤 클릭 ============
        this.log('Step 4: 자동완성 선택...');

        const autocompleteSelector = '#sb-ac-recomm-wrap li.u_atcp_l[data-area="top"] a.u_atcp_a';
        const autocompleteItems = document.querySelectorAll(autocompleteSelector);

        let autocompleteClicked = false;

        if (autocompleteItems.length > 1) {
          // 첫 번째 항목을 제외하고 랜덤 선택
          const randomIndex = this.randomBetween(1, autocompleteItems.length - 1);
          const selectedItem = autocompleteItems[randomIndex];
          const keywordText = selectedItem.textContent.trim();

          this.log(`자동완성 ${autocompleteItems.length}개 중 ${randomIndex+1}번째 선택: "${keywordText}"`);
          selectedItem.click();
          autocompleteClicked = true;

        } else if (autocompleteItems.length === 1) {
          this.log('자동완성 1개 선택');
          autocompleteItems[0].click();
          autocompleteClicked = true;

        } else {
          // 자동완성이 없으면 검색 버튼 클릭 또는 Enter
          this.log('자동완성 없음, 검색 버튼 클릭', 'warn');

          const searchBtn = document.querySelector('button[type="submit"], .btn_search, [class*="search_btn"]');
          if (searchBtn) {
            searchBtn.click();
            autocompleteClicked = true;
          } else {
            // 폴백: Enter 키 (하지만 WebView에서는 동작 안 할 수 있음)
            const enterEvent = new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13 });
            inputField.dispatchEvent(enterEvent);
            autocompleteClicked = true;
          }
        }

        if (!autocompleteClicked) {
          throw new Error('자동완성 클릭 실패');
        }

        // 페이지 로딩 대기
        await this.sleep(this.randomBetween(2000, 3000));

        // ============ 5. ackey 추출 및 상품명으로 재검색 ============
        this.log('Step 5: ackey 추출 및 재검색...');

        const url = new URL(window.location.href);
        const ackey = url.searchParams.get('ackey');
        const sm = url.searchParams.get('sm');

        this.log(`ackey=${ackey}, sm=${sm}`);
        this.reportProgress('ackey_extracted', { ackey: ackey, sm: sm });

        // query를 상품명으로 변경하여 재검색
        url.searchParams.set('query', productName);

        this.log(`상품명으로 재검색: "${productName}"`);
        window.location.href = url.toString();

        // 페이지 로딩 대기 (새 페이지 로드)
        await this.sleep(3000);

        // ============ 6. CAPTCHA 감지 ============
        this.log('Step 6: CAPTCHA 감지...');

        const bodyText = document.body.innerText || '';
        const captchaDetected = bodyText.includes('보안 확인') || bodyText.includes('자동입력방지');

        if (captchaDetected) {
          this.log('CAPTCHA 감지됨!', 'error');
          throw new Error('CAPTCHA_DETECTED');
        }

        // IP 차단 감지
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

        // 페이지 로딩 대기
        await this.sleep(2000);

        // ============ 8. 상세페이지 확인 및 체류 ============
        this.log('Step 8: 상세페이지 체류...');

        const currentPageUrl = window.location.href;
        const isProductPage = currentPageUrl.includes('smartstore.naver.com') ||
                             currentPageUrl.includes('brand.naver.com');

        this.log(`현재 페이지: ${currentPageUrl.substring(0, 60)}...`);

        // 체류 시간 (3~6초)
        const dwellTime = this.randomBetween(...this.config.dwellTime);
        this.log(`상세페이지 체류 ${(dwellTime / 1000).toFixed(1)}초...`);

        this.reportProgress('dwelling', {
          dwell_time_ms: dwellTime,
          product_page_entered: isProductPage
        });

        await this.sleep(dwellTime);

        // ============ 9. 완료 보고 ============
        this.reportComplete({
          success: true,
          product_page_entered: isProductPage,
          dwell_time_ms: dwellTime,
          final_url: currentPageUrl,
          mid: mid,
          product_name: productName
        });

        this.log('자동화 완료!');

      } catch (error) {
        this.log(`자동화 실패: ${error.message}`, 'error');

        this.reportError(error.message, {
          product_name: productName,
          mid: mid,
          short_keyword: shortKeyword,
          current_url: window.location.href,
          error_stack: error.stack
        });
      }
    }
  };

  // ============ 전역으로 노출 ============
  window.NaverShoppingAutomation = AUTOMATION;

  // 초기화 로그
  console.log('[NaverShoppingAutomation] v1 loaded successfully');
  console.log('[NaverShoppingAutomation] Available methods: run(productName, mid, shortKeyword)');

  // Android 브릿지 확인
  if (window.AndroidInterface) {
    console.log('[NaverShoppingAutomation] Android bridge detected');
  } else {
    console.log('[NaverShoppingAutomation] Running in standalone mode (no Android bridge)');
  }

})();

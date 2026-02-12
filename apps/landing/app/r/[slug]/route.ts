import { getServiceSupabase } from '@/lib/supabase';
import { redirect } from 'next/navigation';

const NAVER_SEARCH_BASE = 'https://m.search.naver.com/search.naver';

// 꼬리 키워드 (쿼리 다양화용)
const TAIL_KEYWORDS = ['추천', '할인', '후기', '가격비교', '인기', '베스트', '구매', '쇼핑', '특가', '세일'];

export async function GET(
  request: Request,
  { params }: { params: Promise<{ slug: string }> }
) {
  const { slug } = await params;
  const supabase = getServiceSupabase();

  // Supabase에서 slug에 해당하는 리디렉트 정보 조회
  const { data: redirectData, error } = await supabase
    .from('landing_redirects')
    .select('*')
    .eq('slug', slug)
    .eq('active', true)
    .single();

  // 리디렉트 정보가 없으면 404
  if (error || !redirectData) {
    return new Response('Not Found', { status: 404 });
  }

  // 조회수 증가 (비동기로 처리, 리디렉트 속도에 영향 없음)
  supabase
    .from('landing_redirects')
    .update({ redirect_count: (redirectData.redirect_count || 0) + 1 })
    .eq('id', redirectData.id)
    .then(() => {});

  // 랜덤 키워드 선택
  const keyword = pickRandomKeyword(redirectData);
  const productName = (redirectData.product_name as string) || '';

  // 쿼리 다양화 후 네이버 검색 URL 생성
  const query = generateQuery(keyword, productName);
  const targetUrl = buildSearchUrl(query);
  redirect(targetUrl);
}

// ============ 키워드 선택 ============

/**
 * 키워드 풀에서 랜덤 키워드 선택
 * 우선순위: keywords(JSON 배열) > keyword(쉼표 구분) > keyword(단일)
 */
function pickRandomKeyword(data: Record<string, unknown>): string {
  // 1) keywords 배열 컬럼 (jsonb / text[])
  if (Array.isArray(data.keywords) && data.keywords.length > 0) {
    return data.keywords[Math.floor(Math.random() * data.keywords.length)];
  }

  // 2) keyword 필드 쉼표 구분 ("키워드1, 키워드2, 키워드3")
  const kw = data.keyword as string | undefined;
  if (kw && kw.includes(',')) {
    const list = kw.split(',').map((k) => k.trim()).filter(Boolean);
    if (list.length > 0) {
      return list[Math.floor(Math.random() * list.length)];
    }
  }

  // 3) 단일 keyword
  return kw || '';
}

// ============ 쿼리 다양화 (unified-runner 방식) ============

/**
 * 합성어 분리 (띄어쓰기 없는 단어 → 2~3글자씩 분리)
 * "아기상어장난감" → ["아기", "상어", "장난감"]
 */
function splitCompoundWord(word: string): string[] {
  if (word.length <= 3) return [word];

  const syllables: string[] = [];
  let i = 0;

  while (i < word.length) {
    const chunkSize = Math.random() > 0.5 ? 2 : 3;
    const chunk = word.substring(i, Math.min(i + chunkSize, word.length));

    if (chunk.length >= 2) {
      syllables.push(chunk);
    } else if (chunk.length === 1 && syllables.length > 0) {
      syllables[syllables.length - 1] += chunk;
    } else {
      syllables.push(chunk);
    }

    i += chunkSize;
  }

  return syllables;
}

/**
 * 쿼리 생성 (합성어 분리 + 꼬리 키워드 + 단어 조합)
 * keyword + productName에서 다양한 검색어 생성
 */
function generateQuery(keyword: string, productName: string): string {
  // product_name이 있으면 pickQueryWords 방식 (3단어 조합)
  if (productName) {
    return pickQueryWords(keyword, productName);
  }

  // keyword만 있는 경우 → 합성어 분리 + 꼬리 키워드
  let baseQuery = keyword;

  // 띄어쓰기 없는 긴 키워드 → 합성어 분리
  if (!keyword.includes(' ') && keyword.length > 3) {
    const split = splitCompoundWord(keyword);
    const rand = Math.random();

    if (rand < 0.4) {
      baseQuery = keyword;            // 원본 그대로
    } else if (rand < 0.7) {
      baseQuery = split.join(' ');     // 전부 띄어쓰기
    } else {
      // 부분 띄어쓰기
      const parts = [...split];
      const insertIdx = Math.floor(Math.random() * (parts.length - 1));
      baseQuery = parts.slice(0, insertIdx + 1).join(' ') + parts.slice(insertIdx + 1).join('');
    }
  }

  // 50% 확률로 꼬리 키워드 추가
  if (Math.random() < 0.5) {
    const tail = TAIL_KEYWORDS[Math.floor(Math.random() * TAIL_KEYWORDS.length)];
    baseQuery = `${baseQuery} ${tail}`;
  }

  return baseQuery;
}

/**
 * keyword + productName에서 3단어 랜덤 조합
 * (unified-runner의 pickQueryWords 동일 로직)
 */
function pickQueryWords(keyword: string, productName: string): string {
  const allText = `${keyword} ${productName}`
    .replace(/[\[\](){}]/g, ' ')
    .replace(/[^\w\sㄱ-ㅎㅏ-ㅣ가-힣]/g, ' ');
  const allWords = [...new Set(allText.split(/\s+/).filter(w => w.length >= 2))];

  // 합성어 분리된 단어도 풀에 추가
  const expandedPool: string[] = [...allWords];
  for (const w of allWords) {
    if (w.length > 3) {
      expandedPool.push(...splitCompoundWord(w));
    }
  }
  const uniquePool = [...new Set(expandedPool.filter(w => w.length >= 2))];

  // 셔플
  for (let i = uniquePool.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [uniquePool[i], uniquePool[j]] = [uniquePool[j], uniquePool[i]];
  }

  // 3단어 선택
  const selected: string[] = [];
  for (const w of uniquePool) {
    if (selected.length >= 3) break;
    if (!selected.includes(w)) selected.push(w);
  }

  // 부족하면 꼬리 키워드로 채우기
  while (selected.length < 3) {
    const avail = TAIL_KEYWORDS.filter(t => !selected.includes(t));
    if (!avail.length) break;
    selected.push(avail[Math.floor(Math.random() * avail.length)]);
  }

  return selected.slice(0, 3).join(' ');
}

// ============ URL 생성 ============

/**
 * 랜덤 ackey 생성 (영소문자 + 숫자, 8자리)
 */
function generateAckey(): string {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let result = '';
  for (let i = 0; i < 8; i++) {
    result += chars[Math.floor(Math.random() * chars.length)];
  }
  return result;
}

/**
 * 네이버 모바일 검색 URL 생성
 * https://m.search.naver.com/search.naver?sm=mtp_sug.top&where=m&query=...&ackey=...&acq=...&acr=1~9&qdt=0
 */
function buildSearchUrl(query: string): string {
  const p = new URLSearchParams({
    sm: 'mtp_sug.top',
    where: 'm',
    query,
    ackey: generateAckey(),
    acq: query,
    acr: String(Math.floor(Math.random() * 9) + 1),
    qdt: '0',
  });
  return `${NAVER_SEARCH_BASE}?${p.toString()}`;
}

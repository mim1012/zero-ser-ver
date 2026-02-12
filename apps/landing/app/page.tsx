import { getServiceSupabase } from '@/lib/supabase';

export const revalidate = 60; // ISR: 60초마다 갱신

interface LandingItem {
  slug: string;
  keyword: string;
  product_name: string;
  redirect_count: number;
  created_at: string;
}

export default async function HomePage() {
  let items: LandingItem[] = [];

  try {
    const supabase = getServiceSupabase();
    const { data } = await supabase
      .from('landing_redirects')
      .select('slug, keyword, product_name, redirect_count, created_at')
      .eq('active', true)
      .order('created_at', { ascending: false })
      .limit(8);

    items = data || [];
  } catch {
    // DB 연결 실패 시 빈 목록
  }

  const today = new Date().toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });

  return (
    <>
      <header className="blog-header">
        <div className="container">
          <div>
            <div className="blog-title">쇼핑 리뷰 블로그</div>
            <div className="blog-subtitle">매일 업데이트되는 쇼핑 추천</div>
          </div>
          <div className="blog-subtitle">{today}</div>
        </div>
      </header>

      <main className="container">
        <div className="card" style={{ marginBottom: 24 }}>
          <h1 className="card-title">오늘의 쇼핑 추천</h1>
          <p className="card-body">
            엄선된 인기 상품들을 매일 리뷰하고 추천합니다.
            가격 비교부터 실사용 후기까지, 현명한 쇼핑을 도와드립니다.
          </p>
        </div>

        <div className="home-grid">
          {items.length > 0 ? (
            items.map((item) => (
              <a
                key={item.slug}
                href={`/r/${item.slug}`}
                className="home-card"
                style={{ textDecoration: 'none' }}
              >
                <h3>{item.product_name || item.keyword} 추천 리뷰</h3>
                <p>{item.keyword} - 최저가 비교 및 상세 리뷰</p>
              </a>
            ))
          ) : (
            <div className="card">
              <p className="card-body" style={{ textAlign: 'center', color: '#999' }}>
                리뷰가 준비 중입니다.
              </p>
            </div>
          )}
        </div>
      </main>

      <footer className="footer">
        <p>쇼핑 리뷰 블로그 - 매일 업데이트</p>
      </footer>
    </>
  );
}

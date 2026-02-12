import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: '쇼핑 리뷰 블로그 - 오늘의 추천',
  description: '매일 업데이트되는 쇼핑 추천 리뷰 블로그입니다.',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}

import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  metadataBase: new URL("https://buddystudy.lowfidev.cloud"),
  title: {
    default: "BuddyStudy | AI 학습 시스템 포트폴리오",
    template: "%s | BuddyStudy",
  },
  description:
    "SwiftUI iOS, Kotlin WebFlux, R2DBC, Redis Streams, APNs, Cloudflare WARP와 실측 성능 분석으로 만든 AI 학습 시스템.",
  icons: {
    icon: "/media/buddystudy-icon.png",
    shortcut: "/media/buddystudy-icon.png",
  },
  openGraph: {
    title: "BuddyStudy | AI 학습 시스템 포트폴리오",
    description:
      "제품 설계부터 백엔드, 성능 실험, 보안, 관측 가능성, 배포까지 검증 가능한 근거로 정리했습니다.",
    url: "https://buddystudy.lowfidev.cloud",
    siteName: "BuddyStudy",
    locale: "ko_KR",
    type: "website",
    images: [{ url: "/og.png", width: 1200, height: 630, alt: "BuddyStudy portfolio" }],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        {children}
      </body>
    </html>
  );
}

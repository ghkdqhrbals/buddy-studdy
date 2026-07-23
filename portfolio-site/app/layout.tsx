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
    "통계 최적화, 캐시 경계, Redis Streams 전달 보장, 조합형 아바타, 사설 인프라와 MVC/WebFlux 실측 차트로 설명하는 BuddyStudy 엔지니어링 포트폴리오.",
  icons: {
    icon: "/media/buddystudy-icon.png",
    shortcut: "/media/buddystudy-icon.png",
  },
  openGraph: {
    title: "BuddyStudy | AI 학습 시스템 포트폴리오",
    description:
      "실제 구현 근거와 k6·nGrinder 성능 데이터로 통계, 캐시, 메시징, 이미지와 인프라 설계를 설명합니다.",
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

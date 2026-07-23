"use client";

import Image from "next/image";
import {
  Activity,
  ArrowDown,
  ArrowUpRight,
  BellRing,
  Blocks,
  BookOpen,
  Braces,
  Check,
  ChevronRight,
  Cloud,
  Database,
  Gauge,
  GitFork,
  GraduationCap,
  KeyRound,
  Layers3,
  LockKeyhole,
  MessageSquareText,
  Network,
  RefreshCw,
  Route,
  ServerCog,
  ShieldCheck,
  Smartphone,
  TestTube2,
  TimerReset,
  Workflow,
} from "lucide-react";

const navigation = [
  ["제품", "product"],
  ["아키텍처", "architecture"],
  ["성능", "performance"],
  ["안정성", "reliability"],
  ["보안", "security"],
  ["검증", "testing"],
  ["인터뷰", "interview"],
];

const productFeatures = [
  {
    icon: BookOpen,
    title: "질문부터 채점까지",
    summary: "주제와 난이도에 맞는 짧은 질문을 만들고, 힌트와 AI 채점 결과를 제공합니다.",
    detail: "OpenAI 호출은 서버에서만 수행하며 질문, 초안, 채점 결과를 PostgreSQL에 보존합니다.",
  },
  {
    icon: RefreshCw,
    title: "초안을 지키는 동기화",
    summary: "새 질문이나 원격 동기화가 진행돼도 사용자가 작성 중인 답변을 덮어쓰지 않습니다.",
    detail: "백엔드를 원본으로 두되 활성 초안과 복구 상태는 클라이언트 정책으로 보호합니다.",
  },
  {
    icon: GraduationCap,
    title: "주제 중심 통계",
    summary: "점수 하나가 아니라 주제·난이도·기간을 함께 보며 학습 범위를 추정합니다.",
    detail: "정규화한 topic key와 증분 dirty-key read model로 통계를 제한된 범위만 재계산합니다.",
  },
  {
    icon: BellRing,
    title: "APNs 예약 학습",
    summary: "질문을 먼저 저장한 뒤 푸시를 발송하고, 알림을 눌렀을 때만 학습 화면으로 이동합니다.",
    detail: "Redis Streams와 transactional outbox가 생성·저장·전송 사이의 실패 경계를 관리합니다.",
  },
  {
    icon: MessageSquareText,
    title: "공개 질문 커뮤니티",
    summary: "프로필, 공개 질문, 좋아요, 댓글, 신고와 약관 기반 접근 정책을 제공합니다.",
    detail: "device identity와 Google-linked user를 분리하고 안정적인 API error code로 앱 동작을 결정합니다.",
  },
  {
    icon: Smartphone,
    title: "iOS에 맞춘 사용성",
    summary: "SwiftUI의 일관된 탭 구조, 페이지네이션, 비동기 갱신, 로그인 복귀 흐름을 설계했습니다.",
    detail: "기록 10,000건까지 고려해 전체 렌더링을 피하고 화면별 loading state를 분리합니다.",
  },
];

const screenshots = [
  { src: "/media/study.png", alt: "BuddyStudy 질문과 답변 화면", label: "Study" },
  { src: "/media/records.png", alt: "BuddyStudy 학습 기록 화면", label: "Records" },
  { src: "/media/stats.png", alt: "BuddyStudy 주제 통계 화면", label: "Statistics" },
  { src: "/media/settings.png", alt: "BuddyStudy 설정 화면", label: "Settings" },
];

const architectureLayers = [
  { icon: Smartphone, name: "SwiftUI iOS", note: "상태·초안·오류 표현 정책" },
  { icon: Route, name: "Cloudflare + Nginx", note: "HTTPS 라우팅과 접근 경계" },
  { icon: Blocks, name: "Use Cases", note: "도메인 흐름과 권한 정책" },
  { icon: Database, name: "PostgreSQL + R2DBC", note: "트랜잭션과 원본 데이터" },
  { icon: Workflow, name: "Redis Streams", note: "outbox 기반 비동기 전달" },
  { icon: Cloud, name: "OpenAI + APNs", note: "생성·채점·푸시 어댑터" },
];

const testMatrix = [
  ["iOS", "unit + generic build + real device", "초안, 오류 라우팅, 디코딩, 푸시·백그라운드 UX"],
  ["Backend", "unit + integration", "use case, R2DBC, 트랜잭션, migration, HTTP contract"],
  ["Infrastructure", "Node test", "Cloudflare Worker 설정, timeout, Slack alert 정책"],
  ["Monitoring", "parser + query test", "구조화 로그, LogQL, Native Image runtime sample"],
  ["Performance", "k6 + telemetry + JFR", "RPS, p90/p95/p99, CPU, RSS, thread, DB pool, allocation"],
];

const interviewQuestions = [
  {
    question: "왜 WebFlux를 선택했고, 실제로 더 빨랐나요?",
    answer:
      "외부 OpenAI·APNs·Redis·DB I/O가 많은 흐름을 coroutine으로 읽기 쉽게 구성하려는 선택이었습니다. 하지만 프레임워크가 자동으로 처리량을 높인다고 가정하지 않았습니다. 동일 조건 실험에서 health는 두 런타임 모두 3,000 RPS를 달성했지만 현재 R2DBC 목록 경로는 10개 연결 뒤에 수천 요청이 대기했습니다. 그래서 결론은 'WebFlux가 빠르다'가 아니라 query/row mapping과 bounded admission을 먼저 개선해야 한다는 것입니다.",
  },
  {
    question: "비동기 이벤트가 유실되거나 중복되면 어떻게 하나요?",
    answer:
      "비즈니스 변경과 outbox row를 같은 PostgreSQL 트랜잭션에 기록합니다. dispatcher가 SKIP LOCKED로 claim하고 Redis Stream에 발행합니다. 발행 직후 장애가 나면 재발행될 수 있으므로 delivery는 at-least-once이며 consumer가 event type과 event id로 중복을 제거합니다.",
  },
  {
    question: "R2DBC의 @Transactional은 같은 스레드에서 동작하나요?",
    answer:
      "트랜잭션은 thread-local이 아니라 Reactor Context를 따라갑니다. coroutine이 다른 스레드에서 재개돼도 동일 reactive transaction에 참여할 수 있습니다. JPA persistence context는 없기 때문에 dirty checking이나 lazy loading을 기대하지 않고 변경을 명시적으로 저장합니다.",
  },
  {
    question: "운영 보안은 어디까지 고려했나요?",
    answer:
      "공개 HTTP만 Cloudflare Tunnel과 Nginx로 라우팅합니다. PostgreSQL과 Redis 관리 경로는 WARP private route를 우선 사용하며, Loki와 Grafana는 localhost에 bind하고 인증 프록시만 외부에 노출합니다. 런타임 secret은 AWS Secrets Manager에서 배포 시점에 주입하고 토큰·비밀값은 로그에서 마스킹합니다.",
  },
  {
    question: "다음 성능 개선의 우선순위는 무엇인가요?",
    answer:
      "hot list query를 필요한 필드만 읽는 projection으로 바꾸고 content/count 왕복을 줄이는 것이 먼저입니다. 그 다음 인증 query amplification과 불필요한 body logging을 제거하고 cancellation correctness를 고칩니다. 마지막에 실제 DB 처리량에 맞춘 짧은 bounded queue를 적용한 뒤 독립된 load generator에서 재측정합니다.",
  },
];

export default function Home() {
  return (
    <div className="site">
      <header className="nav-shell">
        <a className="brand" href="#top" aria-label="BuddyStudy 처음으로">
          <Image src="/media/buddystudy-icon.png" alt="" width={34} height={34} priority unoptimized />
          <span>BuddyStudy</span>
        </a>
        <nav className="primary-nav" aria-label="포트폴리오 섹션">
          {navigation.map(([label, id]) => (
            <a href={`#${id}`} key={id}>{label}</a>
          ))}
        </nav>
        <a
          className="github-link"
          href="https://github.com/ghkdqhrbals/buddy-studdy"
          target="_blank"
          rel="noreferrer"
          aria-label="GitHub 저장소 열기"
        >
          <GitFork size={18} />
          <span>Repository</span>
        </a>
      </header>

      <main id="top">
        <section className="hero">
          <div className="hero-noise" aria-hidden="true" />
          <div className="hero-copy">
            <p className="eyebrow">iOS AI LEARNING SYSTEM · END-TO-END ENGINEERING</p>
            <h1>BuddyStudy</h1>
            <p className="hero-lead">
              질문을 만드는 앱을 넘어, 학습 데이터가 쌓이고 설명 가능한 통계로 돌아오는
              전체 시스템을 설계했습니다.
            </p>
            <p className="hero-detail">
              SwiftUI · Kotlin · WebFlux · R2DBC · PostgreSQL · Redis Streams · APNs ·
              Cloudflare · GraalVM Native Image
            </p>
            <div className="hero-actions">
              <a className="primary-action" href="#product">
                프로젝트 살펴보기 <ArrowDown size={18} />
              </a>
              <a className="secondary-action" href="#performance">
                성능 실험 보기 <Gauge size={18} />
              </a>
            </div>
          </div>
          <div className="hero-phones" aria-label="BuddyStudy iOS 화면 미리보기">
            <Image className="phone phone-back" src="/media/records.png" alt="학습 기록 화면" width={340} height={736} priority unoptimized />
            <Image className="phone phone-main" src="/media/study.png" alt="질문 학습 화면" width={380} height={824} priority unoptimized />
            <Image className="phone phone-front" src="/media/stats.png" alt="주제 통계 화면" width={340} height={736} priority unoptimized />
          </div>
          <div className="hero-facts" aria-label="프로젝트 핵심 수치">
            <div><strong>3,000</strong><span>RPS 비교 구간</span></div>
            <div><strong>4</strong><span>독립 배포 모듈</span></div>
            <div><strong>30s</strong><span>runtime sample</span></div>
            <div><strong>iOS</strong><span>public release</span></div>
          </div>
        </section>

        <section className="intro-band" aria-labelledby="overview-title">
          <div className="section-heading">
            <p className="section-index">00 / OVERVIEW</p>
            <h2 id="overview-title">제품과 시스템을 함께 만들었습니다.</h2>
          </div>
          <div className="intro-copy">
            <p>
              <strong>쉽게 말하면</strong> BuddyStudy는 관심 주제를 짧게 질문하고, 답변을
              채점해 부족한 영역을 보여주는 개인 학습 도구입니다.
            </p>
            <p>
              <strong>기술적으로는</strong> 모바일 상태 복구, reactive transaction,
              transactional outbox, 증분 통계 read model, APNs delivery, 구조화 로그,
              Native Image 관측, 부하 실험과 private network 운영까지 포함한 시스템입니다.
            </p>
          </div>
        </section>

        <section id="product" className="section product-section">
          <div className="section-heading">
            <p className="section-index">01 / PRODUCT</p>
            <h2>학습의 전체 피드백 루프</h2>
            <p>질문 생성에서 끝나지 않고, 기록과 통계가 다음 학습을 설명하도록 연결했습니다.</p>
          </div>
          <div className="feature-grid">
            {productFeatures.map(({ icon: Icon, title, summary, detail }) => (
              <article className="feature-item" key={title}>
                <Icon size={24} strokeWidth={1.8} />
                <h3>{title}</h3>
                <p>{summary}</p>
                <small>{detail}</small>
              </article>
            ))}
          </div>
          <div className="screen-gallery">
            {screenshots.map((screen) => (
              <figure key={screen.label}>
                <Image src={screen.src} alt={screen.alt} width={430} height={932} unoptimized />
                <figcaption>{screen.label}</figcaption>
              </figure>
            ))}
          </div>
        </section>

        <section id="architecture" className="section architecture-section">
          <div className="section-heading light">
            <p className="section-index">02 / ARCHITECTURE</p>
            <h2>의존성은 안쪽으로, I/O는 바깥으로</h2>
            <p>프레임워크가 도메인의 언어가 되지 않도록 use case와 port를 경계로 사용합니다.</p>
          </div>
          <div className="architecture-flow" aria-label="BuddyStudy 시스템 계층">
            {architectureLayers.map(({ icon: Icon, name, note }, index) => (
              <div className="architecture-step" key={name}>
                <div>
                  <Icon size={25} />
                  <strong>{name}</strong>
                  <span>{note}</span>
                </div>
                {index < architectureLayers.length - 1 && <ChevronRight aria-hidden="true" size={20} />}
              </div>
            ))}
          </div>
          <div className="architecture-notes">
            <div>
              <Braces size={22} />
              <h3>Kotlin coroutine</h3>
              <p>순차적인 비즈니스 코드를 유지하면서 reactive context의 non-blocking I/O를 사용합니다.</p>
            </div>
            <div>
              <Layers3 size={22} />
              <h3>Reactive transaction</h3>
              <p>스레드가 아니라 Reactor Context에 트랜잭션을 연결하고 변경은 명시적으로 저장합니다.</p>
            </div>
            <div>
              <Workflow size={22} />
              <h3>Transactional outbox</h3>
              <p>DB write와 event intent를 원자적으로 남기고 Redis에는 at-least-once로 전달합니다.</p>
            </div>
          </div>
        </section>

        <section id="performance" className="section performance-section">
          <div className="section-heading">
            <p className="section-index">03 / PERFORMANCE</p>
            <h2>프레임워크가 아니라 병목을 측정했습니다.</h2>
            <p>같은 CPU, heap, DB fixture와 10개 connection pool에서 MVC/JDBC와 WebFlux/R2DBC를 비교했습니다.</p>
          </div>
          <div className="performance-layout">
            <div className="performance-visual">
              <Image
                src="/media/load-test-dashboard.png"
                alt="BuddyStudy MVC와 WebFlux k6 부하 테스트 대시보드"
                width={1200}
                height={2200}
                unoptimized
              />
            </div>
            <div className="performance-findings">
              <p className="finding-kicker">CONTROLLED EXPERIMENT</p>
              <h3>limit 100 → 1</h3>
              <div className="finding-number"><strong>97.9%</strong><span>p95 감소</span></div>
              <div className="finding-number"><strong>73.2%</strong><span>allocation 감소</span></div>
              <p>
                400 RPS에서 row 수만 통제하자 reactive path의 pool queue가 259에서 5로
                줄었습니다. 병목은 Netty 자체가 아니라 100-row materialization과 응답
                pipeline에 있었습니다.
              </p>
              <ul className="check-list">
                <li><Check size={17} />1,000~3,000 constant arrival rate</li>
                <li><Check size={17} />p50·p90·p95·p99와 successful RPS</li>
                <li><Check size={17} />CPU·RSS·thread·GC·allocation·DB pool</li>
                <li><Check size={17} />JFR와 독립 변수 통제 실험</li>
              </ul>
            </div>
          </div>
          <div className="result-strip">
            <div><span>Health</span><strong>3,000 RPS</strong><small>두 런타임 모두 100%</small></div>
            <div><span>MVC studies</span><strong>2,478 RPS</strong><small>2,500 target, successful</small></div>
            <div><span>Reactive queue</span><strong>2,992</strong><small>10개 DB connection 뒤 pending</small></div>
            <div><span>Median RSS</span><strong>+3.8%</strong><small>WebFlux 전체 구간</small></div>
          </div>
          <p className="evidence-note">
            이 결과는 WebFlux나 R2DBC가 본질적으로 느리다는 뜻이 아닙니다. 현재 구현에서
            admission control과 query 비용이 처리량을 제한한다는 실측 결과입니다.
          </p>
        </section>

        <section id="reliability" className="section reliability-section">
          <div className="section-heading">
            <p className="section-index">04 / RELIABILITY</p>
            <h2>실패할 수 있다는 전제로 연결합니다.</h2>
          </div>
          <div className="reliability-timeline">
            <div><span>01</span><strong>Question write</strong><p>질문과 outbox row를 같은 transaction에 저장</p></div>
            <ArrowDown aria-hidden="true" />
            <div><span>02</span><strong>Claim</strong><p>SKIP LOCKED와 lease로 여러 worker의 중복 claim 방지</p></div>
            <ArrowDown aria-hidden="true" />
            <div><span>03</span><strong>Publish</strong><p>Redis Stream으로 APNs job 발행, 실패 시 exponential backoff</p></div>
            <ArrowDown aria-hidden="true" />
            <div><span>04</span><strong>Deduplicate</strong><p>event type + event id로 consumer의 중복 처리 방지</p></div>
          </div>
          <div className="reliability-principles">
            <article>
              <TimerReset size={23} />
              <h3>Eventually consistent</h3>
              <p>통계와 알림은 원본 write를 막지 않고 재시도 가능한 read model과 outbox로 수렴합니다.</p>
            </article>
            <article>
              <RefreshCw size={23} />
              <h3>Idempotent refresh</h3>
              <p>dirty key는 bounded batch로 처리하고 동시 갱신이 있으면 marker를 남겨 다음 실행에서 재처리합니다.</p>
            </article>
            <article>
              <Activity size={23} />
              <h3>External readiness</h3>
              <p>배포 순간이 아닌 Cloudflare Cron이 운영 readiness를 확인하고 Slack으로 상태 전이를 알립니다.</p>
            </article>
          </div>
        </section>

        <section id="security" className="section security-section">
          <div className="section-heading light">
            <p className="section-index">05 / SECURITY</p>
            <h2>공개 트래픽과 운영자 경로를 분리했습니다.</h2>
            <p>DB와 Redis를 인터넷에 직접 노출하지 않고, 공개 HTTP와 private administration의 경계를 다르게 둡니다.</p>
          </div>
          <div className="network-paths">
            <div className="network-path public-path">
              <span>PUBLIC HTTPS</span>
              <div><Cloud size={23} /><strong>Cloudflare Tunnel</strong></div>
              <ChevronRight />
              <div><Route size={23} /><strong>Nginx</strong></div>
              <ChevronRight />
              <div><ServerCog size={23} /><strong>Backend API</strong></div>
            </div>
            <div className="network-path private-path">
              <span>PRIVATE ADMIN</span>
              <div><KeyRound size={23} /><strong>Operator</strong></div>
              <ChevronRight />
              <div><ShieldCheck size={23} /><strong>WARP /32 route</strong></div>
              <ChevronRight />
              <div><Database size={23} /><strong>Postgres · Redis</strong></div>
            </div>
          </div>
          <div className="security-grid">
            <div><LockKeyhole size={22} /><strong>Secret isolation</strong><p>AWS Secrets Manager에서 배포 시점에 runtime secret을 주입합니다.</p></div>
            <div><Network size={22} /><strong>Local observability</strong><p>Loki와 Grafana는 localhost에 bind하고 인증 프록시만 노출합니다.</p></div>
            <div><ShieldCheck size={22} /><strong>Device-bound identity</strong><p>access token의 user와 device를 저장된 관계와 함께 검증합니다.</p></div>
            <div><Workflow size={22} /><strong>Module deployment</strong><p>backend, admin, monitoring, health monitor를 독립 workflow로 배포합니다.</p></div>
          </div>
        </section>

        <section id="testing" className="section testing-section">
          <div className="section-heading">
            <p className="section-index">06 / TESTING &amp; OBSERVABILITY</p>
            <h2>화면, 계약, 운영 지표를 각각 검증합니다.</h2>
          </div>
          <div className="test-table" role="table" aria-label="BuddyStudy 테스트 전략">
            <div className="test-row test-head" role="row">
              <span role="columnheader">영역</span>
              <span role="columnheader">방식</span>
              <span role="columnheader">검증 대상</span>
            </div>
            {testMatrix.map(([area, method, scope]) => (
              <div className="test-row" role="row" key={area}>
                <strong role="cell">{area}</strong>
                <span role="cell">{method}</span>
                <span role="cell">{scope}</span>
              </div>
            ))}
          </div>
          <div className="observability-band">
            <div>
              <TestTube2 size={24} />
              <h3>Request trace</h3>
              <p>request id로 sanitized request, response, stack trace와 관련 로그를 한 번에 조회합니다.</p>
            </div>
            <div>
              <Gauge size={24} />
              <h3>Golden signals</h3>
              <p>traffic, latency, error, saturation을 같은 시간축에서 비교합니다.</p>
            </div>
            <div>
              <ServerCog size={24} />
              <h3>Native Image metrics</h3>
              <p>지원되지 않는 MXBean 하나가 전체 sample을 지우지 않도록 collector를 독립시켰습니다.</p>
            </div>
          </div>
        </section>

        <section id="interview" className="section interview-section">
          <div className="section-heading">
            <p className="section-index">07 / INTERVIEW NOTES</p>
            <h2>결정의 이유와 한계를 함께 설명합니다.</h2>
            <p>각 답변은 현재 구현과 실측 결과를 기준으로 하며, 계획을 완료된 성과처럼 표현하지 않습니다.</p>
          </div>
          <div className="questions">
            {interviewQuestions.map(({ question, answer }, index) => (
              <details key={question} open={index === 0}>
                <summary>
                  <span>{String(index + 1).padStart(2, "0")}</span>
                  {question}
                  <ChevronRight size={20} />
                </summary>
                <p>{answer}</p>
              </details>
            ))}
          </div>
        </section>

        <section className="closing-section">
          <Image src="/media/buddystudy-icon.png" alt="BuddyStudy" width={92} height={92} unoptimized />
          <p className="section-index">BUILD · MEASURE · EXPLAIN</p>
          <h2>작동하는 기능과 설명 가능한 근거를 함께 남깁니다.</h2>
          <p>전체 코드, 상세 성능 보고서와 운영 문서는 저장소에서 확인할 수 있습니다.</p>
          <a href="https://github.com/ghkdqhrbals/buddy-studdy" target="_blank" rel="noreferrer">
            GitHub에서 프로젝트 보기 <ArrowUpRight size={18} />
          </a>
        </section>
      </main>

      <footer>
        <span>BuddyStudy</span>
        <span>SwiftUI · Kotlin · PostgreSQL · Redis · Cloudflare</span>
        <a href="#top">맨 위로 <ArrowUpRight size={15} /></a>
      </footer>
    </div>
  );
}

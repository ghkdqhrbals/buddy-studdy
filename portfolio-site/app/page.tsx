import Image from "next/image";

const navigation = [
  ["개요", "overview"],
  ["개선 사항", "improvements"],
  ["제품", "product"],
  ["아키텍처", "architecture"],
  ["성능", "performance"],
  ["안정성", "reliability"],
  ["보안", "security"],
  ["검증", "testing"],
  ["인터뷰", "interview"],
];

const improvements = [
  {
    problem: "대량 목록 조회에서 지연과 메모리 할당이 급증",
    change: "조회 행 수와 매핑 비용을 분리 측정하고 hot path를 projection 중심으로 재설계",
    result: "동일 400 RPS 실험에서 p95 780.94ms → 16.53ms, allocation 73.2% 감소",
  },
  {
    problem: "질문 생성 후 푸시 발송 실패 시 이벤트 유실 가능",
    change: "도메인 변경과 outbox를 같은 트랜잭션에 기록하고 Redis Streams로 비동기 전달",
    result: "at-least-once 전달과 consumer idempotency로 실패 복구 경계 명확화",
  },
  {
    problem: "동기화 중 사용자가 작성하던 답변이 교체될 수 있음",
    change: "서버 데이터를 원본으로 유지하되 활성 초안은 클라이언트 우선 정책으로 보호",
    result: "예약·푸시·동기화가 진행돼도 작성 중인 답변 보존",
  },
  {
    problem: "Native Image 환경에서 일부 지표 실패가 전체 수집을 중단",
    change: "JVM·프로세스·OS collector를 격리하고 /proc 기반 fallback 추가",
    result: "지원되지 않는 지표만 제외하고 CPU·RSS·스레드 진단은 계속 수집",
  },
  {
    problem: "DB와 운영 도구가 공용 네트워크에 노출될 위험",
    change: "Cloudflare Tunnel, WARP /32 private route, localhost bind와 인증 프록시 적용",
    result: "공개 HTTP와 관리 트래픽을 분리하고 공격 표면 축소",
  },
];

const productFlow = [
  ["1", "질문 생성", "주제·난이도·월간 quota를 확인한 뒤 OpenAI를 통해 질문을 생성합니다."],
  ["2", "답변 보존", "사용자가 작성 중인 초안을 로컬에 보존하고 원격 동기화와 충돌하지 않게 합니다."],
  ["3", "AI 채점", "점수뿐 아니라 설명과 피드백을 저장해 다음 복습의 근거로 사용합니다."],
  ["4", "기록·통계", "주제와 난이도를 기준으로 학습 기록을 집계하고 부족한 범위를 보여줍니다."],
];

const screenshots = [
  { src: "/media/study.png", alt: "BuddyStudy 질문과 답변 화면", label: "질문과 답변" },
  { src: "/media/records.png", alt: "BuddyStudy 학습 기록 화면", label: "학습 기록" },
  { src: "/media/stats.png", alt: "BuddyStudy 주제별 통계 화면", label: "주제별 통계" },
  { src: "/media/settings.png", alt: "BuddyStudy 설정 화면", label: "설정" },
];

const testMatrix = [
  ["iOS", "unit, generic build, real device", "초안 복구, API decoding, 오류 라우팅, 푸시 UX"],
  ["Backend", "unit, integration", "use case, R2DBC, transaction, migration, HTTP contract"],
  ["Infrastructure", "Node test", "Worker 설정, timeout, health monitor, Slack 정책"],
  ["Monitoring", "parser, query test", "구조화 로그, LogQL, Native Image runtime sample"],
  ["Performance", "k6, telemetry, JFR", "RPS, p90/p95/p99, CPU, RSS, thread, DB pool, allocation"],
];

const interviewQuestions = [
  {
    question: "왜 WebFlux를 선택했고, 실제로 더 빨랐나요?",
    answer:
      "외부 API, Redis, DB처럼 I/O가 많은 흐름을 coroutine으로 일관되게 구성하려는 선택이었습니다. 다만 프레임워크 자체가 처리량을 보장하지는 않습니다. health 경로는 MVC와 WebFlux 모두 3,000 RPS를 처리했지만, DB 목록 경로는 10개 연결 뒤에 요청이 쌓였습니다. 따라서 병목은 런타임보다 query 크기, row mapping, DB pool과 admission control에서 먼저 찾았습니다.",
  },
  {
    question: "비동기 이벤트가 유실되거나 중복되면 어떻게 하나요?",
    answer:
      "비즈니스 변경과 outbox row를 동일한 PostgreSQL 트랜잭션에 기록합니다. dispatcher는 SKIP LOCKED로 이벤트를 claim하고 Redis Stream으로 발행합니다. 발행 직후 장애가 나면 재발행될 수 있으므로 delivery는 at-least-once이며, consumer는 event id를 기준으로 멱등 처리합니다.",
  },
  {
    question: "R2DBC 트랜잭션은 같은 스레드에서만 동작하나요?",
    answer:
      "아닙니다. reactive transaction은 thread-local이 아니라 Reactor Context를 따라갑니다. coroutine이 다른 스레드에서 재개되어도 같은 context 안에서는 동일 트랜잭션에 참여합니다. JPA persistence context는 없으므로 dirty checking이나 lazy loading 대신 변경을 명시적으로 저장합니다.",
  },
  {
    question: "운영 보안은 어디까지 고려했나요?",
    answer:
      "공개 HTTP는 Cloudflare Tunnel과 Nginx만 통과시킵니다. PostgreSQL과 Redis 관리 경로는 WARP private route로 제한하고, Loki와 Grafana는 localhost에 bind한 뒤 인증 프록시를 통해서만 접근합니다. secret은 AWS Secrets Manager에서 주입하며 토큰과 민감 헤더는 구조화 로그에서 마스킹합니다.",
  },
];

function SectionTitle({
  eyebrow,
  title,
  description,
}: {
  eyebrow: string;
  title: string;
  description?: string;
}) {
  return (
    <header className="section-title">
      <span>{eyebrow}</span>
      <h2>{title}</h2>
      {description && <p>{description}</p>}
    </header>
  );
}

export default function Home() {
  return (
    <div className="site-shell">
      <a className="skip-link" href="#content">본문으로 이동</a>

      <header className="topbar">
        <a className="brand" href="#overview" aria-label="BuddyStudy 개요로 이동">
          <Image src="/media/buddystudy-icon.png" alt="" width={30} height={30} priority unoptimized />
          <strong>BuddyStudy</strong>
          <span>Engineering Notes</span>
        </a>
        <div className="topbar-links">
          <span>SwiftUI · Kotlin · WebFlux</span>
          <a href="https://github.com/ghkdqhrbals/buddy-studdy" target="_blank" rel="noreferrer">
            GitHub ↗
          </a>
        </div>
      </header>

      <div className="document-layout">
        <aside className="sidebar" aria-label="문서 목차">
          <p>On this page</p>
          <nav>
            {navigation.map(([label, id]) => (
              <a href={`#${id}`} key={id}>{label}</a>
            ))}
          </nav>
          <div className="sidebar-meta">
            <span>Last updated</span>
            <time dateTime="2026-07-24">2026. 07. 24.</time>
          </div>
        </aside>

        <main id="content" className="markdown-body">
          <section id="overview" className="document-hero">
            <div className="status-line">
              <span>iOS</span>
              <span>Production</span>
              <span>End-to-end</span>
            </div>
            <h1>BuddyStudy</h1>
            <p className="lead">
              짧은 질문으로 학습하고, 답변 기록을 주제별 통계로 연결하는
              <strong> iOS AI 학습 시스템</strong>입니다.
            </p>
            <p>
              이 문서는 화면 목록보다 <strong>어떤 문제를 발견했고, 어떤 근거로 개선했으며,
              운영에서 어떻게 검증했는지</strong>를 설명합니다. SwiftUI 앱부터 Kotlin
              WebFlux/R2DBC 백엔드, 비동기 이벤트, 배포, 관측과 부하 실험까지 한 시스템으로
              설계했습니다.
            </p>

            <blockquote>
              <strong>핵심 관점</strong>
              <p>
                “WebFlux가 빠르다” 같은 결론을 먼저 정하지 않습니다. 요청 지연, DB pool,
                allocation, queue depth를 함께 측정하고 실제 병목이 있는 경로를 줄였습니다.
              </p>
            </blockquote>

            <div className="quick-facts" aria-label="프로젝트 요약">
              <dl>
                <dt>Client</dt>
                <dd>SwiftUI / iOS</dd>
              </dl>
              <dl>
                <dt>Backend</dt>
                <dd>Kotlin / WebFlux / R2DBC</dd>
              </dl>
              <dl>
                <dt>Data</dt>
                <dd>PostgreSQL / Redis Streams</dd>
              </dl>
              <dl>
                <dt>Operations</dt>
                <dd>Cloudflare / AWS / PLG</dd>
              </dl>
            </div>
          </section>

          <hr />

          <section id="improvements">
            <SectionTitle
              eyebrow="01 · Improvements"
              title="무엇을 개선했는가"
              description="기능의 개수보다 문제, 변경, 검증 결과가 연결되도록 정리했습니다."
            />
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>문제</th>
                    <th>개선</th>
                    <th>결과</th>
                  </tr>
                </thead>
                <tbody>
                  {improvements.map((item) => (
                    <tr key={item.problem}>
                      <td>{item.problem}</td>
                      <td>{item.change}</td>
                      <td><strong>{item.result}</strong></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <hr />

          <section id="product">
            <SectionTitle
              eyebrow="02 · Product"
              title="질문에서 복습까지 이어지는 흐름"
              description="질문 생성 자체가 아니라 사용자의 답변을 안전하게 보존하고 다음 학습으로 연결하는 데 집중했습니다."
            />
            <ol className="flow-list">
              {productFlow.map(([number, title, description]) => (
                <li key={number}>
                  <span>{number}</span>
                  <div>
                    <h3>{title}</h3>
                    <p>{description}</p>
                  </div>
                </li>
              ))}
            </ol>

            <h3>iOS 화면</h3>
            <div className="screenshot-grid">
              {screenshots.map((screen) => (
                <figure key={screen.label}>
                  <Image src={screen.src} alt={screen.alt} width={390} height={844} unoptimized />
                  <figcaption>{screen.label}</figcaption>
                </figure>
              ))}
            </div>
          </section>

          <hr />

          <section id="architecture">
            <SectionTitle
              eyebrow="03 · Architecture"
              title="의존성은 안쪽으로, I/O는 바깥으로"
              description="도메인 흐름을 프레임워크와 저장소 구현에서 분리하기 위해 use case와 port를 경계로 사용합니다."
            />
            <pre aria-label="BuddyStudy 시스템 아키텍처">
              <code>{`SwiftUI iOS
    │  HTTPS / APNs
    ▼
Cloudflare Tunnel ── Nginx
    │
    ▼
Inbound Port ── Application Use Case ── Domain
                       │
          ┌────────────┼─────────────┐
          ▼            ▼             ▼
    PostgreSQL     Redis Streams   OpenAI / APNs
      R2DBC        + Outbox        Adapters

Observability: structured logs → Promtail → Loki → Grafana`}</code>
            </pre>

            <h3>경계 규칙</h3>
            <ul>
              <li>Controller는 controller-facing port에만 의존합니다.</li>
              <li>Application service는 inbound use case 계약을 구현합니다.</li>
              <li>하위 도메인 로직은 service가 아니라 outbound port에 의존합니다.</li>
              <li>R2DBC 변경은 JPA 영속성 컨텍스트 없이 명시적으로 저장합니다.</li>
              <li>트랜잭션은 thread-local이 아닌 Reactor Context를 따라갑니다.</li>
            </ul>

            <h3>비동기 질문 전달</h3>
            <pre>
              <code>{`[DB transaction]
question 저장 + outbox 저장
        │ commit
        ▼
dispatcher ── Redis Stream ── consumer ── APNs
                   │
                   └── event_id 기반 멱등 처리`}</code>
            </pre>
            <p>
              DB 저장과 메시지 발행 사이의 dual-write 문제는 transactional outbox로 다룹니다.
              전달 보장은 <code>at-least-once</code>이고, 중복 가능성은 consumer의 멱등성으로
              흡수합니다.
            </p>
          </section>

          <hr />

          <section id="performance">
            <SectionTitle
              eyebrow="04 · Performance"
              title="프레임워크가 아니라 병목을 비교했습니다"
              description="같은 API, 같은 DB 조건에서 RPS·지연·CPU·RSS·스레드·DB pool·allocation을 함께 수집했습니다."
            />

            <h3>주요 실험 결과</h3>
            <div className="metric-strip">
              <div><strong>780.94 → 16.53ms</strong><span>p95 latency</span></div>
              <div><strong>-73.2%</strong><span>allocation rate</span></div>
              <div><strong>259 → 5</strong><span>DB pool queue</span></div>
            </div>

            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>실험</th>
                    <th>관찰</th>
                    <th>해석</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>No-DB health, 3,000 RPS</td>
                    <td>MVC와 WebFlux 모두 목표 처리</td>
                    <td>런타임 자체보다 DB 경로가 먼저 병목</td>
                  </tr>
                  <tr>
                    <td>WebFlux DB list, pool 10</td>
                    <td>pending acquisition 최대 2,992</td>
                    <td>무제한 대기는 비동기의 장점이 아니라 tail latency로 전환</td>
                  </tr>
                  <tr>
                    <td>400 RPS, limit 100 → 1</td>
                    <td>p95 97.9% 감소</td>
                    <td>row mapping과 allocation이 핵심 비용임을 확인</td>
                  </tr>
                  <tr>
                    <td>median RSS</td>
                    <td>MVC 903.0MiB / WebFlux 937.2MiB</td>
                    <td>현재 구현에서 WebFlux의 메모리 우위를 확인하지 못함</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <blockquote>
              <strong>결론</strong>
              <p>
                이 결과는 “MVC가 항상 더 빠르다”는 뜻이 아닙니다. 현재 BuddyStudy의 hot list
                query에서 가져오는 행 수와 객체 매핑 비용이 런타임 선택보다 큰 변수였다는 뜻입니다.
              </p>
            </blockquote>

            <figure className="evidence-figure">
              <Image
                src="/media/load-test-dashboard.png"
                alt="MVC와 WebFlux 부하 테스트 결과 대시보드"
                width={1600}
                height={900}
                unoptimized
              />
              <figcaption>부하 실험 대시보드: 처리량, 지연, CPU, RSS, 스레드와 DB pool을 같은 시간축으로 비교</figcaption>
            </figure>
          </section>

          <hr />

          <section id="reliability">
            <SectionTitle
              eyebrow="05 · Reliability"
              title="실패가 발생하는 위치를 설계에 포함했습니다"
            />
            <h3>사용자 데이터 보호</h3>
            <ul>
              <li>예약 질문과 원격 동기화가 활성 답변 초안을 덮어쓰지 않습니다.</li>
              <li>푸시 수신은 조용히 동기화하고, 사용자의 명시적 탭에서만 화면을 이동합니다.</li>
              <li>기록 목록은 10,000건까지 고려해 페이지네이션과 lazy rendering을 사용합니다.</li>
            </ul>

            <h3>운영 복구</h3>
            <ul>
              <li>Outbox claim은 <code>SKIP LOCKED</code>를 사용해 여러 worker의 중복 경쟁을 줄입니다.</li>
              <li>Scheduler stale 판정은 lock 미획득과 실제 실행 실패를 구분합니다.</li>
              <li>Native Image 지표 수집기는 collector별로 격리해 부분 실패를 허용합니다.</li>
              <li>API 오류는 안정적인 error code를 내려주고 앱이 로그인·약관·일반 오류 흐름을 결정합니다.</li>
            </ul>
          </section>

          <hr />

          <section id="security">
            <SectionTitle
              eyebrow="06 · Security"
              title="공개 요청과 관리 트래픽을 분리했습니다"
            />
            <pre>
              <code>{`Internet
  └─ Cloudflare Tunnel → Nginx → Backend API

Private administration
  └─ Cloudflare WARP /32
       ├─ PostgreSQL
       ├─ Redis
       └─ localhost-bound Loki / Grafana → auth proxy`}</code>
            </pre>
            <ul>
              <li>런타임 비밀값은 저장소나 이미지에 포함하지 않고 AWS Secrets Manager에서 주입합니다.</li>
              <li>Authorization, client secret, token과 민감한 body 필드는 구조화 로그에서 마스킹합니다.</li>
              <li>GitHub-hosted runner가 이미지를 빌드해 GHCR에 저장하고 EC2 runner는 배포만 수행합니다.</li>
              <li>Backend, admin, monitoring, routing은 독립된 workflow와 배포 단위로 관리합니다.</li>
            </ul>
          </section>

          <hr />

          <section id="testing">
            <SectionTitle
              eyebrow="07 · Verification"
              title="레이어별 실패 모드를 따로 검증합니다"
            />
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>대상</th>
                    <th>방법</th>
                    <th>검증 범위</th>
                  </tr>
                </thead>
                <tbody>
                  {testMatrix.map(([target, method, scope]) => (
                    <tr key={target}>
                      <td><strong>{target}</strong></td>
                      <td>{method}</td>
                      <td>{scope}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p>
              성능 실험은 성공 RPS만 보지 않습니다. <code>p90/p95/p99</code>, 오류율,
              event-loop CPU, RSS, thread count, DB pool pending, PostgreSQL CPU와 allocation을
              같은 시간 구간으로 비교합니다.
            </p>
          </section>

          <hr />

          <section id="interview">
            <SectionTitle
              eyebrow="08 · Interview Notes"
              title="설계 결정을 설명하는 방법"
              description="결론보다 선택의 조건, 관찰한 데이터, 남은 한계를 함께 답할 수 있도록 정리했습니다."
            />
            <div className="qa-list">
              {interviewQuestions.map((item, index) => (
                <details key={item.question} open={index === 0}>
                  <summary>{item.question}</summary>
                  <p>{item.answer}</p>
                </details>
              ))}
            </div>
          </section>

          <footer className="document-footer">
            <Image src="/media/buddystudy-icon.png" alt="" width={32} height={32} unoptimized />
            <div>
              <strong>BuddyStudy</strong>
              <p>SwiftUI iOS · Kotlin WebFlux · PostgreSQL · Redis Streams</p>
            </div>
            <a href="https://github.com/ghkdqhrbals/buddy-studdy" target="_blank" rel="noreferrer">
              Source code ↗
            </a>
          </footer>
        </main>
      </div>
    </div>
  );
}

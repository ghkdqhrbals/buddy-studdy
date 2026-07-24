import Image from "next/image";
import { PerformanceChart } from "./PerformanceChart";
import {
  benchmarkMetadata,
  diagnosticComparison,
  ngrinderSmoke,
  studiesAt3000TimeSeries,
  studiesSweep,
  targetRps,
} from "./performance-data";

const navigation = [
  ["개요", "overview"],
  ["제품", "product"],
  ["통계 최적화", "statistics"],
  ["캐시", "caching"],
  ["비동기 메시징", "messaging"],
  ["이미지", "image"],
  ["인프라", "infrastructure"],
  ["성능", "performance"],
  ["검증", "testing"],
  ["인터뷰", "interview"],
];

const engineeringAreas = [
  ["통계", "원본 질문을 매번 전수 집계하지 않고 변경된 통계 bucket만 다시 계산"],
  ["캐시", "재생성 가능한 읽기 데이터만 캐시하고 초안·설정·원본 기록과 분리"],
  ["메시징", "PostgreSQL outbox와 Redis Streams로 dual-write 실패와 중복 전달을 통제"],
  ["이미지", "이미지 업로드 대신 조합형 아바타와 compact config로 전송·저장 비용 제거"],
  ["인프라", "공개 API, 사설 관리망, 배포, 관측 경계를 각각 독립적으로 제한"],
];

const productFlow = [
  ["1", "질문 생성", "등급·월간 quota·필수 약관을 확인하고 시스템 키로 질문을 생성합니다."],
  ["2", "답변 보존", "작성 중인 초안은 예약 질문과 원격 동기화가 덮어쓰지 못하도록 보호합니다."],
  ["3", "AI 채점", "점수와 함께 설명·피드백을 저장해 다음 복습의 입력으로 사용합니다."],
  ["4", "기록·통계", "주제·난이도별 기록을 페이지 단위로 읽고 변경된 통계만 갱신합니다."],
];

const screenshots = [
  {
    src: "/media/home-current.png",
    alt: "BuddyStudy 현재 홈 화면",
    label: "홈 · 공개 질문과 내 학습을 한 화면에서 전환",
  },
  {
    src: "/media/settings-current.png",
    alt: "BuddyStudy 현재 설정 화면",
    label: "설정 · 학습 주기, 언어, 알림과 개발자 옵션",
  },
];

const testMatrix = [
  ["iOS", "unit · generic build · real device", "초안 복구, decoding, 인증 상태, 오류 라우팅, 푸시 UX"],
  ["Backend", "unit · integration", "use case, R2DBC transaction, migration, HTTP contract"],
  ["Messaging", "outbox · consumer tests", "중복 event id, stale claim, retry, 부분 실패"],
  ["Infrastructure", "Worker · parser tests", "Tunnel 설정, timeout, health monitor, 구조화 로그"],
  ["Performance", "k6 · nGrinder · JFR/NMT", "open-loop 용량, closed-loop 지속 부하, CPU, RSS, DB pool"],
];

const interviewQuestions = [
  {
    question: "왜 WebFlux를 선택했고, 실제로 더 빨랐나요?",
    answer:
      "OpenAI·APNs·Redis·DB처럼 I/O가 이어지는 경로를 coroutine과 non-blocking client로 통일하려는 선택이었습니다. 하지만 2026-07-22 실제 API별 동일 조건 측정에서 studies는 WebFlux/R2DBC의 큰 row mapping과 DB pool 대기가 먼저 포화됐습니다. synthetic health 수치는 용량 결론에서 제외하고, public questions와 studies를 각각 측정해 선택의 의도와 현재 구현의 결과를 분리합니다.",
  },
  {
    question: "왜 Kafka가 아니라 Redis Streams인가요?",
    answer:
      "Redis가 인증 임시 상태와 운영 인프라에 이미 있었고, 현재 규모에서 필요한 기능은 append-only stream, consumer group, ACK와 replay였습니다. 새 broker 운영 비용을 추가하지 않되, PostgreSQL outbox로 dual-write를 막고 event id 기반 consumer 멱등성으로 at-least-once 중복을 흡수했습니다.",
  },
  {
    question: "통계가 커질 때 전체 데이터를 다시 읽지 않나요?",
    answer:
      "질문 원본은 source of truth로 유지하고, 화면은 user_stats materialized read model을 읽습니다. 답변·삭제가 발생하면 영향을 받은 user/date/topic/difficulty key만 dirty queue에 넣고 bounded batch로 다시 집계합니다. worker는 SKIP LOCKED로 병렬 처리하며 dirty marker의 updated_at을 비교해 동시 변경을 잃지 않습니다.",
  },
  {
    question: "R2DBC 트랜잭션은 같은 스레드에서만 동작하나요?",
    answer:
      "아닙니다. reactive transaction은 thread-local이 아니라 Reactor Context를 따라갑니다. coroutine이 다른 스레드에서 재개되어도 같은 context 안에서는 동일 트랜잭션에 참여합니다. JPA persistence context는 없으므로 dirty checking이나 lazy loading 대신 변경을 명시적으로 저장합니다.",
  },
  {
    question: "운영 보안과 배포 실패 범위를 어떻게 줄였나요?",
    answer:
      "공개 HTTP는 Cloudflare Tunnel과 Nginx만 통과시킵니다. PostgreSQL·Redis 관리 경로는 WARP /32 private route로 제한하고 PLG는 localhost에 bind합니다. GitHub-hosted runner가 이미지를 빌드하고 EC2 runner는 GHCR pull과 rollout만 수행하며, backend·admin·monitoring·routing을 별도 배포 단위로 나눴습니다.",
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
              <span>Measured</span>
            </div>
            <h1>BuddyStudy</h1>
            <p className="lead">
              짧은 질문으로 학습하고, 답변 기록을 주제별 통계로 연결하는
              <strong> iOS AI 학습 시스템</strong>입니다.
            </p>
            <p>
              이 문서는 기술 이름을 나열하지 않습니다. <strong>어떤 병목과 실패 조건이 있었고,
              현재 코드가 무엇을 보장하며, 측정 결과가 선택을 어떻게 바꿨는지</strong>를 실제
              구현과 벤치마크 수치로 설명합니다.
            </p>

            <blockquote>
              <strong>판단 기준</strong>
              <p>
                저장·전달·표시의 source of truth를 먼저 정하고, 캐시와 비동기는 실패 후
                복구 가능한 범위에만 둡니다. 성능은 프레임워크 이름이 아니라 처리량, tail
                latency, allocation, DB pool 대기를 같은 시간축에서 확인합니다.
              </p>
            </blockquote>

            <div className="quick-facts" aria-label="프로젝트 요약">
              <dl><dt>Client</dt><dd>SwiftUI / iOS</dd></dl>
              <dl><dt>Backend</dt><dd>Kotlin / WebFlux / R2DBC</dd></dl>
              <dl><dt>Data</dt><dd>PostgreSQL / Redis Streams</dd></dl>
              <dl><dt>Operations</dt><dd>Cloudflare / AWS / PLG</dd></dl>
            </div>

            <h3>기술 설계의 다섯 축</h3>
            <div className="table-wrap">
              <table>
                <tbody>
                  {engineeringAreas.map(([area, decision]) => (
                    <tr key={area}>
                      <th scope="row">{area}</th>
                      <td>{decision}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <hr />

          <section id="product">
            <SectionTitle
              eyebrow="01 · Product"
              title="질문 생성보다 학습의 연속성을 지킵니다"
              description="예약·푸시·동기화가 개입해도 작성 중인 답변과 사용자의 현재 위치를 보존합니다."
            />
            <ol className="flow-list">
              {productFlow.map(([number, title, description]) => (
                <li key={number}>
                  <span>{number}</span>
                  <div><h3>{title}</h3><p>{description}</p></div>
                </li>
              ))}
            </ol>

            <h3>현재 iOS 화면</h3>
            <p>
              아래 이미지는 <code>StudyMateiOS</code> 빌드를 iOS 26 시뮬레이터에서 직접
              실행해 캡처한 화면입니다.
            </p>
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

          <section id="statistics">
            <SectionTitle
              eyebrow="02 · Statistics"
              title="변경된 통계 bucket만 다시 계산합니다"
              description="원본 질문의 정합성을 유지하면서 읽기 비용을 쓰기 이후의 bounded recomputation으로 이동했습니다."
            />
            <pre aria-label="증분 통계 집계 흐름">
              <code>{`answer / delete transaction
  ├─ questions: source of truth
  └─ user_stats_dirty_keys
       (user, date, topic, difficulty)
             │
             ▼  bounded batch + SKIP LOCKED
       PostgreSQL bucket aggregation
             │
             ▼
       user_stats read model → statistics UI`}</code>
            </pre>
            <div className="decision-list">
              <article>
                <h3>왜 read model인가</h3>
                <p>
                  통계 화면마다 누적 질문을 앱으로 가져와 계산하면 데이터 증가가 네트워크,
                  decoding, 메모리 비용으로 그대로 이어집니다. <code>user_stats</code>는 화면이
                  필요한 주제·난이도 단위의 결과만 저장합니다.
                </p>
              </article>
              <article>
                <h3>어떻게 동시 변경을 보존하나</h3>
                <p>
                  worker는 <code>FOR UPDATE SKIP LOCKED</code>로 서로 다른 dirty key를 처리합니다.
                  집계 후 marker를 지울 때 처음 읽은 <code>updated_at</code>과 비교해, 처리 중
                  다시 발생한 답변 변경을 실수로 삭제하지 않습니다.
                </p>
              </article>
              <article>
                <h3>보장 범위</h3>
                <p>
                  dirty key 처리는 at-least-once여도 같은 bucket을 원본에서 다시 계산하므로
                  멱등입니다. 집계가 잠시 늦을 수는 있지만 원본 질문을 잃거나 누적 값을 중복
                  증가시키지 않습니다.
                </p>
              </article>
            </div>
          </section>

          <hr />

          <section id="caching">
            <SectionTitle
              eyebrow="03 · Caching"
              title="캐시는 원본이 아니라 다시 만들 수 있는 읽기 결과입니다"
              description="속도를 위해 캐시를 늘리기보다 데이터 성격별 소유권과 무효화 시점을 먼저 정했습니다."
            />
            <div className="table-wrap">
              <table>
                <thead>
                  <tr><th>데이터</th><th>저장 위치</th><th>갱신·무효화</th><th>이유</th></tr>
                </thead>
                <tbody>
                  <tr>
                    <td>활성 답변 초안</td><td>SettingsStore</td><td>채점 완료 또는 명시적 폐기</td>
                    <td>재생성할 수 없는 사용자 입력이므로 일반 캐시와 분리</td>
                  </tr>
                  <tr>
                    <td>기록 페이지·화면 모델</td><td>iOS memory cache</td><td>삭제·동기화 성공 후 해당 page 갱신</td>
                    <td>10,000건을 한 번에 렌더링하지 않고 page 단위 재사용</td>
                  </tr>
                  <tr>
                    <td>프로필·아바타 catalog</td><td>iOS local cache</td><td>프로필 저장·로그아웃·catalog version 변경</td>
                    <td>탭 이동마다 동일한 조합 데이터를 다시 받지 않음</td>
                  </tr>
                  <tr>
                    <td>이메일 인증 code</td><td>Redis TTL</td><td>만료 또는 검증 성공 시 삭제</td>
                    <td>영구 보존이 필요 없는 보안 상태를 시간으로 제한</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <blockquote>
              <strong>의도적으로 공유 캐시하지 않은 값</strong>
              <p>
                공개 질문의 <code>likedByMe</code>처럼 viewer마다 달라지는 필드는 동일 URL의
                공용 응답으로 캐시하면 사용자 간 데이터가 섞일 수 있습니다. 7초 목록 캐시를
                검토했지만 현재 구현 근거가 없으므로 적용된 기능으로 주장하지 않습니다.
              </p>
            </blockquote>
          </section>

          <hr />

          <section id="messaging">
            <SectionTitle
              eyebrow="04 · Messaging"
              title="Redis Streams를 선택한 이유와 전달 보장"
              description="이미 운영 중인 Redis를 재사용하되, DB commit과 메시지 발행을 하나의 원자 작업처럼 가장하지 않습니다."
            />
            <h3>왜 Redis Streams인가</h3>
            <ul>
              <li>Redis는 인증 임시 상태와 운영 인프라에 이미 존재해 별도 broker 운영 비용이 없습니다.</li>
              <li>현재 규모에 필요한 append-only ordering, consumer group, pending entry, ACK와 replay를 제공합니다.</li>
              <li>Kafka의 파티션·보존·클러스터 운영 복잡도보다 현재 트래픽과 팀 규모에 맞는 선택입니다.</li>
            </ul>
            <pre aria-label="PostgreSQL outbox와 Redis Stream 전달 흐름">
              <code>{`[same PostgreSQL transaction]
domain write + outbox(event_type, event_id UNIQUE)
                 │ commit
                 ▼
dispatcher: SKIP LOCKED claim + lease + exponential retry
                 │ XADD
                 ▼
Redis Stream → consumer group → handler(event_id dedupe) → ACK`}</code>
            </pre>
            <div className="table-wrap">
              <table>
                <thead><tr><th>실패 지점</th><th>현재 처리</th><th>보장</th></tr></thead>
                <tbody>
                  <tr>
                    <td>DB commit 전 장애</td><td>domain과 outbox 모두 rollback</td><td>발행할 유령 이벤트 없음</td>
                  </tr>
                  <tr>
                    <td>commit 후 XADD 전 장애</td><td>stale claim 회수 후 재시도</td><td>outbox가 발행 의도를 보존</td>
                  </tr>
                  <tr>
                    <td>XADD 후 mark 전 장애</td><td>재발행 가능, consumer가 event id dedupe</td><td>at-least-once + 멱등 소비</td>
                  </tr>
                  <tr>
                    <td>handler 후 ACK 전 장애</td><td>pending entry 재전달</td><td>부작용도 event id 기준 멱등 처리 필요</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p className="version-note">
              <strong>버전 구분:</strong> 현재 보장은 outbox unique key와 consumer 멱등성에서
              나옵니다. Redis 8.6의 <code>XADD IDMP</code>/<code>IDMPAUTO</code>는 producer
              단계의 중복도 줄일 수 있지만, 해당 버전과 client 지원을 검증한 뒤 추가할 보강책입니다.
              Redis 8.2 기능으로 잘못 표기하지 않습니다.{" "}
              <a href="https://redis.io/docs/latest/develop/data-types/streams/idempotency/" target="_blank" rel="noreferrer">
                Redis 공식 idempotency 문서 ↗
              </a>
            </p>
          </section>

          <hr />

          <section id="image">
            <SectionTitle
              eyebrow="05 · Image"
              title="이미지 전송 대신 조합 정보를 저장합니다"
              description="현재 기본 프로필은 Reddit식 아바타 builder이며, 서버가 사용자 사진 원본과 파생 이미지를 운영한다는 주장은 하지 않습니다."
            />
            <pre aria-label="조합형 아바타 렌더링 구조">
              <code>{`PostgreSQL avatar catalog
category / item key / compatibility / z-index / unlock
                         │
                         ▼ compact avatarConfig
iOS local catalog cache ── SwiftUI fixed-slot renderer
  base + background + top + bottom + shoes + hat + item`}</code>
            </pre>
            <div className="decision-list">
              <article>
                <h3>저장 비용</h3>
                <p>
                  사용자마다 합성 PNG를 저장하지 않고 item key와 색상 설정만 저장합니다.
                  새 모자·상의·신발은 catalog row와 앱 asset을 추가해 기존 프로필 schema를
                  바꾸지 않고 확장합니다.
                </p>
              </article>
              <article>
                <h3>로딩 비용</h3>
                <p>
                  앱에 포함된 asset과 SwiftUI renderer를 사용해 프로필 목록마다 원격 이미지를
                  다운로드하지 않습니다. catalog와 profile config는 로컬 캐시에서 재사용합니다.
                </p>
              </article>
              <article>
                <h3>현재 한계</h3>
                <p>
                  개인 사진 업로드, 64/256 on-the-fly resize, Nginx 파생 이미지 TTL은 설계
                  후보이며 현재 배포 기능이 아닙니다. 실제 구현과 다음 단계가 섞이지 않게
                  아바타 builder를 현재 기준으로 설명합니다.
                </p>
              </article>
            </div>
          </section>

          <hr />

          <section id="infrastructure">
            <SectionTitle
              eyebrow="06 · Infrastructure"
              title="공개 경로, 관리망, 배포와 관측을 분리했습니다"
              description="한 모듈의 변경이나 외부 노출이 전체 시스템의 실패 범위가 되지 않도록 경계를 나눴습니다."
            />
            <pre aria-label="BuddyStudy 운영 인프라">
              <code>{`Public HTTP
Client → Cloudflare Tunnel → Nginx → WebFlux API
                                      ├─ PostgreSQL / R2DBC
                                      ├─ Redis Streams
                                      └─ OpenAI / APNs

Private administration
Cloudflare WARP /32 → PostgreSQL / Redis
localhost only      → Promtail / Loki / Grafana → authenticated proxy

Delivery
GitHub-hosted build → GHCR → EC2 deploy-only runner`}</code>
            </pre>
            <div className="table-wrap">
              <table>
                <thead><tr><th>경계</th><th>구현</th><th>줄인 위험</th></tr></thead>
                <tbody>
                  <tr><td>Network</td><td>Tunnel hostname + WARP /32 + localhost bind</td><td>DB·Redis·관측 도구의 직접 공개 노출</td></tr>
                  <tr><td>Secrets</td><td>AWS Secrets Manager 주입 + 로그 redaction</td><td>저장소·이미지·API 로그의 credential 유출</td></tr>
                  <tr><td>Build</td><td>GitHub-hosted image build, EC2는 pull/rollout만</td><td>작은 운영 호스트의 build 자원 고갈</td></tr>
                  <tr><td>Deployment</td><td>backend, admin, monitoring, routing workflow 분리</td><td>무관한 모듈의 동시 장애와 rollback 확대</td></tr>
                  <tr><td>Observability</td><td>request id 구조화 로그 → Promtail → Loki → Grafana</td><td>API exchange와 관련 로그를 수동으로 연결하는 비용</td></tr>
                </tbody>
              </table>
            </div>
          </section>

          <hr />

          <section id="performance">
            <SectionTitle
              eyebrow="07 · Performance"
              title="두 런타임을 같은 조건에서 측정했습니다"
              description="아래 그래프는 샘플 UI가 아니라 2026-07-22 k6 원본 결과의 세 번 반복 중앙값과 초 단위 시계열입니다."
            />

            <div className="benchmark-meta">
              <dl><dt>Baseline</dt><dd>MVC/JDBC <code>{benchmarkMetadata.mvcRef.slice(0, 8)}</code></dd></dl>
              <dl><dt>Candidate</dt><dd>WebFlux/R2DBC <code>{benchmarkMetadata.webfluxRef.slice(0, 8)}</code></dd></dl>
              <dl><dt>Limits</dt><dd>{benchmarkMetadata.runtimeLimit}</dd></dl>
              <dl><dt>Fixture</dt><dd>{benchmarkMetadata.fixture}</dd></dl>
            </div>

            <h3>도구와 역할</h3>
            <div className="table-wrap">
              <table>
                <thead><tr><th>도구</th><th>부하 모델</th><th>판단에 사용하는 값</th></tr></thead>
                <tbody>
                  <tr>
                    <td><strong>k6</strong></td>
                    <td>constant-arrival-rate open-loop</td>
                    <td>목표 1,000~3,000 RPS의 성공 RPS, dropped iteration, p95/p99</td>
                  </tr>
                  <tr>
                    <td><strong>nGrinder {ngrinderSmoke.version}</strong></td>
                    <td>25~1,000 VUser closed-loop</td>
                    <td>동시 사용자별 지속 TPS, response time, 오류와 회복</td>
                  </tr>
                  <tr>
                    <td><strong>JFR / NMT</strong></td>
                    <td>포화점 진단 재실행에서만 활성화</td>
                    <td>allocation, GC, heap/non-heap/direct memory, thread</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <PerformanceChart
              title="Studies API p95 latency by target RPS"
              description="100 studies 조회, 세 번 반복 중앙값. WebFlux는 5초 timeout 경계에 도달했습니다."
              xValues={targetRps}
              xLabel="target requests per second"
              yLabel="p95 latency · ms · log scale"
              yTicks={[1, 10, 100, 1000, 5000]}
              scale="log"
              formatX={(value) => value.toLocaleString("en-US")}
              formatY={(value) => value >= 1000 ? `${value / 1000}s` : `${value}ms`}
              series={[
                { label: "MVC / JDBC", values: studiesSweep.mvc.p95Ms, color: "#cf222e" },
                { label: "WebFlux / R2DBC", values: studiesSweep.webflux.p95Ms, color: "#0969da" },
              ]}
            />

            <PerformanceChart
              title="Studies API successful throughput"
              description="목표 RPS와 실제 성공 응답 처리량. 회색 점선은 요청한 부하입니다."
              xValues={targetRps}
              xLabel="target requests per second"
              yLabel="successful requests per second"
              yTicks={[0, 500, 1000, 1500, 2000, 2500, 3000]}
              formatX={(value) => value.toLocaleString("en-US")}
              formatY={(value) => value.toLocaleString("en-US", { maximumFractionDigits: 1 })}
              series={[
                { label: "Target", values: targetRps, color: "#8c959f", dashed: true },
                { label: "MVC / JDBC", values: studiesSweep.mvc.successfulRps, color: "#cf222e" },
                {
                  label: "WebFlux / R2DBC",
                  values: studiesSweep.webflux.successfulRps,
                  color: "#0969da",
                  showValues: true,
                },
              ]}
            />

            <PerformanceChart
              title="3,000 RPS run: p95 over measurement time"
              description="동일 studies 시나리오의 round 2 초 단위 원본. 대기열이 tail latency로 변하는 과정을 보여줍니다."
              xValues={studiesAt3000TimeSeries.seconds}
              xLabel="measurement second"
              yLabel="p95 latency · ms · log scale"
              yTicks={[100, 500, 1000, 2000, 5000]}
              scale="log"
              formatY={(value) => value >= 1000 ? `${value / 1000}s` : `${value}ms`}
              series={[
                { label: "MVC / JDBC", values: studiesAt3000TimeSeries.mvcP95Ms, color: "#cf222e" },
                { label: "WebFlux / R2DBC", values: studiesAt3000TimeSeries.webfluxP95Ms, color: "#0969da" },
              ]}
            />

            <h3>원시 중앙값</h3>
            <div className="table-wrap compact-table">
              <table>
                <thead>
                  <tr><th>Target</th><th>MVC success</th><th>MVC p95</th><th>WebFlux success</th><th>WebFlux p95</th><th>WebFlux error</th></tr>
                </thead>
                <tbody>
                  {targetRps.map((target, index) => (
                    <tr key={target}>
                      <td>{target.toLocaleString("en-US")} RPS</td>
                      <td>{studiesSweep.mvc.successfulRps[index].toFixed(1)}</td>
                      <td>{studiesSweep.mvc.p95Ms[index].toFixed(2)}ms</td>
                      <td>{studiesSweep.webflux.successfulRps[index].toFixed(1)}</td>
                      <td>{studiesSweep.webflux.p95Ms[index].toFixed(2)}ms</td>
                      <td>{studiesSweep.webflux.failedPercent[index].toFixed(3)}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <h3>병목을 분리한 진단 실험</h3>
            <div className="metric-strip">
              <div><strong>{diagnosticComparison.listLimit100.p95Ms} → {diagnosticComparison.listLimit1.p95Ms}ms</strong><span>400 RPS p95</span></div>
              <div><strong>-73.2%</strong><span>allocation rate</span></div>
              <div><strong>{diagnosticComparison.listLimit100.dbPoolPending} → {diagnosticComparison.listLimit1.dbPoolPending}</strong><span>DB pool pending</span></div>
            </div>
            <p>
              같은 WebFlux/R2DBC 경로에서 조회 행 수만 100에서 1로 줄이자 p95가 97.9%,
              allocation이 73.2% 감소했습니다. 이 실험은 병목이 “reactive가 느리다”가 아니라
              큰 결과를 만드는 query와 row mapping, pool 뒤의 무제한 대기라는 점을 분리합니다.
            </p>

            <blockquote>
              <strong>nGrinder 결과 해석 범위</strong>
              <p>
                현재 보존된 {ngrinderSmoke.version} 결과는 1 VUser smoke입니다. MVC{" "}
                {ngrinderSmoke.mvc.tps} TPS, WebFlux {ngrinderSmoke.webflux.tps} TPS였지만
                controller·agent·등록·실행·수집 자동화 확인용이므로 용량 결론에는 사용하지
                않습니다. 표준 프로필은 25·50·100·200·400·600·800·1,000 동시
                사용자로 구성했으며, 이 반복 실행 전까지 smoke를 k6 결과와 동급 근거로
                포장하지 않습니다.
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
              <figcaption>수집 대시보드: RPS·지연과 함께 CPU, RSS, thread, DB pool, PostgreSQL 지표를 같은 시간축으로 비교</figcaption>
            </figure>
          </section>

          <hr />

          <section id="testing">
            <SectionTitle
              eyebrow="08 · Verification"
              title="성공 응답뿐 아니라 실패 경계를 검증합니다"
            />
            <div className="table-wrap">
              <table>
                <thead><tr><th>대상</th><th>방법</th><th>검증 범위</th></tr></thead>
                <tbody>
                  {testMatrix.map(([target, method, scope]) => (
                    <tr key={target}><td><strong>{target}</strong></td><td>{method}</td><td>{scope}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p>
              성능 실행은 Git SHA, JDK, 도구 버전, 머신 제한, DB pool과 fixture를 결과에
              함께 기록합니다. 부하 발생기 CPU p95가 80%를 넘거나 네트워크·메모리 압력이
              생긴 실행은 서버 성능 결과에서 제외합니다.
            </p>
          </section>

          <hr />

          <section id="interview">
            <SectionTitle
              eyebrow="09 · Interview Notes"
              title="선택, 증거, 한계를 함께 설명합니다"
              description="정답처럼 말하기보다 당시 조건과 측정된 반례까지 답할 수 있게 정리했습니다."
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

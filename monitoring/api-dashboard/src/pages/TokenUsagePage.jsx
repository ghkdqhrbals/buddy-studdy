import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { PageHeader, DataTable, DetailDrawer, Pagination } from "../components/AdminUI.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";
import { formatDateTime } from "../lib/format.js";
import { loadUsage, summarizeUsage, groupUsage, usageTrend, TOKEN_FIELDS } from "../lib/tokenUsage.js";

const fmt = (n) => n === null || n === undefined ? "—" : n.toLocaleString("ko-KR");
const fieldLabel = { inputTokens: "입력 토큰", outputTokens: "출력 토큰", totalTokens: "전체 토큰", cachedInputTokens: "캐시 입력", inputTextTokens: "텍스트 입력", inputAudioTokens: "음성 입력", outputTextTokens: "텍스트 출력", outputAudioTokens: "음성 출력", reasoningTokens: "추론 토큰", cachedTextTokens: "캐시 텍스트", cachedAudioTokens: "캐시 음성", inputImageTokens: "이미지 입력", outputImageTokens: "이미지 출력", cachedImageTokens: "캐시 이미지" };
const operationLabel = { realtime: "실시간 대화", question: "질문 생성", grading: "채점", curriculum: "커리큘럼", voice_summary: "대화 요약", voice_input_assessment: "입력 확인", voice_preview: "음성 미리듣기", translation: "번역", embedding: "임베딩", validation: "검증" };
const outcomeLabel = { succeeded: "완료", failed: "실패", cancelled: "취소", incomplete: "미완료", disconnected: "연결 끊김" };
function Count({ value }) { return <span title={`${value.known}건 보고됨 · ${value.missing}건 미보고`}>{fmt(value.value)}{value.missing > 0 && value.known > 0 ? " *" : ""}</span>; }
function Pager({ count, page, setPage, label }) {
  const pages = Math.max(1, Math.ceil(count / 25));
  return <Pagination page={page} totalPages={pages} label={`${label} ${fmt(count)}건 · ${page} / ${pages}`} onPrevious={() => setPage(Math.max(1, page - 1))} onNext={() => setPage(Math.min(pages, page + 1))} />;
}

export function TokenUsagePage() {
  const [range, setRange] = useState("86400000");
  const [model, setModel] = useState("");
  const [operation, setOperation] = useState("");
  const [granularity, setGranularity] = useState("");
  const [outcome, setOutcome] = useState("");
  const [page, setPage] = useState(1), [groupPage, setGroupPage] = useState(1);
  const [selected, setSelected] = useState(null);
  const query = useQuery({ queryKey: ["token-usage", range], queryFn: ({ signal }) => loadUsage(range, signal) });
  const source = query.data?.rows || [];
  const rows = useMemo(() => source.filter((r) => (!model || r.model === model) && (!operation || r.operation === operation) && (!granularity || r.granularity === granularity) && (!outcome || r.outcome === outcome)), [query.data, model, operation, granularity, outcome]);
  const summary = useMemo(() => summarizeUsage(rows), [rows]);
  const groups = useMemo(() => groupUsage(rows), [rows]);
  const trend = useMemo(() => query.data ? usageTrend(rows, query.data.start, query.data.end) : [], [rows, query.data]);
  const peak = Math.max(1, ...trend.map((b) => (b.input || 0) + (b.output || 0)));
  const safePage = Math.min(page, Math.max(1, Math.ceil(rows.length / 25)));
  const safeGroupPage = Math.min(groupPage, Math.max(1, Math.ceil(groups.length / 25)));
  function update(setter, value) { setter(value); setPage(1); setGroupPage(1); setSelected(null); }
  const filters = [
    ["모델", model, setModel, "model", null], ["작업", operation, setOperation, "operation", operationLabel],
    ["집계 단위", granularity, setGranularity, "granularity", { provider_response: "모델 응답", physical_attempt: "실제 요청 시도", logical: "논리 작업" }],
    ["결과", outcome, setOutcome, "outcome", outcomeLabel],
  ];
  const columns = [
    { key: "timestampMs", label: "시간", render: (r) => formatDateTime(r.timestampMs) },
    { key: "model", label: "모델" }, { key: "operation", label: "작업 / 단계", render: (r) => `${operationLabel[r.operation] || r.operation} / ${r.stage}` },
    { key: "inputTokens", label: "입력", render: (r) => fmt(r.inputTokens) }, { key: "outputTokens", label: "출력", render: (r) => fmt(r.outputTokens) },
    { key: "cachedInputTokens", label: "캐시 입력", render: (r) => fmt(r.cachedInputTokens) },
    { key: "outcome", label: "결과", render: (r) => outcomeLabel[r.outcome] || r.outcome },
  ];
  const groupColumns = [
    { key: "model", label: "모델" }, { key: "operation", label: "작업", render: (r) => operationLabel[r.operation] || r.operation },
    { key: "stage", label: "단계" }, { key: "granularity", label: "집계 단위" }, { key: "count", label: "기록 수" },
    ...["inputTokens", "outputTokens", "cachedInputTokens"].map((key) => ({ key, label: fieldLabel[key], render: (r) => <Count value={r[key]} /> })),
  ];
  return <div className="token-usage-page">
    <PageHeader eyebrow="Observe / AI" title="토큰 사용량" description="실시간 대화부터 질문 생성·채점까지, 모델이 보고한 사용량을 확인합니다." actions={<Button variant="secondary" icon={RefreshCw} busy={query.isFetching} onClick={() => query.refetch()}>새로고침</Button>} />
    <section className="workspace-section token-filters" aria-label="토큰 사용량 필터">
      <label className="field compact-field"><span>조회 기간</span><select value={range} onChange={(e) => update(setRange, e.target.value)}><option value="3600000">최근 1시간</option><option value="21600000">최근 6시간</option><option value="86400000">최근 24시간</option><option value="604800000">최근 7일</option></select></label>
      {filters.map(([name, value, setter, key, labels]) => <label key={key} className="field compact-field"><span>{name}</span><select value={value} onChange={(e) => update(setter, e.target.value)}><option value="">전체</option>{[...new Set(source.map((r) => r[key]))].sort().map((v) => <option key={v} value={v}>{labels?.[v] || v}</option>)}</select></label>)}
    </section>
    {query.error && <InlineNotice tone="danger">{query.error.message}{query.data ? " 아래는 마지막으로 성공한 조회 결과입니다." : ""}</InlineNotice>}
    {query.data?.truncated && <InlineNotice>조회 한도에 도달해 최신 2,000개 로그만 표시합니다. 아래 합계와 그래프는 전체 기간의 총사용량이 아닙니다. 기간을 줄여 확인하세요.</InlineNotice>}
    {query.data?.invalid > 0 && <InlineNotice>{query.data.invalid}개의 해석할 수 없는 로그가 집계에서 제외되었습니다.</InlineNotice>}
    <div className="token-cards" aria-label="토큰 사용량 요약">
      {["inputTokens", "outputTokens", "cachedInputTokens"].map((key) => <article className="token-card" key={key}><span>{fieldLabel[key]}</span><strong>{query.isLoading ? "…" : <Count value={summary[key]} />}</strong><small>{key === "cachedInputTokens" ? "입력 토큰에 포함된 수량" : `${fmt(summary[key].known)}건의 보고값 합계`}</small></article>)}
      <article className="token-card"><span>사용량 미보고</span><strong>{query.isLoading ? "…" : `${fmt(summary.missing)} / ${fmt(summary.count)}`}</strong><small>입력 또는 출력 값이 없는 기록</small></article>
    </div>
    <section className="workspace-section token-trend">
      <div className="token-section-heading"><h2>시간별 사용량</h2><span>입력 <i className="token-input-key" /> 출력 <i className="token-output-key" /></span></div>
      <div className="token-chart" role="img" aria-label="현재 조회 결과의 입력·출력 토큰 추이. 막대에 마우스를 올리면 수량을 볼 수 있습니다.">
        {trend.map((b) => <div className="token-bar-slot" key={b.start} title={`${formatDateTime(b.start)} · ${b.count}건 · 입력 ${fmt(b.input)} · 출력 ${fmt(b.output)}`}><div className="token-bar-output" style={{ height: `${(b.output || 0) / peak * 100}%` }} /><div className="token-bar-input" style={{ height: `${(b.input || 0) / peak * 100}%` }} /></div>)}
        {!rows.length && <span className="token-chart-empty">{query.isLoading ? "사용량을 불러오는 중" : "이 조건에 해당하는 토큰 기록이 없습니다"}</span>}
      </div>
      <div className="token-chart-axis"><span>{query.data ? formatDateTime(query.data.start) : ""}</span><span>{query.data ? formatDateTime(query.data.end) : ""}</span></div>
    </section>
    <section className="workspace-section"><h2>텍스트 · 음성</h2><div className="token-modalities">{["inputTextTokens", "outputTextTokens", "inputAudioTokens", "outputAudioTokens"].map((key) => <div key={key}><span>{fieldLabel[key]}</span><strong><Count value={summary[key]} /></strong></div>)}</div><p className="token-note">—는 미보고이며 0이 아닙니다. *는 일부 기록의 합계입니다. 세부 토큰은 입력·출력의 부분값이므로 다시 더하지 않습니다.</p></section>
    <section className="workspace-section"><div className="token-section-heading"><h2>모델 · 작업별 사용량</h2><span>입력 + 출력이 많은 순서</span></div><DataTable columns={groupColumns} rows={groups.slice((safeGroupPage - 1) * 25, safeGroupPage * 25)} rowKey={(r) => r.id} loading={query.isLoading} emptyText="모델이 사용량을 보고하면 작업별 기록이 표시됩니다." /><Pager count={groups.length} page={safeGroupPage} setPage={setGroupPage} label="작업 그룹" /></section>
    <section className="workspace-section"><h2>응답 · 요청 기록</h2><p className="token-note">행을 선택하면 캐시·음성·추론 토큰과 재시도 정보를 확인할 수 있습니다. 취소·실패 응답의 보고된 사용량도 포함합니다.</p><DataTable columns={columns} rows={rows.slice((safePage - 1) * 25, safePage * 25)} rowKey={(r) => r.id} onRowClick={setSelected} loading={query.isLoading} emptyText="로컬 앱에서 대화하거나 질문을 생성하면 실제 사용 기록이 표시됩니다." /><Pager count={rows.length} page={safePage} setPage={setPage} label="사용 기록" /></section>
    <aside className="workspace-section token-context"><h2>MCP와 토큰의 관계</h2><p>MCP 함수의 실행 시간은 <a href="/performance.html">API Performance</a>에서 확인합니다. 이 화면은 모델 응답과 요청의 사용량을 보여줍니다. 현재 로그에는 두 기록을 정확히 연결할 식별자가 없어 함수별 토큰 비용과 인자·결과의 토큰 길이는 표시하지 않습니다.</p><p>금액은 계산하지 않습니다. 집계 단위가 서로 다른 기록의 합계를 청구 총액으로 해석하지 마세요. 캐시 적중률은 입력·캐시 값이 모두 보고된 {fmt(summary.cacheCoverage)}건 기준 {summary.cacheRate === null ? "미확인" : `${(summary.cacheRate * 100).toFixed(1)}%`}입니다.</p></aside>
    <DetailDrawer open={Boolean(selected)} title="토큰 사용 상세" subtitle={selected ? `${selected.model} · ${formatDateTime(selected.timestampMs)}` : ""} onClose={() => setSelected(null)}>{selected && <><dl className="token-detail">{[["작업", operationLabel[selected.operation] || selected.operation], ["단계", selected.stage], ["전송", selected.transport], ["집계 단위", selected.granularity], ["결과", outcomeLabel[selected.outcome] || selected.outcome], ["소요 시간", selected.durationMs === null ? "미보고" : `${fmt(selected.durationMs)} ms`], ["시도", fmt(selected.attempt)], ["재시도", fmt(selected.retryCount)], ...TOKEN_FIELDS.map((key) => [fieldLabel[key], fmt(selected[key])]), ["음성 길이", selected.audioSeconds === null ? "미보고" : `${selected.audioSeconds} 초`], ["사용량 기록 ID", selected.eventRef]].map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl><p className="token-note">제공자가 보고하지 않은 값은 —로 표시합니다.</p></>}</DetailDrawer>
  </div>;
}

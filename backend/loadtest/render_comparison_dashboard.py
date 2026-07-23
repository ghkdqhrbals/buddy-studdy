#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BuddyStudy Performance Lab</title>
<style>
:root { color-scheme: light; --ink:#172033; --muted:#667085; --line:#e3e8ef;
  --surface:#fff; --soft:#f6f8fb; --nav:#111827; --blue:#2563eb; --green:#059669;
  --red:#dc2626; }
* { box-sizing:border-box; }
body { margin:0; color:var(--ink); background:var(--soft);
  font:14px/1.45 Inter,ui-sans-serif,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; }
nav { position:sticky; top:0; z-index:3; display:flex; align-items:center; gap:28px;
  height:58px; padding:0 28px; background:var(--nav); color:#fff; }
nav strong { font-size:16px; } nav span { color:#aab4c4; font-size:12px; }
main { max-width:1500px; margin:0 auto; padding:24px 28px 48px; }
.toolbar { display:grid; grid-template-columns:repeat(5,minmax(130px,1fr)); gap:12px;
  padding:14px 0 20px; border-bottom:1px solid var(--line); }
label { display:block; color:var(--muted); font-size:11px; font-weight:700; text-transform:uppercase; }
select { width:100%; margin-top:5px; padding:9px 10px; border:1px solid #ccd5e2;
  border-radius:6px; background:#fff; color:var(--ink); }
.cards { display:grid; grid-template-columns:repeat(5,minmax(120px,1fr)); gap:1px;
  margin:22px 0; border:1px solid var(--line); background:var(--line); }
.card { padding:16px; background:#fff; min-height:86px; }
.card b { display:block; margin-top:8px; font-size:22px; }
.panel { margin-top:16px; border:1px solid var(--line); background:#fff; }
.panel-head { display:flex; justify-content:space-between; align-items:center;
  padding:13px 16px; border-bottom:1px solid var(--line); }
.panel-head h2 { margin:0; font-size:14px; } .panel-head small { color:var(--muted); }
.panel-head select { width:auto; min-width:150px; margin:0; padding:6px 30px 6px 9px; }
.chart-wrap { height:330px; padding:16px; } canvas { width:100%; height:100%; }
.table-wrap { overflow:auto; max-height:620px; }
table { width:100%; border-collapse:collapse; white-space:nowrap; font-size:12px; }
th { position:sticky; top:0; z-index:1; padding:10px 12px; background:#f8fafc;
  color:var(--muted); text-align:left; font-size:10px; text-transform:uppercase; }
td { padding:10px 12px; border-top:1px solid var(--line); font-variant-numeric:tabular-nums; }
tr:hover td { background:#f8fbff; } .mvc { color:var(--blue); font-weight:700; }
.webflux { color:var(--green); font-weight:700; }
.invalid,.bad { color:var(--red); font-weight:700; } .good { color:var(--green); font-weight:700; }
.empty { padding:40px; text-align:center; color:var(--muted); }
@media (max-width:800px) { main { padding:16px; } .toolbar { grid-template-columns:1fr 1fr; }
  .cards { grid-template-columns:1fr 1fr; } nav span { display:none; } }
</style>
</head>
<body>
<nav><strong>BuddyStudy Performance Lab</strong><span>MVC/JDBC and WebFlux/R2DBC</span></nav>
<main>
  <section class="toolbar">
    <label>Tool<select id="tool"></select></label>
    <label>Scenario<select id="scenario"></select></label>
    <label>Round<select id="round"></select></label>
    <label>Load<select id="load"></select></label>
    <label>Metric<select id="metric">
      <option value="successRps">Successful RPS</option>
      <option value="p95Ms">p95 latency</option>
      <option value="meanMs">Mean latency</option>
      <option value="appCpuP95">App CPU p95</option>
      <option value="databaseCpuP95">Database CPU p95</option>
      <option value="appRssPeakBytes">App RSS peak</option>
    </select></label>
  </section>
  <section class="cards" id="cards"></section>
  <section class="panel">
    <header class="panel-head"><h2>Runtime comparison</h2><small id="chartCaption"></small></header>
    <div class="chart-wrap"><canvas id="chart"></canvas></div>
  </section>
  <section class="panel">
    <header class="panel-head">
      <div><h2>Selected run time series</h2><small id="seriesCaption"></small></div>
      <select id="seriesMetric" aria-label="Time series metric">
        <option value="rps">Requests per second</option>
        <option value="latency">Latency</option>
      </select>
    </header>
    <div class="chart-wrap"><canvas id="seriesChart"></canvas></div>
  </section>
  <section class="panel">
    <header class="panel-head"><h2>Measured runs</h2><small>invalid generator runs are excluded from conclusions</small></header>
    <div class="table-wrap"><table>
      <thead><tr><th>Tool</th><th>Runtime</th><th>Round</th><th>Scenario</th><th>Load</th>
        <th>Success RPS</th><th>Error</th><th>p95 / mean</th><th>App CPU p95</th>
        <th>RSS peak</th><th>DB CPU p95</th><th>DB waits</th><th>Generator CPU p95</th>
        <th>Validity</th></tr></thead><tbody id="rows"></tbody>
    </table></div>
  </section>
</main>
<script>
const data = __DATA__;
const runs = data.runs;
const $ = id => document.getElementById(id);
const unique = values => [...new Set(values)].sort((a,b)=>String(a).localeCompare(String(b),undefined,{numeric:true}));
const fmt = (value,d=2) => value == null ? "-" : Number(value).toFixed(d);
const mib = value => value == null ? "-" : fmt(value/1048576,1);
function fill(id, values) {
  $(id).innerHTML = values.map(value=>`<option value="${value}">${value}</option>`).join("");
}
fill("tool", unique(runs.map(x=>x.tool)));
fill("scenario", unique(runs.map(x=>x.scenario)));
function refreshRounds() {
  const tool=$("tool").value, scenario=$("scenario").value;
  const values=unique(runs.filter(x=>x.tool===tool&&x.scenario===scenario).map(x=>x.round));
  const previous=$("round").value;
  fill("round",values);
  if(values.map(String).includes(previous)) $("round").value=previous;
}
function refreshLoads() {
  const tool = $("tool").value;
  const scenario = $("scenario").value;
  const round=Number($("round").value);
  const values = unique(runs.filter(x=>x.tool===tool&&x.scenario===scenario&&x.round===round)
    .map(x=>x.load.value));
  const previous = $("load").value;
  fill("load", ["all",...values]);
  if (values.map(String).includes(previous)) $("load").value=previous;
}
function filtered() {
  const tool=$("tool").value, scenario=$("scenario").value, load=$("load").value;
  const round=Number($("round").value);
  return runs.filter(x=>x.tool===tool&&x.scenario===scenario&&x.round===round&&
    (load==="all"||String(x.load.value)===load));
}
function valueOf(run, metric) {
  return run.summary[metric] ?? run.resources[metric] ?? null;
}
function renderCards(rows) {
  const valid=rows.filter(x=>x.validity.valid);
  const values=key=>valid.map(x=>valueOf(x,key)).filter(x=>x!=null);
  const avg=items=>items.length?items.reduce((a,b)=>a+b,0)/items.length:null;
  const cards=[
    ["Valid runs",`${valid.length} / ${rows.length}`],
    ["Successful RPS",fmt(avg(values("successRps")))],
    [$("tool").value==="k6"?"p95 latency":"Mean latency",
      `${fmt(avg(values($("tool").value==="k6"?"p95Ms":"meanMs")))} ms`],
    ["App CPU p95",`${fmt(avg(values("appCpuP95")),1)}%`],
    ["Invalid generator runs",String(rows.length-valid.length)]
  ];
  $("cards").innerHTML=cards.map(([label,value])=>`<div class="card"><label>${label}</label><b>${value}</b></div>`).join("");
}
function renderRows(rows) {
  $("rows").innerHTML=rows.map(run=>{
    const latency=run.tool==="k6"?run.summary.p95Ms:run.summary.meanMs;
    return `<tr><td>${run.tool}</td><td class="${run.runtime}">${run.runtime}</td>
      <td>${run.round}</td><td>${run.scenario}</td><td>${run.load.value} ${run.load.type}</td>
      <td>${fmt(run.summary.successRps)}</td><td>${fmt(run.summary.failureRate*100,3)}%</td>
      <td>${fmt(latency)} ms</td><td>${fmt(run.resources.appCpuP95,1)}%</td>
      <td>${mib(run.resources.appRssPeakBytes)} MiB</td><td>${fmt(run.resources.databaseCpuP95,1)}%</td>
      <td>${fmt(run.resources.databaseWaitingPeak,0)}</td><td>${fmt(run.generator.hostCpuP95,1)}%</td>
      <td class="${run.validity.valid?"good":"invalid"}">${run.validity.valid?"valid":"invalid"}</td></tr>`;
  }).join("") || `<tr><td colspan="14" class="empty">No matching runs</td></tr>`;
}
function renderChart(rows) {
  const canvas=$("chart"), dpr=devicePixelRatio||1, box=canvas.getBoundingClientRect();
  canvas.width=Math.max(1,box.width*dpr); canvas.height=Math.max(1,box.height*dpr);
  const ctx=canvas.getContext("2d"); ctx.scale(dpr,dpr);
  const width=box.width,height=box.height,pad={l:58,r:18,t:14,b:38};
  ctx.clearRect(0,0,width,height);
  const metric=$("metric").value;
  const byLoad=unique(rows.map(x=>x.load.value));
  const series=["mvc","webflux"].map(runtime=>({
    runtime,color:runtime==="mvc"?"#2563eb":"#059669",
    points:byLoad.map(load=>{
      const values=rows.filter(x=>x.runtime===runtime&&x.load.value===load&&x.validity.valid)
        .map(x=>valueOf(x,metric)).filter(x=>x!=null);
      return {load,value:values.length?values.sort((a,b)=>a-b)[Math.floor(values.length/2)]:null};
    })
  }));
  const all=series.flatMap(s=>s.points.map(p=>p.value)).filter(x=>x!=null);
  if (!all.length) { ctx.fillStyle="#667085";ctx.textAlign="center";ctx.fillText("No metric data",width/2,height/2);return; }
  const max=Math.max(...all)*1.12||1,min=Math.min(0,...all);
  const x=i=>pad.l+(byLoad.length===1?(width-pad.l-pad.r)/2:i*(width-pad.l-pad.r)/(byLoad.length-1));
  const y=v=>pad.t+(max-v)*(height-pad.t-pad.b)/(max-min);
  ctx.strokeStyle="#e3e8ef";ctx.fillStyle="#667085";ctx.font="11px sans-serif";ctx.textAlign="right";
  for(let i=0;i<=4;i++){const value=max*i/4, yy=y(value);ctx.beginPath();ctx.moveTo(pad.l,yy);ctx.lineTo(width-pad.r,yy);ctx.stroke();ctx.fillText(fmt(value,1),pad.l-8,yy+4);}
  ctx.textAlign="center";byLoad.forEach((load,i)=>ctx.fillText(load,x(i),height-12));
  series.forEach(s=>{ctx.strokeStyle=s.color;ctx.fillStyle=s.color;ctx.lineWidth=2;ctx.beginPath();
    s.points.forEach((p,i)=>{if(p.value==null)return;const xx=x(i),yy=y(p.value);if(i===0)ctx.moveTo(xx,yy);else ctx.lineTo(xx,yy);});
    ctx.stroke();s.points.forEach((p,i)=>{if(p.value==null)return;ctx.beginPath();ctx.arc(x(i),y(p.value),4,0,Math.PI*2);ctx.fill();});
  });
  $("chartCaption").textContent=`${metric} · blue MVC · green WebFlux`;
}
function renderTimeSeries(rows) {
  const canvas=$("seriesChart"),dpr=devicePixelRatio||1,box=canvas.getBoundingClientRect();
  canvas.width=Math.max(1,box.width*dpr);canvas.height=Math.max(1,box.height*dpr);
  const ctx=canvas.getContext("2d");ctx.scale(dpr,dpr);
  const width=box.width,height=box.height,pad={l:58,r:18,t:14,b:38};
  ctx.clearRect(0,0,width,height);
  if($("load").value==="all"){
    ctx.fillStyle="#667085";ctx.textAlign="center";
    ctx.fillText("Choose a specific load to inspect its time series",width/2,height/2);
    $("seriesCaption").textContent="per-second samples from the selected run";
    return;
  }
  const metric=$("seriesMetric").value;
  const series=rows.filter(x=>Array.isArray(x.series)&&x.series.length).map(run=>({
    runtime:run.runtime,color:run.runtime==="mvc"?"#2563eb":"#059669",
    points:run.series.map(point=>({
      x:Number(point.elapsedSeconds||0),
      y:metric==="rps"?Number(point.rps||0):
        Number(run.tool==="k6"?(point.p95Ms||0):(point.meanMs||0))
    }))
  }));
  const all=series.flatMap(item=>item.points.map(point=>point.y)).filter(Number.isFinite);
  if(!all.length){ctx.fillStyle="#667085";ctx.textAlign="center";ctx.fillText("No time-series data",width/2,height/2);return;}
  const maxX=Math.max(1,...series.flatMap(item=>item.points.map(point=>point.x)));
  const maxY=Math.max(1,...all)*1.12;
  const x=value=>pad.l+value*(width-pad.l-pad.r)/maxX;
  const y=value=>pad.t+(maxY-value)*(height-pad.t-pad.b)/maxY;
  ctx.strokeStyle="#e3e8ef";ctx.fillStyle="#667085";ctx.font="11px sans-serif";ctx.textAlign="right";
  for(let i=0;i<=4;i++){const value=maxY*i/4,yy=y(value);ctx.beginPath();ctx.moveTo(pad.l,yy);ctx.lineTo(width-pad.r,yy);ctx.stroke();ctx.fillText(fmt(value,1),pad.l-8,yy+4);}
  ctx.textAlign="center";for(let i=0;i<=4;i++){const value=maxX*i/4;ctx.fillText(`${fmt(value,0)}s`,x(value),height-12);}
  series.forEach(item=>{ctx.strokeStyle=item.color;ctx.lineWidth=2;ctx.beginPath();
    item.points.forEach((point,index)=>{if(index===0)ctx.moveTo(x(point.x),y(point.y));else ctx.lineTo(x(point.x),y(point.y));});
    ctx.stroke();
  });
  $("seriesCaption").textContent=metric==="rps"?
    "RPS · blue MVC · green WebFlux":"k6 p95 / nGrinder mean latency (ms)";
}
function render(){const rows=filtered();renderCards(rows);renderRows(rows);renderChart(rows);renderTimeSeries(rows);}
$("tool").addEventListener("change",()=>{refreshRounds();refreshLoads();render();});
$("scenario").addEventListener("change",()=>{refreshRounds();refreshLoads();render();});
$("round").addEventListener("change",()=>{refreshLoads();render();});
$("load").addEventListener("change",render);$("metric").addEventListener("change",render);
$("seriesMetric").addEventListener("change",render);
window.addEventListener("resize",render);
refreshRounds();refreshLoads();render();
</script>
</body></html>
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir", type=Path)
    args = parser.parse_args()
    data = json.loads((args.results_dir / "normalized-results.json").read_text())
    for run in data["runs"]:
        path = args.results_dir / run.get("timeseries", "")
        try:
            run["series"] = json.loads(path.read_text()) if path.is_file() else []
        except (OSError, ValueError):
            run["series"] = []
    safe_data = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    (args.results_dir / "DASHBOARD.html").write_text(
        HTML.replace("__DATA__", safe_data)
    )


if __name__ == "__main__":
    main()

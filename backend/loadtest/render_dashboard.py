#!/usr/bin/env python3
import argparse
import json
import statistics
from datetime import datetime
from pathlib import Path


RUNTIMES = ("mvc", "webflux")
POINT_METRICS = (
    "rps", "p50Ms", "p90Ms", "p95Ms", "p99Ms", "failureRate", "dropped", "vus"
)


def metric(summary, name, key):
    return float(summary.get("metrics", {}).get(name, {}).get("values", {}).get(key, 0.0))


def median_points(runs):
    cleaned = []
    for run in runs:
        complete = [point for point in run if not point.get("partial")]
        cleaned.append(complete or run)
    length = min((len(run) for run in cleaned), default=0)
    points = []
    for index in range(length):
        point = {"elapsedSeconds": index}
        for key in POINT_METRICS:
            point[key] = statistics.median(float(run[index].get(key, 0.0)) for run in cleaned)
        point["successRps"] = statistics.median(
            float(run[index].get("rps", 0.0)) * (1.0 - float(run[index].get("failureRate", 0.0)))
            for run in cleaned
        )
        points.append(point)
    return points


def nested_value(sample, section, key, scale=1.0):
    value = sample.get(section, {}).get(key)
    return float(value) * scale if isinstance(value, (int, float)) else None


def median_telemetry(runs):
    length = min((len(run) for run in runs), default=0)
    points = []
    for index in range(length):
        rows = [run[index] for run in runs]
        fields = {
            "cpuPercent": [nested_value(row, "process", "cpu_percent") for row in rows],
            "rssMiB": [nested_value(row, "process", "rss_bytes", 1 / 1024**2) for row in rows],
            "osThreads": [nested_value(row, "process", "os_threads") for row in rows],
            "jvmThreads": [nested_value(row, "actuator", "jvm.threads.live") for row in rows],
            "heapMiB": [nested_value(row, "actuator", "jvm.heap.used", 1 / 1024**2) for row in rows],
        }
        elapsed = []
        for run, row in zip(runs, rows):
            try:
                elapsed.append((datetime.fromisoformat(row["timestamp"]) - datetime.fromisoformat(run[0]["timestamp"])).total_seconds())
            except (KeyError, ValueError):
                pass
        point = {"elapsedSeconds": statistics.median(elapsed) if elapsed else index}
        for key, values in fields.items():
            present = [value for value in values if value is not None]
            point[key] = statistics.median(present) if present else 0.0
        points.append(point)
    return points


def read_json(path):
    with path.open() as handle:
        return json.load(handle)


def load_data(results_dir, rounds, rates, scenarios):
    data = {scenario: {} for scenario in scenarios}
    for scenario in scenarios:
        for rate in rates:
            data[scenario][str(rate)] = {}
            for runtime in RUNTIMES:
                request_runs = []
                telemetry_runs = []
                summaries = []
                for round_number in range(1, rounds + 1):
                    stage = f"{runtime}-round{round_number}-{scenario}-rps{rate}"
                    request_runs.append(read_json(results_dir / "timeseries" / f"{stage}.json"))
                    telemetry_path = results_dir / "telemetry" / f"{stage}.jsonl"
                    telemetry_runs.append(
                        [json.loads(line) for line in telemetry_path.read_text().splitlines() if line.strip()]
                    )
                    summaries.append(read_json(results_dir / "raw" / f"{stage}.json"))
                achieved_runs = [metric(row, "http_reqs", "rate") for row in summaries]
                failure_runs = [metric(row, "http_req_failed", "rate") for row in summaries]
                data[scenario][str(rate)][runtime] = {
                    "summary": {
                        "achievedRps": statistics.median(achieved_runs),
                        "successRps": statistics.median(
                            achieved * (1.0 - failed)
                            for achieved, failed in zip(achieved_runs, failure_runs)
                        ),
                        "p90Ms": statistics.median(metric(row, "http_req_duration", "p(90)") for row in summaries),
                        "p95Ms": statistics.median(metric(row, "http_req_duration", "p(95)") for row in summaries),
                        "p99Ms": statistics.median(metric(row, "http_req_duration", "p(99)") for row in summaries),
                        "failureRate": statistics.median(metric(row, "http_req_failed", "rate") for row in summaries),
                        "dropped": statistics.median(metric(row, "dropped_iterations", "count") for row in summaries),
                    },
                    "requests": median_points(request_runs),
                    "telemetry": median_telemetry(telemetry_runs),
                }
    return data


HTML = r'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BuddyStudy MVC vs WebFlux Load Test</title>
<style>
:root{color-scheme:light;--ink:#172033;--muted:#667085;--line:#dfe4ea;--bg:#f5f7fa;--mvc:#2563eb;--webflux:#ea580c}*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;letter-spacing:0}
header{height:64px;background:#111827;color:#fff;display:flex;align-items:center;padding:0 28px;gap:18px}header strong{font-size:18px}header span{color:#aeb8c8}
main{max-width:1520px;margin:auto;padding:24px}.toolbar{display:flex;align-items:end;gap:16px;flex-wrap:wrap;margin-bottom:18px}.field{display:grid;gap:6px}.field label{font-size:11px;font-weight:700;color:var(--muted);text-transform:uppercase}.field select{height:38px;border:1px solid #cdd5df;border-radius:6px;background:#fff;padding:0 34px 0 11px;font-weight:600}
.rates{display:flex;border:1px solid #cdd5df;border-radius:6px;overflow:hidden;background:#fff}.rates button{border:0;border-right:1px solid #e4e7ec;background:#fff;padding:10px 16px;font-weight:700;color:#475467;cursor:pointer}.rates button:last-child{border:0}.rates button.active{background:#172033;color:#fff}.meta{margin-left:auto;color:var(--muted);font-size:12px}
.cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:12px}.card,.chart{background:#fff;border:1px solid var(--line);border-radius:7px}.card{padding:15px}.card h3{font-size:12px;margin:0 0 10px;color:var(--muted);text-transform:uppercase}.pair{display:flex;justify-content:space-between;gap:12px}.pair b{display:block;font-size:22px}.pair small{font-weight:700}.mvc{color:var(--mvc)}.webflux{color:var(--webflux)}
.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.chart{padding:14px;min-width:0}.chart.full{grid-column:1/-1}.chart-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:6px}.chart h2{font-size:14px;margin:0}.legend{display:flex;gap:12px;color:var(--muted);font-size:12px;flex-wrap:wrap}.legend i{display:inline-block;width:14px;height:3px;margin-right:5px;vertical-align:middle}.chart canvas{width:100%;height:240px;display:block}.chart.full canvas{height:280px}
.table-wrap{margin-top:12px;background:#fff;border:1px solid var(--line);border-radius:7px;overflow:auto}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{padding:10px 12px;border-bottom:1px solid #edf0f3;text-align:right}th{font-size:11px;color:var(--muted);background:#f9fafb;text-transform:uppercase}th:first-child,td:first-child{text-align:left}tr:last-child td{border:0}.note{color:var(--muted);font-size:12px}
@media(max-width:900px){main{padding:14px}.cards,.grid{grid-template-columns:1fr}.chart.full{grid-column:auto}.meta{width:100%;margin:0}}
</style></head><body>
<header><strong>BuddyStudy Load Test</strong><span>MVC vs WebFlux · constant arrival rate</span></header><main>
<div class="toolbar"><div class="field"><label>Endpoint</label><select id="scenario"></select></div><div class="field"><label>Target RPS</label><div class="rates" id="rates"></div></div><div class="meta" id="meta"></div></div>
<section class="cards" id="cards"></section><section class="grid">
<article class="chart full"><div class="chart-head"><h2>Successful TPS over time</h2><div class="legend"></div></div><canvas id="tps"></canvas></article>
<article class="chart"><div class="chart-head"><h2>Response time p90 / p95</h2><div class="legend"></div></div><canvas id="latency"></canvas></article>
<article class="chart"><div class="chart-head"><h2>Failure rate</h2><div class="legend"></div></div><canvas id="failures"></canvas></article>
<article class="chart"><div class="chart-head"><h2>Dropped iterations per second</h2><div class="legend"></div></div><canvas id="dropped"></canvas></article>
<article class="chart"><div class="chart-head"><h2>JVM process CPU</h2><div class="legend"></div></div><canvas id="cpu"></canvas></article>
<article class="chart"><div class="chart-head"><h2>RSS memory</h2><div class="legend"></div></div><canvas id="memory"></canvas></article>
<article class="chart"><div class="chart-head"><h2>OS threads</h2><div class="legend"></div></div><canvas id="threads"></canvas></article>
<article class="chart"><div class="chart-head"><h2>Heap memory</h2><div class="legend"></div></div><canvas id="heap"></canvas></article></section>
<div class="table-wrap"><table><thead><tr><th>Target RPS</th><th>Runtime</th><th>HTTP RPS</th><th>Successful RPS</th><th>Success target</th><th>p90 ms</th><th>p95 ms</th><th>p99 ms</th><th>Failed</th><th>Dropped</th></tr></thead><tbody id="rows"></tbody></table></div>
<p class="note">Medians across rounds. HTTP RPS includes failed responses; Successful RPS excludes them. At saturation, judge successful TPS, dropped work, and failures before latency percentiles. The load generator and server shared this host.</p>
</main><script>
const DATA=__DATA__,META=__META__,colors={mvc:'#2563eb',webflux:'#ea580c',target:'#475467'},params=new URLSearchParams(location.search),scenarioLabels={'public-questions':'GET /api/v1/public/questions',studies:'GET /api/v1/studies','mobile-read-mix':'Mobile read mix'};let rate=META.rates.map(String).includes(params.get('rate'))?params.get('rate'):String(META.rates[0]);const scenario=document.getElementById('scenario'),rates=document.getElementById('rates');META.scenarios.forEach(value=>{const option=document.createElement('option');option.value=value;option.textContent=scenarioLabels[value]||value;scenario.appendChild(option)});if(Object.hasOwn(DATA,params.get('scenario')))scenario.value=params.get('scenario');META.rates.forEach(v=>{const b=document.createElement('button');b.textContent=v;b.onclick=()=>{rate=String(v);render()};rates.appendChild(b)});scenario.onchange=render;window.onresize=drawAll;
const legend=items=>items.map(i=>`<span><i style="background:${i.color}"></i>${i.name}</span>`).join('');const selected=()=>DATA[scenario.value][rate];
function series(runtime,source,key,name,color,dash=[]){return{name,color,dash,points:selected()[runtime][source].map(p=>({x:p.elapsedSeconds,y:Number(p[key]||0)}))}}
function draw(id,input,unit='',floor=0){const canvas=document.getElementById(id),ratio=devicePixelRatio||1,w=canvas.clientWidth,h=canvas.clientHeight;c=canvas.getContext('2d');canvas.width=w*ratio;canvas.height=h*ratio;c.scale(ratio,ratio);const pad={l:56,r:18,t:16,b:30},pw=w-pad.l-pad.r,ph=h-pad.t-pad.b,pts=input.flatMap(s=>s.points),maxX=Math.max(1,...pts.map(p=>p.x)),maxY=Math.max(floor,...pts.map(p=>p.y))*1.12||1,sx=x=>pad.l+x/maxX*pw,sy=y=>pad.t+ph-y/maxY*ph;c.font='11px -apple-system';c.fillStyle='#667085';c.strokeStyle='#e4e7ec';c.lineWidth=1;for(let i=0;i<=4;i++){const y=pad.t+ph*i/4;c.beginPath();c.moveTo(pad.l,y);c.lineTo(w-pad.r,y);c.stroke();const v=maxY*(1-i/4);c.fillText(`${v>=100?v.toFixed(0):v.toFixed(1)}${unit}`,4,y+4)}for(let i=0;i<=5;i++){const x=pad.l+pw*i/5;c.fillText(`${Math.round(maxX*i/5)}s`,x-8,h-8)}input.forEach(s=>{c.strokeStyle=s.color;c.lineWidth=2;c.setLineDash(s.dash);c.beginPath();s.points.forEach((p,i)=>i?c.lineTo(sx(p.x),sy(p.y)):c.moveTo(sx(p.x),sy(p.y)));c.stroke();c.setLineDash([])});canvas.parentElement.querySelector('.legend').innerHTML=legend(input)}
function card(title,key,fmt){const d=selected();return `<article class="card"><h3>${title}</h3><div class="pair"><div><small class="mvc">MVC</small><b>${fmt(d.mvc.summary[key])}</b></div><div><small class="webflux">WebFlux</small><b>${fmt(d.webflux.summary[key])}</b></div></div></article>`}
function drawAll(){const target=Number(rate),m=series('mvc','requests','successRps','MVC successful',colors.mvc),w=series('webflux','requests','successRps','WebFlux successful',colors.webflux),len=Math.max(m.points.length,w.points.length),t={name:'Target',color:colors.target,dash:[6,5],points:Array.from({length:len},(_,x)=>({x,y:target}))};draw('tps',[m,w,t],'',target);draw('latency',[series('mvc','requests','p90Ms','MVC p90',colors.mvc),series('mvc','requests','p95Ms','MVC p95',colors.mvc,[5,4]),series('webflux','requests','p90Ms','WebFlux p90',colors.webflux),series('webflux','requests','p95Ms','WebFlux p95',colors.webflux,[5,4])],'ms');draw('failures',[{name:'MVC',color:colors.mvc,dash:[],points:selected().mvc.requests.map(p=>({x:p.elapsedSeconds,y:p.failureRate*100}))},{name:'WebFlux',color:colors.webflux,dash:[],points:selected().webflux.requests.map(p=>({x:p.elapsedSeconds,y:p.failureRate*100}))}],'%');draw('dropped',[series('mvc','requests','dropped','MVC',colors.mvc),series('webflux','requests','dropped','WebFlux',colors.webflux)]);draw('cpu',[series('mvc','telemetry','cpuPercent','MVC',colors.mvc),series('webflux','telemetry','cpuPercent','WebFlux',colors.webflux)],'%');draw('memory',[series('mvc','telemetry','rssMiB','MVC',colors.mvc),series('webflux','telemetry','rssMiB','WebFlux',colors.webflux)],' MiB');draw('threads',[series('mvc','telemetry','osThreads','MVC',colors.mvc),series('webflux','telemetry','osThreads','WebFlux',colors.webflux)]);draw('heap',[series('mvc','telemetry','heapMiB','MVC',colors.mvc),series('webflux','telemetry','heapMiB','WebFlux',colors.webflux)],' MiB')}
function render(){[...rates.children].forEach(b=>b.classList.toggle('active',b.textContent===rate));document.getElementById('meta').textContent=`${META.rounds} rounds · ${META.duration} each · ${META.generated}`;document.getElementById('cards').innerHTML=card('Successful TPS','successRps',v=>v.toFixed(1))+card('HTTP response TPS','achievedRps',v=>v.toFixed(1))+card('p95 response time','p95Ms',v=>`${v.toFixed(2)} ms`)+card('Failure rate','failureRate',v=>`${(v*100).toFixed(3)}%`);const rows=[];META.rates.forEach(r=>['mvc','webflux'].forEach(runtime=>{const s=DATA[scenario.value][String(r)][runtime].summary;rows.push(`<tr><td>${r}</td><td class="${runtime}">${runtime.toUpperCase()}</td><td>${s.achievedRps.toFixed(1)}</td><td>${s.successRps.toFixed(1)}</td><td>${(s.successRps/r*100).toFixed(1)}%</td><td>${s.p90Ms.toFixed(2)}</td><td>${s.p95Ms.toFixed(2)}</td><td>${s.p99Ms.toFixed(2)}</td><td>${(s.failureRate*100).toFixed(3)}%</td><td>${s.dropped.toFixed(0)}</td></tr>`)}));document.getElementById('rows').innerHTML=rows.join('');requestAnimationFrame(drawAll)}render();
</script></body></html>'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir", type=Path)
    parser.add_argument("--rounds", type=int, required=True)
    parser.add_argument("--target-rps-list", required=True)
    parser.add_argument("--scenario-list", required=True)
    parser.add_argument("--duration", required=True)
    args = parser.parse_args()
    rates = [int(value) for value in args.target_rps_list.split(",")]
    scenarios = [value for value in args.scenario_list.split(",") if value]
    data = load_data(args.results_dir, args.rounds, rates, scenarios)
    metadata = {"rounds": args.rounds, "rates": rates, "scenarios": scenarios, "duration": args.duration, "generated": args.results_dir.name}
    (args.results_dir / "DASHBOARD_DATA.json").write_text(json.dumps(data, separators=(",", ":")) + "\n")
    html = HTML.replace("__DATA__", json.dumps(data, separators=(",", ":"))).replace("__META__", json.dumps(metadata, separators=(",", ":")))
    (args.results_dir / "DASHBOARD.html").write_text(html)


if __name__ == "__main__":
    main()

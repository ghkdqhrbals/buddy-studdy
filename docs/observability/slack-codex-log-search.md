# Slack Codex API Log Search

Slack에서 Codex에게 API 로그 조사를 요청하면 대시보드에서 수동 공유하지 않고 Codex가 Loki를 조회한 뒤 정해진 형식으로 답한다.

## 요청 예시

```text
Codex, requestId 25316cca-c9f1-46fc-a355-630553772173 로그 찾아줘.
Codex, 최근 15분 /api/v1/devices/register 5xx 로그 찾아줘.
Codex, NoClassDefFoundError 검색해서 관련 request/response 보여줘.
```

## Codex 실행 명령

```sh
cd monitoring/api-dashboard
LOKI_BASE_URL=https://grafana.lowfidev.cloud \
MONITORING_BASIC_AUTH="$(printf '%s' 'admin:password' | base64)" \
npm run codex:log-search -- --requestId 25316cca-c9f1-46fc-a355-630553772173
```

필터 예시:

```sh
npm run codex:log-search -- --path /api/v1/devices/register --status 5 --rangeMs 900000
npm run codex:log-search -- --q NoClassDefFoundError --from 2026-07-05T12:00:00Z --to 2026-07-05T13:00:00Z
npm run codex:log-search -- --method POST --path /api/v1/auth/token --sort asc
```

환경 변수:

- `LOKI_BASE_URL`: Loki 또는 API dashboard proxy base URL. 기본값은 `http://127.0.0.1:3100`.
- `MONITORING_DASHBOARD_URL`: 결과에 포함할 API Logs 링크. 기본값은 `https://grafana.lowfidev.cloud`.
- `MONITORING_BASIC_AUTH`: Basic Auth가 필요한 경우 `user:password`를 base64 인코딩한 값.

## Slack 응답 템플릿

Codex는 스크립트 출력 그대로 Slack에 전달한다. 응답에는 다음 항목이 들어가야 한다.

- 검색 시간 범위와 정렬 방향
- 적용된 필터
- 같은 조건으로 바로 열 수 있는 Grafana/API Logs 링크
- 선택된 API 요청의 method/path/status/duration/requestId
- 에러 코드, 메시지, stack trace가 있으면 stack trace
- 최근 매칭 API 요청 목록
- trace id/requestId로 묶인 관련 로그
- 선택된 요청의 request JSON과 response JSON

## 원칙

- Slack 메시지에 인증 비밀번호, APNs token, Authorization header 원문을 추가로 노출하지 않는다. 로그 수집 단계에서 이미 redaction된 값만 사용한다.
- 1억 건 규모를 가정하고 즉시 전체 검색을 하지 않는다. 시간 범위, requestId, path, status, 또는 검색어 중 하나 이상으로 좁힌 뒤 조회한다.
- 대시보드 UI에 Slack 토큰이나 webhook URL을 넣지 않는다. Slack 전송은 Codex 또는 서버 사이드에서만 수행한다.

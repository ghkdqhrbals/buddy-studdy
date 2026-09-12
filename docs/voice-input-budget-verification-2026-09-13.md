# 음성 입력 토큰 절감 — 2026-09-13

## 변경

- 서버가 문장을 이미 준비한 시작 질문, 저장된 질문 낭독, 저장된 채점 안내,
  학습 상태 안내, 취소 안내, 변경 확인 질문, 종료/한도 안내에는 응답별
  `tools: []`, `tool_choice: none`, `input: []`를 명시한다.
- 종전 `tool_choice: none`만으로는 세션의 도구 정의가 입력에서 제외되지 않았다.
  질문/채점 낭독은 이미 빈 custom input을 사용했지만 도구 카탈로그는 상속했다.
- 일반 학습 대화와 사용자의 새 질문에는 위 override를 적용하지 않는다.
  세션 도구 정의를 매번 바꾸지 않아 일반 응답의 고정 prefix를 유지한다.
- WebRTC 최초 세션, sideband 업데이트 및 PCM 세션에 같은 truncation 정책을
  사용한다: `post_instructions: 8000`, `retention_ratio: 0.8`.
  이는 **지침 이후 대화 문맥의 한도**이며, 지침/도구까지 포함한 총 입력의
  8,000토큰 상한을 의미하지 않는다. 한도 도달 시 오래된 공급자 문맥을
  한 번에 정리해 다음 정리까지 여유를 둔다.

응답별 입력 축소는 앱 채팅/초안/레코드를 삭제하지 않는다. 생성된 발화는
기존 기본 대화에 계속 추가한다. `conversation: none`이나
`conversation.item.delete`는 보내지 않는다. 정확한 질문·점수·확인문은
기존 응답별 지침에 그대로 유지하며, 질문 낭독 완료 전 답변 준비 전환과
취소/새 발화/중복 낭독 방지 경계도 유지한다.

오래된 모델 문맥을 무한히 기억하는 기능은 보장하지 않는다. 현재 학습의
정본은 서버의 학습/질문/답변 상태에 남으며, 초기 지침은 truncation 대상이
아니다. 별도의 유료 요약 작업은 추가하지 않았다.

## 실제 API 검증

개발 백엔드와 같은 개발 Secrets Manager 설정에서 정규 사용자 콘텐츠 키를
프로세스 메모리로만 읽었다. 키, AWS 자격증명, secret payload를 결과나 파일에
저장하지 않았다. 기존 로컬 앱 세션을 생성하거나 DB/학습 데이터를 변경하지
않았다. 실제 도구 실행도 없다.

`gpt-realtime-2.1`, 같은 세션, 같은 합성 한국어 질문, 같은 응답 지침,
동일 `input: []`, `tool_choice: none`, 최대 출력 128토큰으로 두 번 요청했다.
16개 **합성** 함수 정의의 세션 상속 유무만 바꿨다. 음성 바이트는 저장하지 않았다.

| 조건 | 입력 | 출력 | 캐시 입력 | 전사 결과 |
|---|---:|---:|---:|---|
| 기존: 도구 정의 상속 | 1,357 | 110 | 0 | Redis에서 TTL은 무엇인가요? |
| 변경: 도구 정의 제외 | 28 | 92 | 0 | Redis에서 TTL은 무엇인가요? |

두 응답 모두 completed. 이 합성 낭독에서는 입력이 97.9% 줄었다. **전체 대화의
절감률, 실제 운영 도구 카탈로그에 대한 절감률, 청구 금액 감소율이 아니다.**
출력 토큰 차이는 음성 생성 변동이며 고정 절감으로 주장하지 않는다.
공급자는 요청한 8,000 / 0.8 truncation 설정을 session.updated에서 확인했다.

- 실행 스크립트: `build/voice-input-budget-probe.mjs`
- 로컬 측정 결과: `build/voice-input-budget-probe-results.json`
- 공식 계약: [Realtime client events](https://developers.openai.com/api/reference/resources/realtime/client-events),
  [Realtime cost optimization](https://developers.openai.com/api/docs/guides/voice-latency-cost)

## 회귀 검증

관련 native controller, relay, WebRTC/PCM 어댑터, MCP 설정 및 입력 예산 검사를
실행했다. 처음 두 실패는 상태 안내에도 기존 문맥이 있어야 한다는 예전
테스트 기대값이었다. 실제 실패 상태/답변 차단/단일 안내 검사를 유지하며
빈 입력·빈 도구 정의 및 정확한 안내 유형을 검증하도록 수정했다.

최종 결과: **437개 검사 통과, 실패/오류/건너뜀 0개**. `:tutor:bootJar`도 성공했다.

```sh
./gradlew :infra:test \
  --tests '*VoiceTutorInputBudgetTest' \
  --tests '*VoiceTutorNativeConversationControllerTest' \
  --tests '*VoiceTutorWebRtcMcpConfigurationTest' \
  --tests '*VoiceTutorNativeSessionRelayTest' \
  --tests '*OpenAIVoiceTutorRealtimeAdapterTest' \
  --tests '*OpenAIVoiceTutorWebRtcAdapterTest' \
  :tutor:bootJar --no-daemon --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m'
```

빌드 로그는 `build/voice-input-budget-verified.log`에 남겼다. 앱 코드를 변경하지
않았으므로 이번 검증에 iOS 빌드나 실제 기기 UI 검증은 포함하지 않았다.


## 로컬 적용

- 진행 중인 `ACTIVE` 음성 세션 0개를 확인한 뒤 로컬 `backend-backend-1`만 재시작했다.
- 기존 이미지와 앱 볼륨을 유지하고 호스트에서 빌드한 JAR을 교체했다. Docker 이미지
  빌드나 운영 배포는 수행하지 않았다.
- 시작 시각: `2026-09-12T17:31:22.816351088Z` (한국시간 9월 13일 02:31).
- 새 JAR SHA-256: `fd914c91c22154dc9fed8ae3563c5f972a1bb74642eaf8cb8983a262ef6ec076`.
- 이전 JAR SHA-256: `7858fcb99fc519018147843ddcb8438b5709953139b0b130248ce386a6cbbbff`.
- 이전 JAR 백업: 앱 볼륨의 `/app/buddystudy-backend.pre-input-budget-20260913.jar`.
- 기존 `127.0.0.1:8080` 바인딩 유지. 초기 기동 중 연결이 준비되지 않은 응답 후,
  `/actuator/health`가 `status: UP`으로 응답함을 확인했다.
- 새로 시작하는 음성 세션부터 적용된다. 실제 전체 세션의 비용 감소율은 이후
  `token-usage.html`의 운영별 입력/캐시 입력 통계로 비교해야 한다.

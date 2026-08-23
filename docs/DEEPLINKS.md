# BuddyStudy iOS 딥링크

이 문서는 iOS 앱이 현재 실제로 해석하는 `buddystudy://` 딥링크와 화면 이동
결과를 정리한다. 새 링크를 발급할 때는 아래 **권장 URI**만 사용한다. 별칭은 이미
전송된 알림과 이전 앱 버전의 호환을 위한 입력이다.

구현의 기준은 다음과 같다.

- URL 등록: `StudyMate/iOSInfo.plist`
- URL 파싱: `AppRoute` in `StudyMate/Models/StudyModels.swift`
- 외부 URL 진입: `StudyMate/StudyMateiOSApp.swift`
- 앱 화면 이동: `AppState.openRoute` in `StudyMate/ViewModels/AppState.swift`
- 푸시 및 알림함 이동: `StudyNotificationPayload`와
  `NotificationLandingCoordinator`
- 관리자 발송 검증: backend `AdminMessagingService`

## 공통 규칙

- 스킴은 `buddystudy` 하나만 지원한다.
- 호스트와 경로 이름은 대소문자를 구분하지 않지만 ID 값은 원문을 유지한다.
- 화면 ID는 경로에 넣는 방식을 권장한다. 예:
  `buddystudy://records/record-123`
- 경로 ID는 `/`가 없는 단일 path segment여야 한다. `/`가 포함된 특수 ID를
  다뤄야 한다면 percent encoding한 쿼리 파라미터 형식을 사용한다.
- 알 수 없는 경로, 다른 스킴, 지원하지 않는 경로 구조는 무시한다. 상세 ID가
  없는 `studies`, `records`, `public/questions`는 각각의 목록 화면으로 이동한다.
- 딥링크는 인증이나 권한을 우회하지 않는다. 로그아웃 상태에서는 해당 화면의
  로그인 안내 또는 백엔드 접근 정책이 그대로 적용된다.
- 앱 초기화 전에 URL이 들어오면 보관했다가 `AppState` 준비 후 순서대로 처리한다.
- 앱이 실행 중일 때 푸시가 수신되기만 한 경우에는 이동하지 않는다. 사용자가
  시스템 알림이나 알림함 항목을 명시적으로 눌러야 이동한다.

## 화면별 권장 딥링크

| 화면 | 권장 URI | 파라미터 | 직접 실행 시 결과 | 비고 |
| --- | --- | --- | --- | --- |
| 홈 | `buddystudy://home` | 없음 | 홈 탭의 기본 화면을 연다. | `buddystudy://`도 홈으로 해석한다. |
| 홈 공지 팝업 | `buddystudy://home/message` | URL 파라미터 없음 | URL만 열면 홈으로 이동한다. | 팝업은 `ADMIN_MESSAGE` 알림의 제목/본문이 함께 있을 때만 표시된다. |
| 내 학습 목록 | `buddystudy://studies` | 없음 | 홈 탭의 **내 학습** 범위를 연다. | 이전 알림 호환용 경로이며 관리자 새 메시지 프리셋에는 노출하지 않는다. |
| 학습방 | `buddystudy://studies/{studyId}` | `studyId`: 학습 또는 토픽 ID | 홈 탭에서 해당 학습방을 연다. | 학습 트리가 아니라 질문/답변 학습방을 연다. |
| 기록 목록 | `buddystudy://records` | 없음 | 기록 탭을 연다. | 로그아웃 상태에서는 기록 로그인 안내가 표시된다. |
| 기록 상세 | `buddystudy://records/{recordId}` | `recordId`: 기록 ID | 기록 탭에서 해당 기록 상세를 연다. | 질문 푸시가 사용하는 표준 경로다. |
| 통계 | `buddystudy://statistics` | 없음 | 통계 탭을 연다. | 로그아웃 상태에서는 통계 로그인 안내가 표시된다. |
| 설정 | `buddystudy://settings` | 없음 | 홈에서 설정 화면을 연다. | 로그아웃 상태에서는 설치 단위 설정만 노출된다. |
| 프로필 허브 | `buddystudy://profile` | 없음 | 홈에서 프로필 허브 시트를 연다. | 로그아웃 상태에서는 로그인 진입점을 보여준다. |
| 피드백 작성 | `buddystudy://feedback` | 없음 | 홈에서 피드백 작성 화면을 연다. | 네이티브 광고 캠페인의 표준 목적지로 사용할 수 있다. |
| 공개 질문 목록 | `buddystudy://public/questions` | 없음 | 홈의 **모든 학습들** 범위를 연다. | 실제 데이터 접근 권한은 현재 세션과 백엔드 정책을 따른다. |
| 공개 질문 상세 | `buddystudy://public/questions/{questionId}` | `questionId`: 공개 질문 ID | 홈에서 공개 질문 상세를 연다. | 상세를 먼저 지정하고 필요한 목록 데이터를 불러온다. |

### 사용 예시

```text
buddystudy://home
buddystudy://studies/42
buddystudy://records/record-123
buddystudy://statistics
buddystudy://settings
buddystudy://profile
buddystudy://feedback
buddystudy://public/questions/987
```

## 호환 별칭

아래 주소도 현재 파서가 읽지만 새 링크에는 권장 URI를 사용한다.

| 목적지 | 호환 주소 |
| --- | --- |
| 홈 | `buddystudy://test-push` |
| 내 학습 목록 | `buddystudy://study` |
| 학습방 | `buddystudy://study/{studyId}` |
| 기록 목록 | `buddystudy://history` |
| 기록 상세 | `buddystudy://record/{recordId}`, `buddystudy://history/{recordId}` |
| 통계 | `buddystudy://stats` |
| 설정 | `buddystudy://settings/openai`, `buddystudy://settings/api-key` |
| 공개 질문 목록 | `buddystudy://public` |
| 공개 질문 상세 | `buddystudy://public/question/{questionId}` |

`settings/openai`와 `settings/api-key`는 과거 이름을 보존한 별칭이다. 현재 iOS에서는
별도 OpenAI 설정 하위 화면이 없으므로 일반 설정 화면을 연다.

### 쿼리 파라미터 호환

상세 화면 ID는 경로 방식이 권장되지만 다음 쿼리도 읽는다. 쿼리 키는 표에 적힌
대소문자를 그대로 사용한다.

| 목적지 | 지원 예시 |
| --- | --- |
| 학습방 | `buddystudy://studies?studyId=42`, `?categoryId=42`, `?id=42` |
| 기록 상세 | `buddystudy://records?recordId=record-123`, `?recordID=record-123`, `?id=record-123` |
| 공개 질문 상세 | `buddystudy://public/questions?questionId=987`, `?questionID=987`, `?id=987` |

## 진입 채널별 이동 방식

같은 `AppRoute`라도 진입 채널에 따라 내비게이션 스택이 다르다.

| 진입 채널 | 동작 |
| --- | --- |
| Safari, 메모, 다른 앱의 URL | `onOpenURL`로 받아 목적지 탭 또는 홈 내 화면으로 직접 이동한다. |
| 시스템 푸시 탭 | 홈과 내 학습 목록은 홈으로 직접 이동한다. 그 외 목적지는 **알림** 탭을 선택하고 해당 목적지 하나만 알림 탭 내비게이션 스택에 올린다. |
| 앱 알림함 항목 탭 | 선택한 항목을 읽음 처리한 뒤 같은 목적지를 연다. 홈 공지 팝업은 알림 화면을 닫고 홈에서 표시한다. |
| 푸시 단순 수신 | 데이터만 갱신하고 화면은 이동하지 않는다. |

이 규칙 때문에 시스템 푸시를 눌렀을 때 숨은 `홈 → 알림함 → 상세` 스택을 만들지
않는다. 기록 상세, 공개 질문 상세, 통계, 설정 등은 알림 탭에서 목적지 하나만
직접 표시한다.

## 홈 공지 팝업 계약

`buddystudy://home/message`는 일반 URL만으로 본문을 전달하는 화면이 아니다.
다음 정상 발송 계약을 만족하는 관리자 알림에서만 공지 팝업으로 사용한다.

1. 알림 유형이 `ADMIN_MESSAGE`다. 알림함 항목은 이 유형까지 확인한다.
2. `deepLink`, `url`, `landingUrl` 중 하나가
   `buddystudy://home/message`다.
3. 시스템 푸시는 `aps.alert.title`과 `aps.alert.body`를 포함한다. 알림함에서는
   저장된 알림의 `title`과 Markdown `body`를 사용한다.
4. 사용자가 해당 알림을 명시적으로 누른다.

팝업 본문은 Markdown으로 표시한다. APNs 미리보기는 Markdown 원문이 아니라
파서가 만든 일반 텍스트를 사용한다. URL만 외부에서 열면 표시할 제목과 본문이
없으므로 홈 이동으로 끝난다.

관리자 메시지 기본 요청은 다음과 같다.

```json
{
  "title": "피드백을 확인했어요",
  "body": "소중한 피드백 감사합니다. **무료 크레딧**을 확인해 주세요.",
  "deepLink": "buddystudy://home/message"
}
```

## 관리자 메시지에서 사용할 수 있는 목적지

관리자 API는 외부 웹 URL을 거부하고 첫 경로가 다음 allowlist에 포함된
`buddystudy://` 주소만 허용한다.

```text
home, study, studies, record, records, history,
stats, statistics, settings, profile, public
```

관리자 UI의 기본 프리셋은 다음 여섯 개다.

| 프리셋 | URI |
| --- | --- |
| Home popup | `buddystudy://home/message` |
| Home | `buddystudy://home` |
| Records | `buddystudy://records` |
| Statistics | `buddystudy://statistics` |
| Settings | `buddystudy://settings` |
| Public questions | `buddystudy://public/questions` |

동적 상세 화면은 Custom app deep link에 표준 URI를 입력한다. 예:
`buddystudy://records/123`. 백엔드 allowlist는 첫 경로만 검사하므로, 발송 전에
반드시 이 문서의 전체 URI 형태와 일치하는지 확인해야 한다. 첫 경로만 유효하고
나머지 구조가 잘못된 주소는 서버가 수락하더라도 iOS가 무시할 수 있다.

## URL이 아닌 알림 payload 호환 형식

이 형식은 레거시 APNs/CloudKit payload 호환용이며 외부 링크로 사용하지 않는다.
`deepLink`, `url`, `landingUrl`이 있으면 URL 딥링크가 우선한다. URL이 없을 때
`route`, `screen`, `destination`과 `params` 또는 `parameters`를 읽는다.

| 목적지 | route 값 | 필요한 params |
| --- | --- | --- |
| 홈 | `home` | 없음 |
| 내 학습 목록 | `study`, `study.list`, `studies` | 없음 |
| 학습방 | `study.room`, `study.detail` | `categoryId` 또는 `studyId` |
| 기록 목록 | `records`, `record.list`, `history` | 없음 |
| 기록 상세 | `record.detail`, `records.detail`, `history.detail` | `recordId`, `recordID` 또는 `id` |
| 통계 | `stats`, `statistics` | 없음 |
| 설정 | `settings` | 없음 |
| 설정 호환 별칭 | `settings.openai`, `settings.api-key` | 없음 |
| 프로필 | `profile` | 없음 |
| 피드백 작성 | `feedback` | 없음 |
| 공개 질문 목록 | `public.questions`, `community.questions` | 없음 |
| 공개 질문 상세 | `public.question`, `community.question` | `questionId`, `questionID` 또는 `id` |

## 현재 직접 딥링크가 없는 화면

다음 화면은 앱 내부 탐색이나 알림 라우팅으로만 열며 독립된 `buddystudy://`
주소가 없다.

- 알림함과 특정 알림 항목
- 내 학습 트리 전체 보기
- 통계의 특정 학습/토픽 상세
- 사용량
- 멤버십 관리와 결제 내역
- 알림 설정
- 약관
- 계정 설정
- 아바타 편집
- 로그인, 온보딩, 업데이트, 점검 화면

특히 `buddystudy://notifications`와 `buddystudy://notifications/{id}`는 현재
`AppRoute`가 지원하지 않는다. `buddystudy://studies/{studyId}`는 학습방을 열며
학습 트리 전체 보기를 열지 않는다.

## 새 딥링크 추가 체크리스트

1. `AppRoute` case와 URL/legacy payload 파서를 함께 추가한다.
2. 직접 실행, 시스템 푸시 탭, 알림함 탭의 화면 이동을 모두 정의한다.
3. 로그아웃, 삭제된 ID, 존재하지 않는 ID의 실패 화면을 확인한다.
4. 관리자 발송이 필요하면 backend allowlist와 Monitoring 프리셋을 함께 갱신한다.
5. 생성하는 백엔드 이벤트와 푸시의 딥링크를 새 표준 URI로 통일한다.
6. iOS 라우트 파싱 테스트, 알림 라우팅 테스트, backend 관리자 검증 테스트를
   추가한다.
7. 이 문서와 `ARCHITECTURE.md`, 필요하면 `NOTIFICATION_SYSTEM.md`를 갱신한다.

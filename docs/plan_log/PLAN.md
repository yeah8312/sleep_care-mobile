# Galaxy Watch 연동 및 모바일 큐/커서 전환 계획

## Summary
- 1차 워치 플랫폼은 `Galaxy Watch + Samsung Health Sensor SDK` 로 확정한다.
- 모바일 앱은 로컬 Wi‑Fi에서 `NSD + WSS`로 라즈베리파이를 찾고 연결하며, 동시에 Wear OS Data Layer로 워치 세션을 제어한다.
- 이번 범위에는 `watch session start/stop`, 심박 샘플 큐/커서, `hr.ingest` 중계, 진동 경고 전달을 포함한다.
- 수면 데이터는 모바일 앱에서 Health Connect로 읽고, 워치 앱은 companion 세션과 상태 표시를 담당한다.

## Progress Update
- `:watch-contracts` 공유 모듈을 추가해 watch models, paths, protocol codec, cursor calculator를 통합했다.
- `:watch` Wear OS companion 모듈을 추가해 `Connection Waiting -> Active Session -> Alerting -> Watch Settings` UI와 foreground tracking service 스캐폴드를 구현했다.
- 모바일 앱은 워치 `session.ready / error / closed`, ACK 커서, 백필 요청, `hr.ingest`, 진동 경고 요청을 반영했다.
- 모바일 앱 수면 데이터는 Health Connect 기반으로 연결했고, 권한/미지원/업데이트 필요/데이터 없음 상태를 분기한다.
- 설정 화면에서 Health Connect 권한 요청을 직접 올릴 수 있게 했고, manifest에 `android.permission.health.READ_SLEEP`를 선언했다.
- Health Connect 앱에서 이 앱이 직접 연결 대상으로 보이도록 onboarding/rationale activity와 provider query를 추가했다.
- 권한 창이 뜨지 않는 경우를 위해 설정 화면에서 Health Connect 관리/업데이트 화면으로 직접 이동할 수 있게 했다.
- 수면 분석 화면을 Stitch 산출물 방향으로 재구성했고, 상단 카드는 `최근 7일 수면 점수`로 명확히 표시한다.
- 규칙성은 취침/기상 시각 일관성 점수로 바꿨고, 관련 계산식은 [sleep-metrics.md](../sleep-metrics.md)에 문서화했다.
- 주간 수면 리듬은 `기상한 날짜` 기준 대표 수면 1개와 추가 수면으로 집계하며, `3시간 이하 공백`의 분할 밤잠은 병합한다.
- 홈의 `어제 수면 상태` 카드도 같은 집계 기준을 사용하도록 수정했다.
- Android Studio 기준 `:app:compileDebugKotlin`, `:watch:compileDebugKotlin`, `:watch-contracts:test`, `:app:testDebugUnitTest` 검증을 통과했다.
- Samsung Health Sensor SDK AAR은 아직 미연결이라 워치 센서 backend는 placeholder 상태다.

## Implementation Changes
- 데이터 경계 정리:
  - `PiBleDataSource`를 `PiNetworkDataSource`로 교체하고, 책임을 `서비스 발견`, `WSS 연결`, `세션 제어`, `실시간 이벤트 수신`으로 명확히 분리한다.
  - `WatchSessionDataSource`를 추가해 Galaxy Watch 연결, 세션 제어, ACK, 백필, 진동 요청을 전담하게 한다.
  - `WatchSleepDataSource`는 Health Connect 기반 실제 수면 읽기 경로로 교체한다.
  - `DeviceConnectionRepository`는 `Pi 발견/연결 상태`와 `Galaxy Watch 연결 상태`를 함께 노출한다.
- 새 인터페이스와 타입:
  - `StudySessionRepository`를 추가해 `observeSessionState()`, `startSession()`, `stopSession()`를 제공한다.
  - `PiNetworkDataSource`는 `hr.ingest` 송신까지 포함하고, `WatchSessionDataSource`는 Galaxy Watch 세션 제어와 심박 배치 수신을 담당한다.
  - 프로토콜 모델은 `PiEnvelope`, `PiHelloAck`, `PiRiskUpdate`, `PiAlertFire`, `PiSessionSummary`, `WatchHeartRateSample`, `WatchCursor`, `StudySessionState`로 명시한다.
  - `SleepAnalysisSnapshot`과 홈/분석용 상태 모델에는 `emptyReason` 또는 동등한 unavailable 플래그를 추가해 가짜 수치를 없앤다.
- 네트워크 구현:
  - Android NSD로 `_sleepcare._tcp`를 탐색하고, TXT 레코드의 `proto`, `tls`, `ws`, `device_id`를 읽어 연결 대상을 확정한다.
  - WebSocket 클라이언트는 OkHttp 기반으로 추가하고, `WSS`만 허용한다.
  - 보안은 “수동 신뢰” 방식으로 고정한다: 등록된 Pi 인증서를 앱 리소스에 포함하고 그 인증서만 신뢰하는 커스텀 TrustManager를 사용한다. 핀닝 UI나 TOFU는 이번 범위에 넣지 않는다.
  - `AndroidManifest.xml`에는 `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`를 추가하고, 멀티캐스트 락을 사용해 NSD 안정성을 확보한다.
- 저장/동기화 구조:
  - `drowsiness_events`는 `alert.fire`와 `session.summary` 기반 실제 이벤트를 저장하는 용도로 유지한다.
  - 새 `study_sessions` 테이블을 추가해 세션 시작/종료 시각, 상태, 마지막 위험 상태, 요약 수치를 저장한다.
  - 새 `watch_hr_samples`, `watch_cursors` 저장소를 추가해 심박 수신 큐와 `highest contiguous ack` 커서를 유지한다.
  - `risk.update`는 실시간 UI 상태와 현재 세션 상태에는 반영하되, 전량 영속 저장하지 않는다.
  - `SleepRepository.refreshFromSource()`는 Health Connect에서 최근 수면 세션을 읽고 로컬 캐시를 동기화한다.
- UI/UX 반영:
  - 홈 화면은 새 Stitch 방향에 맞춰 `공부 시작 버튼 ↔ 활성 세션 타이머`를 최상단에 배치한다.
  - 홈과 분석 화면의 수면 카드/문구는 Health Connect 상태를 기준으로 표시하고, 기존 기본 점수/기본 그래프 fallback은 제거한다.
  - 졸음 관련 카드와 상세 화면은 실제 `risk.update`/`alert.fire`를 기반으로 갱신한다.
  - 기기 연결/설정 화면은 “Galaxy Watch 연결/재시도/해제” 흐름을 반영한다.
- 추천 로직 조정:
  - 추천 엔진은 수면 데이터가 없을 때도 동작해야 하며, `최근 졸음 이벤트`, `시험 일정`, `학습 플랜`, `사용자 목표`만으로 취침/기상 제안을 계산한다.
  - 수면 데이터가 비어 있을 때는 “수면 기록 기반 보정 없음” 설명을 추가하고, 가짜 수면 점수는 계산하지 않는다.

## Public APIs / Interfaces / Types
- `PiBleDataSource` 삭제, `PiNetworkDataSource` 추가.
- `StudySessionRepository` 추가.
- `StudySessionState`, `PiEnvelope`, `PiRiskUpdate`, `PiAlertFire`, `PiSessionSummary` 추가.
- `HomeDashboardSnapshot`에 활성 세션 상태를 포함.
- `SleepAnalysisSnapshot`은 unavailable 상태를 표현할 수 있도록 nullable 수치 또는 `emptyReason` 필드를 포함하도록 변경.
- `SleepSession.source` 기본 문구에서 `Smartwatch / Health Connect` 전제를 제거.

## Test Plan
- 프로토콜 파서 테스트: `hello_ack`, `risk.update`, `alert.fire`, `session.summary` JSON이 각 도메인 타입으로 정확히 매핑되는지 검증한다.
- 세션 상태 테스트: `startSession()` 이후 `connecting → running`, `stopSession()` 이후 `stopping → idle`로 전이되는지 검증한다.
- 저장소 테스트: `alert.fire` 수신 시 `drowsiness_events`에 실제 이벤트가 저장되고 중복 이벤트가 중복 삽입되지 않는지 검증한다.
- 추천 테스트: 수면 세션이 비어 있을 때도 추천이 생성되며, 수면 점수 fallback 숫자가 노출되지 않는지 검증한다.
- UI 상태 테스트: 홈 화면이 `inactive session`, `active timer`, `sleep unavailable`, `live drowsiness state`를 각각 올바르게 렌더링하는지 확인한다.
- 수동 시나리오:
  - 동일 Wi‑Fi에서 Pi 광고 발견 후 연결 성공
  - 공부 시작 시 `hello/session.open` 송신 및 타이머 시작
  - `risk.update` 수신 시 홈/분석 UI 갱신
  - `alert.fire` 수신 시 이벤트 저장 및 사용자 알림 반영
  - 공부 종료 시 `session.close` 송신 및 `session.summary` 저장
  - 워치 데이터가 전혀 없어도 앱이 오류 없이 동작

## Assumptions
- 이번 단계의 실제 Pi 프로토콜 범위는 `hello`, `hello_ack`, `session.open`, `session.ack`, `hr.ingest`, `risk.update`, `alert.fire`, `session.close`, `session.summary`, `ping/pong`를 포함한다.
- 워치 프로토콜의 `session.ready / error / close` 기본 이벤트는 구현했고, 세부 payload와 retry 정책은 후속 논의 대상으로 남긴다.
- 로컬 네트워크에는 1대의 등록된 Pi가 있고, 앱은 리소스에 포함된 인증서로 그 Pi를 수동 신뢰한다.
- 구현 중심 파일은 [AppModule.kt](/mnt/c/Users/cksgm/.gemini/antigravity/scratch/sleep_care-mobile/app/src/main/java/com/sleepcare/mobile/di/AppModule.kt), [AppRepositories.kt](/mnt/c/Users/cksgm/.gemini/antigravity/scratch/sleep_care-mobile/app/src/main/java/com/sleepcare/mobile/data/repository/AppRepositories.kt), [SleepCareApp.kt](/mnt/c/Users/cksgm/.gemini/antigravity/scratch/sleep_care-mobile/app/src/main/java/com/sleepcare/mobile/navigation/SleepCareApp.kt)를 축으로 정리한다.

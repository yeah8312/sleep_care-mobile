# 워치 앱 계획

## 현재 구현 상태
- `:watch` Wear OS companion 모듈과 `:watch-contracts` 공유 계약 모듈을 추가했다.
- 1차 대상은 **Galaxy Watch + Samsung Health Sensor SDK** 로 확정했다.
- 워치 앱은 `Connection Waiting -> Active Session -> Alerting -> Watch Settings` 흐름, `WearableListenerService`, foreground tracking service 스캐폴드를 갖췄다.
- 모바일 앱은 Wear OS Data Layer 기반 연결/ACK/백필, `session.ready / error / closed`, `hr.ingest` 중계 구조를 반영했다.
- Data Layer 전달 조건을 맞추기 위해 워치 앱의 런타임 `applicationId`는 폰 앱과 같은 `com.sleepcare.mobile`을 사용하고, Kotlin `namespace`는 `com.sleepcare.watch`로 유지한다.
- 워치 센서 백엔드는 Samsung Health Sensor SDK의 `HEART_RATE_CONTINUOUS` tracker를 사용한다. SDK AAR은 `watch/libs/samsung-health-sensor-api.aar`에 로컬로 배치해야 하며 Git에는 커밋하지 않는다.

## 목표
- 학습 세션 중 심박/IBI 데이터를 안정적으로 수집한다.
- 모바일 앱과의 실시간 연동 및 진동 보조 경고를 구현한다.
- 수면 데이터 연동 경로를 정리해 모바일 앱 분석에 연결한다.

## 주요 기능
- 심박/IBI 수집
- 모바일 앱과 실시간 동기화
- 진동 보조 경고 수신
- 연결 상태 표시
- 수면 데이터 연동 보조

## 기술 방향
- 워치와 모바일 앱 간 실시간 통신은 Wear OS Data Layer를 기준으로 설계한다.
- Wear OS Data Layer는 폰/워치 앱의 `applicationId`와 signing key가 같아야 listener 전달이 가능하다. capability는 대상 노드 필터이며 이 보안 조건을 대신하지 않는다.
- 워치 앱이 열린 상태에서는 `MessageClient.addListener` live listener도 같이 붙여 manifest listener 기동 문제와 실제 메시지 수신 문제를 분리 진단한다.
- 심박/IBI 접근은 **Samsung Health Sensor SDK** 를 사용한다.
- 수면 기록은 워치 앱 구현 범위와 함께 연동 경로를 검토한다.
- 워치 앱 범위는 실시간 세션 연동과 최소한의 센서 파이프라인 검증에 우선 집중한다.

## 현재 범위
### 학습 세션
- 워치 앱은 foreground tracking service, 10분 샘플 버퍼, Data Layer listener, 세션 UI 스캐폴드를 구현했다.
- 모바일 앱은 워치 세션 시작/중지, 심박 샘플 큐/커서, ACK 커서, 백필 요청, `hr.ingest` 중계를 수행한다.
- 실제 심박/IBI 연속 수집은 Samsung Health Sensor SDK backend가 담당한다. 첫 샘플 전에는 데모 심박값을 표시하지 않아 SDK 수신 여부를 구분한다.
- tracker 시작에 성공하면 `session.ready`의 `sensor_backend`는 `samsung-health-sensor-sdk`로 전달한다. 권한, Health Platform, SDK policy, tracker 미지원 문제는 `session.error`로 모바일에 전달한다.

### 실기기 진단 메모
- 2026-05-06 Galaxy Watch `SM_R905N` 실기기 테스트에서 `sleepcare_watch_session_runtime` capability는 확인되지만, manifest 기반 `WearableListenerService`에는 `/sc/v1/...` 메시지가 기록되지 않는 현상을 확인했다.
- 같은 설치 상태에서 워치 앱을 화면에 열고 `MessageClient.addListener` live listener를 붙이자 테스트 세션 시작, flush policy, vibration, ACK, backfill, stop 명령이 모두 성공했다.
- 따라서 해당 증상은 Data Layer 계약, path, payload, nodeId 전체가 틀린 문제라기보다, 워치 OS/Play Services 조합에서 manifest listener가 안정적으로 기동하지 않은 문제로 본다.
- 운영 세션을 폰에서만 시작해야 한다면 manifest listener wake-up 경로를 계속 검증해야 한다. 워치 앱을 먼저 열어두는 테스트/운영 흐름에서는 live listener가 실사용 가능한 수신 경로가 된다.

### 수면 데이터
- 워치 앱은 수면 기록을 직접 읽지 않고, 설정 화면에서 “휴대폰 Health Connect 관리” 상태를 보여준다.
- 모바일 앱은 Health Connect 기반 수면 세션 읽기와 권한/미지원/무데이터 상태 분기를 구현했다.

## 후속 확장 가능성
- 수면 단계 정보 활용 검토
- 심박 외 추가 생체 신호 활용 검토
- 워치 단독 보조 경고 강화 검토

## 구현 단계
1. 대상 워치 플랫폼 확정
2. `:watch` / `:watch-contracts` 모듈 추가
3. Wear OS Data Layer 통신 구성
4. 모바일 앱 큐/커서 모델과 `hr.ingest` 중계 구현
5. Health Connect 기반 수면 데이터 연동
6. Samsung Health Sensor SDK 기반 실제 심박/IBI 수집 경로 연결
7. 디바이스 환경에서 통합 검증

## 고려 사항
- 워치 OS별 API 차이
- 제조사별 센서 제약
- 백그라운드 동기화 제한
- 배터리 소모 최소화
- 기존 워치 debug 패키지 `com.sleepcare.watch`가 남아 있으면 통합 테스트가 헷갈릴 수 있으므로 새 watch APK 설치 전 수동 삭제가 필요할 수 있다.

## 협업 논의 포인트
- Samsung Health Sensor SDK release signing key와 SDK policy 등록 검증
- `session.error` 이후 모바일 세션 복구/자동 eye-only 전환 정책
- 동기화 주기와 실패 처리 기준
- 참고 의견: 1차 범위는 워치 단 수면 분석보다 학습 세션의 실시간 심박 연동 안정화가 우선이다.

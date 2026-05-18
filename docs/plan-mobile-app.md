# 모바일 앱 계획

## 목표
- 전체 서비스의 중심 허브 역할을 수행한다.
- 학습 세션과 졸음 이벤트를 수집, 저장, 분석, 시각화한다.
- 사용자 맞춤 수면 스케줄 제안을 제공한다.

## 현재 구현 상태
- 모바일 앱 ↔ 라즈베리파이 연결은 로컬 네트워크 `NSD + WSS` 기준으로 구현되어 있다.
- 홈 화면에서 공부 시작/종료, 세션 타이머, Pi 기반 실시간 위험 상태를 표시한다.
- `drowsiness_events`, `study_sessions`는 실제 Pi 이벤트를 저장한다.
- `:watch-contracts` 기준으로 Wear OS Data Layer 연결 상태, 워치 세션 시작/중지, `session.ready / error / closed`, 심박 샘플 큐/커서, `hr.ingest` 중계 경로를 구현했다.
- Pi QR 인식이 실패하는 현장 상황을 위해 pairing JSON을 직접 붙여 넣어 기존 QR 검증/등록 경로로 저장할 수 있다.
- 설정의 개발자 모드를 켜면 Pi 공부 세션 없이 폰 ↔ 워치 Data Layer 명령 전체를 기기 연결 화면에서 테스트할 수 있다.
- 워치 Data Layer 전달을 위해 폰/워치 런타임 `applicationId`는 `com.sleepcare.mobile`로 맞추고, 워치 Kotlin `namespace`만 `com.sleepcare.watch`로 유지한다.
- 실제 수면 데이터는 Health Connect 기반으로 읽고, 홈/분석/설정 화면에서 권한 없음/미지원/업데이트 필요/데이터 없음 상태를 구분한다.
- 설정 화면에서 Health Connect 수면 읽기 권한을 직접 요청할 수 있고, 앱 manifest에는 `android.permission.health.READ_SLEEP`를 선언했다.
- Health Connect 앱에서 이 앱이 노출되도록 onboarding activity, rationale activity, provider query를 manifest에 반영했다.
- 권한 팝업이 바로 뜨지 않는 경우를 위해 설정 화면에서 Health Connect 관리 화면과 업데이트 화면으로 직접 이동할 수 있다.
- 분석 화면의 수면 점수는 `최근 7일 수면 점수`로 표시하고, 규칙성은 취침/기상 시각 일관성 기준으로 계산한다.
- 주간 수면 리듬은 `기상한 날짜` 기준 `대표 수면 1개 + 추가 수면` 구조를 사용하며, `3시간 이하 공백`의 분할 밤잠은 병합한다.
- 홈의 `어제 수면 상태` 카드도 같은 수면일 집계 규칙을 사용한다.
- `androidx.health.connect:connect-client:1.1.0` 요구사항에 맞춰 Android 모듈 `compileSdk`를 36으로 올렸다.

## 주요 기능
- 라즈베리파이 연결 및 상태 확인
- 학습 세션 시작/종료 및 타이머 표시
- 라즈베리파이 졸음 이벤트 수신과 기록 저장
- 라즈베리파이 위험도 업데이트 수신과 홈/분석 UI 반영
- 수면 기록 empty state 표시
- 통합 분석 리포트
- 수면 스케줄 제안
- 학습 플랜 입력
- 시험 날짜 입력
- 알림 및 리마인더

## 기술 스택
### 앱 본체
- Kotlin
- Jetpack Compose + Material 3
- MVVM + Repository + UDF(단방향 데이터 흐름)
- Android 공식 가이드를 기준으로 UI 레이어와 Data 레이어를 분리한다.

### 상태관리 / 비동기 / DI
- ViewModel
- Kotlin Coroutines + Flow
- Hilt
- 세션 상태, 수면 기록 조회, 추천 결과 계산, 화면 상태 갱신을 분리하기 좋은 조합으로 본다.

### 로컬 저장
- Room: 학습 세션, 졸음 이벤트, 수면 세션, 시험 일정, 학습 플랜, 추천 결과 캐시
- DataStore: 설정값, 마지막 동기화 시각, 사용자 선호값

### 백그라운드 작업
- WorkManager
- 현재 릴리스에서는 핵심 흐름이 Flow 기반 실시간 처리이며, 주기적 수면 동기화 워커는 비활성 상태다.

### 라즈베리파이 연동
- 로컬 네트워크(NSD + WSS)
- 모바일 앱은 파이 서비스 발견과 WebSocket 클라이언트 역할을 담당한다.
- 현재 구현 범위는 `hello`, `session.open`, `risk.update`, `alert.fire`, `session.close`, `session.summary`, `ping/pong`이다.
- 보안은 QR로 등록한 Pi 인증서 public key의 SPKI SHA-256 fingerprint를 pin으로 저장하고 WSS 연결 시 대조한다.
- QR 스캔이 어려울 때는 `sleepcare-pair-v1` JSON payload를 직접 입력해 같은 `PiPairingCodec` 검증을 거쳐 등록한다.

### 워치 연동
- Wear OS Data Layer
- Galaxy Watch + Samsung Health Sensor SDK 조합을 기준으로 한다.
- 워치 앱은 Samsung Health Sensor SDK `HEART_RATE_CONTINUOUS` tracker로 실제 심박/IBI를 읽고, `session.ready.sensor_backend=samsung-health-sensor-sdk`로 준비 완료를 알린다.
- SDK AAR은 워치 모듈의 `watch/libs/samsung-health-sensor-api.aar`에 로컬로 배치하며 Git에 커밋하지 않는다.
- Data Layer가 워치 앱 listener까지 메시지를 전달하려면 폰/워치 `applicationId`와 signing key가 모두 같아야 한다.
- capability는 `sleepcare_watch_session_runtime`을 제공하는 워치 앱 노드를 고르는 필터이며, package/signature 정합성 조건을 대신하지 않는다.
- 모바일 앱은 워치 연결 상태, ACK 커서, 백필 요청, `hr.ingest`, 진동 경고 요청을 지원하도록 확장한다.
- 개발자 모드의 워치 통신 테스트는 `start`, `stop`, `flush policy`, `vibration`, `ack`, `backfill`을 Pi 없이 전송해 워치 앱 readiness와 Data Layer 계약을 검증한다.
- 운영 공부 세션은 `sleepcare_watch_session_runtime` capability가 확인된 워치 앱 노드에만 명령을 보낸다.
- 개발자 모드 워치 테스트는 capability를 먼저 사용하되, capability discovery 문제가 의심될 때 페어링된 Wear OS 노드로 직접 전송해 Data Layer 연결과 워치 앱 수신 여부를 분리 진단한다.
- 폰의 전송 성공은 워치 앱 수신 확인이 아니므로, 워치 앱 설정 화면의 `Message Log`와 `adb logcat -s SleepCareWatch`로 listener 수신/서비스 처리/ready 회신 단계를 함께 확인한다.
- 워치 센서 시작 실패는 권한, Health Platform 설치/버전, SDK policy, tracker 미지원 원인을 `session.error`로 회신한다.
- 2026-05-06 실기기 테스트에서는 capability가 확인된 상태에서도 manifest listener 로그가 없었고, 워치 앱을 연 상태의 live listener에서는 전체 테스트 명령이 성공했다. 따라서 현재 운영 리스크는 Data Layer 계약보다 백그라운드 listener wake-up 안정성에 있다.
- 기존 워치 debug 패키지 `com.sleepcare.watch`가 남아 있으면 테스트 대상이 헷갈릴 수 있으므로 새 APK 설치 전 `adb -s <watch> uninstall com.sleepcare.watch`로 제거한다.

### 수면 데이터 연동
- Health Connect 기반 실제 연동을 사용한다.
- 워치 앱은 수면 기록을 직접 읽지 않고, 모바일 앱이 수면 세션을 읽어 분석/추천에 반영한다.
- `잠들기 지연`은 원본 앱별 신뢰도 차이 때문에 현재 분석 UI에서 제외하고, 총 수면 시간·밤중 각성·취침/기상 일관성 중심으로 표시한다.
- 계산식과 집계 규칙의 최신 문서는 [sleep-metrics.md](./sleep-metrics.md)를 참조한다.

## 수면 스케줄 제안 기능
### 학습 플랜 기반
- 공부 시간표 또는 학습 플랜 입력
- 공부 시간대와 기상 시간을 반영한 취침 시간 제안
- 졸음이 잦은 시간대에 맞춘 휴식 가이드 제안

### 시험 날짜 기반
- 시험 날짜 입력
- 시험 시간에 맞춘 기상 리듬 조정 제안
- 시험 직전 기간의 취침 시간 가이드 제공

### 통합 추천 기준 초안
- 공부 중 졸음 빈도
- 시험일까지 남은 기간
- 사용자가 원하는 기상 시간
- 최근 수면 데이터가 없으면 "수면 기록 기반 보정 없음" 전제로 추천을 생성한다.

## 화면 구조 초안
- 온보딩
- 기기 연결
- 홈 대시보드
- 학습 세션 상태
- 졸음 분석
- 수면 분석
- 수면 스케줄 제안
- 학습 플랜 입력
- 시험 일정 관리
- 설정

## 구현 단계
1. 앱 골격과 라우팅 구성
2. 라즈베리파이 연결과 세션 제어 구현
3. 데이터 저장 구조 설계
4. 기초 리포트와 시각화 구현
5. 규칙 기반 추천 MVP 구현
6. 워치 실시간 데이터 수신과 중계 구현
7. Health Connect 수면 연동 및 상태 분기 구현
8. Samsung Health Sensor SDK 실기기 연동 검증

## 기술 설계 메모
- 신규 화면은 Compose 기준으로 설계하고 Material 3 컴포넌트를 사용한다.
- 화면 상태는 ViewModel에서 관리하고 Flow 기반으로 UI에 전달한다.
- Repository 계층에서 라즈베리파이 통신, 학습 세션 상태, 수면 데이터 소스를 분리한다.
- Room은 구조화된 세션 및 분석 데이터 저장소로 사용하고 DataStore는 경량 설정 저장에 한정한다.
- 실제 수면 데이터가 없을 때도 홈/분석/추천이 동작하도록 empty state를 명시적으로 유지한다.
- 공부 세션은 홈 카드에서 `워치 포함` 또는 `Eye only` 모드를 선택해 시작한다.
- 워치 포함 모드는 `ArmingWatch -> OpeningSession -> Running` 전이를 사용하고, 워치 `ready` 전에는 Pi 세션을 열지 않는다.
- Eye only 모드는 워치 handshake를 생략하고 Pi 카메라 세션을 바로 준비하며, 자동 fallback이 아니라 사용자가 명시적으로 선택한 경우에만 동작한다.
- 개발자 모드 워치 테스트는 운영 공부 세션으로 저장하지 않고 Pi `session.open`도 보내지 않는다.
- 개발자 모드의 paired-node fallback은 테스트 카드 전용이며, 운영 공부 세션의 capability-only 정책을 대체하지 않는다.

## 협업 논의 포인트
- 홈 화면 핵심 카드 우선순위
- 세션 시작/종료 UX 방식
- 분석 결과 시각화 방식
- 수면 데이터 실제 연동 경로
- Health Connect onboarding/permissions 배포 시 개인정보처리방침 문구 정합성
- Samsung Health Sensor SDK 실기기 연결 방식
- 워치 앱 연결 시점과 프로토콜 세부 확장 범위
- 참고 의견: 홈 화면은 "어제 수면 상태", "최근 졸음 빈도", "오늘 추천 취침 시간" 중심 구성이 적합하다.
- 참고 의견: 앱은 많은 정보를 보여주는 것보다 행동으로 이어지는 제안을 중심으로 설계하는 편이 좋다.

> 참고: 개발자 모드의 Pi 개발 테스트 카드는 운영 공부 세션과 분리되어 직접 endpoint, SPKI 읽기, pairing JSON 생성/등록, NSD 후보 검색, hello, Eye-only, Synthetic HR, 종료를 검증한다. Pi 개발자가 따라갈 사용 설명서는 [Pi 개발자 디버그 가이드](./pi-developer-debug-guide.md)에 둔다.

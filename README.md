# Sleep Care Mobile

수험생의 공부 중 졸음과 수면 패턴을 함께 분석해 수면 루틴을 제안하는 Android 앱 프로젝트입니다.

현재 이 저장소는 기획 문서와 Stitch 산출물을 바탕으로, `Kotlin + Jetpack Compose` 기반 Android 앱 MVP와 Pi 연동 1차 구현까지 완료된 상태입니다.

## 현재 상태

- Android 네이티브 앱 프로젝트 생성 완료
- Compose 기반 화면 흐름 구현 완료
- 온보딩, 홈, 분석, 기기 연결, 수면 스케줄, 학습 플랜, 시험 일정, 설정 화면 추가
- Room/DataStore 기반 로컬 저장 구조 추가
- Raspberry Pi 연동을 `NSD + WSS` 기반 실제 네트워크 구조로 전환
- 홈 화면의 공부 시작/종료, 세션 타이머, 실시간 Pi 위험 상태 반영
- `study_sessions`, `drowsiness_events` 기반 실제 세션/알림 저장
- Health Connect 기반 수면 읽기, 권한 요청, 상태 분기, 앱 목록 노출용 onboarding/rationale 엔트리 추가
- 수면 분석 화면을 Stitch 산출물 방향으로 재구성하고, `최근 7일 수면 점수`, `주간 수면 리듬`, `최근 수면 구조` 카드 반영
- 주간 수면 집계는 `기상한 날짜` 기준 `대표 수면 1개 + 추가 수면` 구조와 `3시간 이하 공백 병합`을 사용
- 홈 화면의 `어제 수면 상태` 카드도 같은 집계 규칙을 따르도록 수정
- 워치 앱은 companion 스캐폴드와 세션 중계 구조까지 구현되었고, 실제 Samsung 센서 backend 연결은 남아 있음
- 규칙 기반 추천 엔진 추가
- 수면/추천 계산식 문서 추가: [docs/sleep-metrics.md](./docs/sleep-metrics.md)
- 단위 테스트 추가
- `testDebugUnitTest` 성공
- 실기기 수동 테스트 확인

## 기술 스택

- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- Hilt
- Room
- DataStore
- OkHttp(WebSocket/TLS)
- Android NSD

## 주요 폴더

- `app/src/main/java/com/sleepcare/mobile/navigation`
  앱 진입점과 전체 라우팅
- `app/src/main/java/com/sleepcare/mobile/ui`
  기능별 Compose 화면과 공통 컴포넌트
- `app/src/main/java/com/sleepcare/mobile/domain`
  도메인 모델, 저장소 인터페이스, 점수 계산 로직
- `app/src/main/java/com/sleepcare/mobile/data`
  Room/DataStore, Pi 네트워크 데이터 소스, 저장소 구현, 추천 엔진
- `docs`
  제품/아키텍처/데이터 기획 문서
- `stitch_exports/onboarding`
  Stitch 화면 HTML, 이미지, 디자인 시스템 산출물

## 구현된 화면

- Onboarding
- Home Dashboard
- Analysis Hub
- Sleep Analysis Detail
- Drowsiness Analysis Detail
- Device Connection
- Sleep Schedule Suggestion
- Study Plan
- Exam Schedule
- Settings

## 실행 및 테스트

### 필수 조건

- JDK 17
- Android SDK
- `local.properties`에 SDK 경로 설정

현재 저장소의 `local.properties` 예시:

```properties
sdk.dir=/path/to/Android/Sdk
```

### Gradle 확인

```bash
./gradlew -version
./gradlew help
```

### 단위 테스트

```bash
./gradlew clean testDebugUnitTest
```

현재 기준으로 `testDebugUnitTest`는 성공했습니다.

Codex 샌드박스처럼 홈 디렉터리 쓰기 제약이 있는 환경에서는 Kotlin daemon이 fallback 컴파일로 동작할 수 있습니다. 그런 경우 아래처럼 `GRADLE_OPTS`를 함께 주는 편이 안전했습니다.

```bash
GRADLE_OPTS='-Duser.home=/tmp' ./gradlew clean testDebugUnitTest
```

### 수동 테스트

실기기에서 기본 플로우 동작을 확인했습니다.

- 앱 실행 및 온보딩 진입
- 홈/분석/스케줄/설정 탭 이동
- 상세 화면 진입 및 뒤로 가기
- 로컬 저장 상태 유지
- 홈 화면에서 공부 세션 시작/종료
- Pi 연결 상태 및 실시간 졸음 이벤트 반영
- Health Connect 권한 요청 및 직접 설정 진입 동작
- 수면 분석 상세 화면과 홈 `어제 수면 상태` 카드 갱신

## 현재 확인된 이슈

- 프로젝트 설정 기준 `compileSdk`는 `36`이며, 로컬 Android SDK에 해당 플랫폼이 없으면 빌드 환경 차이로 문제가 날 수 있음
- Android Gradle Plugin 버전과 로컬 SDK 설치 상태가 어긋나면 Build Tools 관련 경고가 출력될 수 있음
- 샌드박스 환경에서는 Kotlin daemon 임시 파일 경로 문제로 fallback 컴파일이 발생할 수 있음
- 모바일 앱은 Pi와의 `NSD + WSS` 연결을 구현했지만, 실제 Pi 펌웨어/서버와의 통합 검증은 같은 로컬 Wi-Fi 환경에서 계속 확인이 필요함
- 워치 앱은 companion 구조와 `hr.ingest` 중계까지 반영됐지만, Samsung Health Sensor SDK 실센서 backend 연결은 아직 남아 있음
- Gradle Kotlin DSL 캐시가 깨진 환경에서는 로컬 빌드가 캐시 오류로 중단될 수 있음
- Health Connect 원본 앱마다 sleep stage 밀도가 달라 `잠들기 지연`은 신뢰도 문제가 있어 현재 분석 UI에서 제외했다

## 현재 구현 범위 요약

- 모바일 앱 ↔ 라즈베리파이: 구현됨
- 로컬 Wi-Fi에서 `_sleepcare._tcp` NSD 탐색
- `WSS` 연결
- `hello`, `session.open`, `risk.update`, `alert.fire`, `session.close`, `session.summary` 처리
- 모바일 앱 ↔ 워치 앱: companion 세션/큐/커서/진동 경고 구조 구현, 실센서 backend 일부 미구현
- 수면 데이터: Health Connect 기반 실제 연동 구현
- 분석 화면: 최근 7일 수면 점수, 취침/기상 시각 일관성 기반 규칙성, 주간 수면 리듬 표시
- 홈 화면: `어제 수면 상태` 카드가 분할 밤잠 병합과 추가 수면 합산 규칙을 반영

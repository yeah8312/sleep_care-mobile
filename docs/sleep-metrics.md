# Sleep Metrics And Recommendation Formulas

이 문서는 `Sleep Care` 앱이 2026년 4월 23일 기준으로 사용하는 수면 분석 및 추천 계산식을 정리한다.

## 1. 원본 수면 세션 처리

원본 데이터는 Health Connect `SleepSessionRecord`에서 읽는다.

- 기본 세션 시간: `endTime - startTime`
- 밤중 각성 시간: stage 중 `AWAKE`, `AWAKE_IN_BED`, `OUT_OF_BED` 합
- 수면 stage 시간: stage 중 `LIGHT`, `DEEP`, `REM`, `SLEEPING` 합

### 분할 밤잠 병합

주간 분석과 홈 대시보드 집계 전에는 인접한 수면 세션을 먼저 병합한다.

- 공백이 `3시간 이하`
- 두 세션 중 하나라도 야간 수면 성격
  - 자정 넘김
  - 시작 시각이 `18:00` 이후
  - 종료 시각이 `10:00` 이전

병합 시:

- 시작 시각: 첫 세션 시작
- 종료 시각: 마지막 세션 종료
- 총 수면 시간: 전체 구간 길이
- 밤중 각성 시간: 두 세션의 각성 시간 + 중간 공백

## 2. 수면일(Sleep Day) 집계

주간 수면 리듬과 분석 점수는 세션 그대로 쓰지 않고 `기상한 날짜` 기준으로 묶는다.

- 예: 토요일 밤에 자서 일요일 아침에 일어나면 `일요일 수면일`

수면일마다:

- `primarySession`: 대표 야간 수면 1개
- `totalMinutes`: 해당 날짜로 귀속된 전체 수면 시간
- `extraSleepMinutes`: `totalMinutes - primarySession.totalMinutes`

`extraSleepMinutes`는 낮잠 또는 분할 수면 후반부를 포괄하는 보조 지표다. UI에서는 `추가 수면`으로만 표시하고, 낮잠이라고 단정하지 않는다.

## 3. 규칙성 점수

수면 분석 화면의 `규칙성`은 더 이상 sleep stage 비율이 아니라 `취침/기상 시각 일관성`이다.

### 취침 시각 정규화

취침 시각은 야간 축 기준으로 비교하기 위해 자정 이후 값을 다음 날로 넘긴다.

- `00:30` -> `24:30`
- `02:10` -> `26:10`

### 기상 시각 정규화

기상 시각은 일반적인 오전 기상 패턴 기준으로 그대로 사용한다.

### 점수 계산

최근 7일 대표 수면에 대해:

1. 평균 취침 시각 계산
2. 평균 기상 시각 계산
3. 각 날짜의 취침 시각과 평균 취침 시각 차이의 절대값 평균 계산
4. 각 날짜의 기상 시각과 평균 기상 시각 차이의 절대값 평균 계산

패널티:

- 취침 패널티: `평균 취침 편차(분) / 5`, 최대 25점
- 기상 패널티: `평균 기상 편차(분) / 4`, 최대 30점

최종 점수:

```text
regularityScore = 100 - bedtimePenalty - wakePenalty
regularityScore는 35~100으로 제한
```

수면일이 1개뿐이면 규칙성은 임시로 `85점`을 사용한다.

## 4. 최근 7일 수면 점수

수면 분석 화면의 상단 `최근 7일 수면 점수`는 다음 주간 평균값으로 계산한다.

- `averageMinutes`: 최근 7일 수면일의 `totalMinutes` 평균
- `regularityScore`: 위 규칙성 점수
- `awakeMinutes`: 최근 7일 대표 수면의 밤중 각성 시간 평균

최종 식:

```text
durationScore = totalMinutes / 4.8, 최대 40점
consistencyPart = regularityScore * 0.35, 최대 35점
awakePenalty = awakeMinutes, 최대 10점

weeklySleepScore = durationScore + consistencyPart + 25 - awakePenalty
weeklySleepScore는 35~100으로 제한
```

분석 화면에서는 `latencyMinutes`를 사용하지 않는다.

## 5. 홈의 어제 수면 상태

홈의 `어제 수면 상태`는 가장 최근 수면일 기준으로 계산한다.

- 총 수면 시간: 그 수면일의 `totalMinutes`
- 점수: 위 하루 수면 블록에 대해 `ScoreCalculator.sleepQuality()` 재계산
- 규칙성 입력값: 해당 대표 수면의 기존 세션 점수 사용

즉 홈은 최신 하루 중심, 분석 화면은 최근 7일 중심이다.

## 6. 권장 기상 시각

추천 엔진은 다음 우선순위로 `baselineWakeTime`을 정한다.

1. 앞으로 14일 내 가장 가까운 시험의 시작 90분 전
2. 사용자가 설정한 목표 기상 시각
3. 공부 계획 시작 시각 90분 전
4. 기본값 `06:30`

## 7. 권장 취침 시각

먼저 목표 수면 시간을 정한다.

- 최근 3개 수면 평균이 `390분` 미만이거나
- 최근 졸음 이벤트가 3개 이상이면
- 목표 수면 시간 = `480분`

그 외에는:

- 목표 수면 시간 = `450분`

권장 취침 시각:

```text
recommendedBedtime = baselineWakeTime - targetSleepMinutes - 15분
```

여기서 추가 15분은 취침 준비 여유 시간이다.

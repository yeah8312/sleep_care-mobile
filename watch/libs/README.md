# Samsung Health Sensor SDK AAR

이 폴더에는 Samsung Developer에서 받은 Samsung Health Sensor SDK AAR을 로컬로 둡니다.

필수 파일명:

```text
watch/libs/samsung-health-sensor-api.aar
```

이 파일은 Git에 커밋하지 않습니다. SDK 라이선스, 용량, 개발자별 배포 경로가 섞이지 않게 하기 위해 `.gitignore`의 `*.aar` 규칙을 그대로 따릅니다.

## 빌드 전 확인

워치 모듈은 실제 SDK 타입을 직접 import합니다. 따라서 AAR이 없으면 `:watch:preBuild`에서 다음 이유로 빌드가 중단됩니다.

```text
Samsung Health Sensor SDK AAR이 필요합니다.
```

## 실기기 테스트 메모

- SDK policy 오류가 나면 Samsung Health Platform 개발자 모드, 앱 package name, signing key SHA-256, 허용 tracker type 등록 상태를 확인합니다.
- SleepCare 워치 앱의 런타임 `applicationId`는 폰 앱과 같은 `com.sleepcare.mobile`입니다. Samsung SDK policy 등록도 이 값을 기준으로 맞춥니다.
- 로그 확인은 `adb logcat -s SleepCareWatch`를 사용합니다.

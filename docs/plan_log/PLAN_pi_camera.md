# QR 기반 Pi 신뢰 등록 및 SPKI Pinning 구현 계획

## Summary
- 기존 앱 내장 `sleepcare_pi_dev_cert.pem` 신뢰 방식에서, **Pi가 QR로 배포한 SPKI SHA-256 fingerprint를 앱에 등록하고 TLS 연결 시 검증하는 방식**으로 전환한다.
- QR 등록 UX는 **카메라 스캔 포함**으로 구현한다.
- Pi 개발팀이 바로 맞춰 구현할 수 있도록 `docs/pi-qr-pairing.md`를 새로 만들고, 기존 통신 설계 문서에는 링크와 보안 요약을 추가한다.

## Key Changes
- QR payload 표준:
  - QR 내용은 UTF-8 JSON 문자열로 고정한다.
  - 필수 필드: `proto="sleepcare-pair-v1"`, `device_id`, `service="_sleepcare._tcp"`, `ws`, `tls=1`, `spki_sha256`.
  - `spki_sha256`은 DER-encoded SPKI bytes의 SHA-256을 Base64로 표현한다.
  - 선택 필드: `display_name`, `issued_at_ms`, `key_id`, `pin_hint`.
- 앱 등록 저장:
  - `PreferencesStore`에 trusted Pi 정보 저장/삭제/관찰 API를 추가한다.
  - 저장값은 `device_id`, `display_name`, `service`, `ws_path`, `spki_sha256`, `registered_at_ms`를 JSON 문자열 1개로 보관한다.
- TLS 검증:
  - `PiNetworkDataSourceImpl`은 더 이상 raw resource 인증서를 기본 신뢰 앵커로 쓰지 않는다.
  - WSS 연결 시 서버 leaf certificate의 public key SPKI SHA-256을 계산하고, 저장된 `spki_sha256`과 일치할 때만 연결 성공 처리한다.
  - 불일치 시 연결 상태를 `Failed`로 두고 메시지는 “등록된 Pi 인증 정보와 다름” 계열로 분리한다.
  - `hostnameVerifier { _, _ -> true }`는 로컬 네트워크 한계상 유지하되, SPKI pin 검증을 필수 보안 조건으로 둔다.
- QR 등록 UI:
  - 기기 연결 화면에 “새 Pi 등록” 액션을 추가한다.
  - CameraX + ML Kit Barcode Scanning 의존성을 추가해 QR을 스캔한다.
  - 스캔 성공 시 payload를 파싱하고, 기기명/device_id/SPKI 짧은 지문을 보여준 뒤 사용자가 등록한다.
  - 등록 완료 후 즉시 NSD 발견 + WSS 검증 연결을 시도한다.
- 문서:
  - `docs/pi-qr-pairing.md` 생성: QR 생성 방법, SPKI 계산법, payload 예시, Pi 서버 인증서 요구사항, 앱 연결 흐름, 오류 처리 기준을 Pi 개발자 관점으로 설명한다.
  - `docs/sleepcare_protocol_design.md`의 보안/기기 등록 섹션에 “QR + SPKI SHA-256” 방식을 확정 내용으로 갱신한다.
  - `docs/plan-raspberry-pi.md`에 새 pairing 문서 링크와 Pi 쪽 구현 체크리스트를 추가한다.

## Public Interfaces / Types
- 도메인 모델 추가:
  - `TrustedPiDevice(deviceId, displayName, serviceType, wsPath, spkiSha256, registeredAtMs)`
  - `PiPairingPayload(proto, deviceId, displayName?, service, ws, tls, spkiSha256, issuedAtMs?, keyId?)`
- repository/data source 변경:
  - `DeviceConnectionRepository`에 `observeTrustedPi()`, `registerPiFromQr(rawPayload)`, `forgetPi()` 추가.
  - `PiNetworkDataSource`는 등록된 Pi가 없으면 연결 시도를 실패 처리하고, QR 등록 안내 상태를 노출한다.
- QR parser:
  - JSON 파싱 실패, 필수 필드 누락, `proto` 불일치, `tls != 1`, SPKI Base64 형식 오류를 명확한 에러로 반환한다.

## Test Plan
- 단위 테스트:
  - 유효한 QR JSON이 `PiPairingPayload`와 `TrustedPiDevice`로 변환되는지 검증.
  - 필수 필드 누락, 잘못된 `proto`, `tls=0`, invalid Base64를 거부하는지 검증.
  - PEM/X.509 certificate에서 SPKI SHA-256을 계산해 기대 Base64 pin과 일치하는지 검증.
  - 저장된 pin과 서버 인증서 pin 불일치 시 연결 실패 상태가 되는지 검증.
- 수동 테스트:
  - Pi QR 스캔 → 확인 화면 → 등록 → 자동 연결 시도.
  - 등록된 Pi가 없는 상태에서 연결 화면이 “Pi 등록 필요”로 보이는지 확인.
  - 다른 인증서/키를 가진 Pi가 같은 `device_id`로 발견될 때 인증 오류로 차단되는지 확인.
  - 등록 해제 후 다시 연결 시 QR 등록을 요구하는지 확인.

## Assumptions
- 이번 범위는 **앱의 QR 스캔 등록, SPKI pin 검증, 문서화**까지 포함한다.
- QR은 매 연결마다 쓰지 않고, **최초 등록 또는 키 교체 시 재등록**에만 사용한다.
- Pi는 자체 TLS 인증서를 계속 제시하며, QR에는 인증서 전체가 아니라 **SPKI SHA-256 fingerprint**만 넣는다.
- Pi의 키쌍이 바뀌면 기존 등록은 실패하고 사용자는 QR로 재등록해야 한다.
- 개발용 raw 인증서 파일은 후속 호환용으로 남길 수 있지만, 기본 연결 경로에서는 등록된 QR pin을 우선 사용한다.

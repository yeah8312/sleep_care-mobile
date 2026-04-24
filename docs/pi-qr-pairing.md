# SleepCare Pi QR Pairing Guide

이 문서는 라즈베리파이 개발자가 모바일 앱과 맞춰 구현해야 하는 QR 기반 신뢰 등록 규격을 정리한다.

## 목표

- 앱은 최초 1회 Pi의 QR을 스캔해 신뢰 정보를 저장한다.
- QR에는 인증서 전체가 아니라 TLS 인증서 공개키의 **SPKI SHA-256 fingerprint**를 넣는다.
- 이후 앱은 NSD로 등록된 Pi를 찾고, WSS 연결 시 서버 인증서의 SPKI fingerprint가 등록값과 같은지 검증한다.
- Pi 키쌍이 바뀌면 앱에서 QR 재등록이 필요하다.

## QR Payload

QR 내용은 UTF-8 JSON 문자열이다.

```json
{
  "proto": "sleepcare-pair-v1",
  "device_id": "deskpi-a1",
  "display_name": "SleepCare Pi Desk",
  "service": "_sleepcare._tcp",
  "ws": "/ws",
  "tls": 1,
  "spki_sha256": "BASE64_SHA256_OF_DER_SPKI",
  "issued_at_ms": 1775578000000,
  "key_id": "deskpi-a1-2026-04",
  "pin_hint": "optional short display hint"
}
```

필수 필드:

| 필드 | 값 |
|---|---|
| `proto` | 항상 `sleepcare-pair-v1` |
| `device_id` | NSD TXT record의 `device_id`와 같은 안정적인 ID |
| `service` | 항상 `_sleepcare._tcp` |
| `ws` | WebSocket path, 예: `/ws` |
| `tls` | 항상 `1` |
| `spki_sha256` | DER-encoded SubjectPublicKeyInfo bytes의 SHA-256을 Base64로 인코딩한 값 |

선택 필드:

| 필드 | 용도 |
|---|---|
| `display_name` | 앱 UI에 보여줄 이름 |
| `issued_at_ms` | QR 생성 시각, Unix epoch milliseconds |
| `key_id` | Pi 키 교체/운영 추적용 ID |
| `pin_hint` | 사람이 확인할 짧은 힌트 |

## SPKI SHA-256 생성

앱은 인증서 전체 fingerprint가 아니라 공개키 SPKI fingerprint를 비교한다. 인증서를 같은 키쌍으로 재발급하면 pin은 유지된다.

OpenSSL 예시:

```bash
openssl x509 -in sleepcare_pi_cert.pem -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary \
  | openssl base64
```

Python 예시:

```python
import base64
import hashlib
from cryptography import x509
from cryptography.hazmat.primitives import serialization

cert = x509.load_pem_x509_certificate(open("sleepcare_pi_cert.pem", "rb").read())
spki = cert.public_key().public_bytes(
    encoding=serialization.Encoding.DER,
    format=serialization.PublicFormat.SubjectPublicKeyInfo,
)
print(base64.b64encode(hashlib.sha256(spki).digest()).decode("ascii"))
```

## Pi 서버 요구사항

- NSD/DNS-SD 서비스 타입은 `_sleepcare._tcp`를 사용한다.
- TXT record는 최소 `proto=v1`, `tls=1`, `ws=/ws`, `device_id=<QR과 같은 값>`을 포함한다.
- WSS 서버는 QR의 `spki_sha256`을 만든 인증서와 같은 키쌍의 인증서를 제시한다.
- 앱은 발견된 `device_id`와 `ws`가 QR 등록값과 다르면 연결하지 않는다.
- 앱은 TLS handshake 중 서버 leaf certificate의 SPKI SHA-256을 계산해 QR 등록값과 다르면 연결을 차단한다.

## 앱 연결 흐름

1. 사용자가 Pi 화면 또는 Pi 설정 페이지에 표시된 QR을 앱에서 스캔한다.
2. 앱은 payload를 검증하고 `device_id`, `service`, `ws`, `spki_sha256`을 로컬에 저장한다.
3. 앱은 NSD로 `_sleepcare._tcp`를 탐색한다.
4. 발견된 서비스의 `device_id`와 `ws`가 등록값과 일치하면 `wss://host:port/ws`로 연결한다.
5. TLS handshake에서 SPKI pin이 일치하면 `hello`를 보내고, Pi의 `hello_ack`를 기다린다.
6. 이후 기존 `session.open`, `risk.update`, `alert.fire`, `session.close`, `session.summary` 프로토콜을 사용한다.

## 오류 처리 기준

| 상황 | 앱 동작 |
|---|---|
| QR JSON 파싱 실패 | QR 등록 실패, 다시 스캔 안내 |
| `proto` 불일치 | 지원하지 않는 QR로 거부 |
| `tls != 1` | 보안 연결 필수 조건 위반으로 거부 |
| `spki_sha256` Base64 오류 | QR 등록 실패 |
| 등록된 Pi 없음 | 연결 화면에서 QR 등록 필요 안내 |
| NSD에서 등록된 `device_id` 미발견 | 같은 Wi-Fi 확인 안내 |
| SPKI pin 불일치 | 보안 오류로 연결 차단, QR 재등록 안내 |

## 키 교체 운영

- 인증서를 재발급하되 같은 키쌍을 유지하면 앱 재등록은 필요 없다.
- 키쌍까지 교체하면 QR의 `spki_sha256`이 바뀌므로 앱에서 재등록해야 한다.
- `key_id`는 운영자가 어떤 키로 QR을 발급했는지 추적하기 위한 선택 필드다.

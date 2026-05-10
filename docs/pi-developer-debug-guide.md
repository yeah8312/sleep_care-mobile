# Pi 개발자 디버그 가이드

이 문서는 Pi 개발자가 앱 개발자 도움 없이도 **QR, Avahi, WSS, hello, session.open, hr.ingest, session.close 중 어디까지 동작하는지** 순서대로 확인할 수 있게 만든 사용 설명서다.

중요한 원칙은 하나다. **디버그 전용 프로토콜을 새로 만들 필요가 없다.** Android 앱의 개발자 모드 Pi 테스트 도구는 운영 프로토콜인 `hello`, `session.open`, `hr.ingest`, `session.close`만 보낸다. Pi 서버는 이 네 가지와 응답 메시지만 구현하면 된다.

## 0. 준비물

앱에서 할 일:

- Android 앱에서 `설정` 화면으로 이동한다.
- `개발자 모드` 스위치를 켠다.
- `기기 연결` 화면으로 이동한다.
- `개발자 모드 · Pi 개발 테스트` 카드가 보이는지 확인한다.

Pi에서 준비할 것:

- Android 폰과 Pi가 같은 Wi-Fi에 연결되어 있어야 한다.
- Pi IP 주소를 알고 있어야 한다. 예: `192.168.0.45`
- WSS 포트를 정해야 한다. 예: `8765`
- WebSocket path를 정해야 한다. 예: `/ws`
- TLS 인증서와 개인키가 있어야 한다.
- Pi 서버 로그에서 수신 JSON을 한 줄씩 볼 수 있어야 한다.

용어를 먼저 정리한다.

- `WSS`: TLS가 적용된 WebSocket이다. 앱은 보안 없는 `ws://`로 우회하지 않는다.
- `SPKI`: 서버 인증서의 public key 지문이다. 앱은 이 값을 pin으로 저장해 “내가 등록한 Pi 인증서인지” 확인한다.
- `pairing JSON`: QR에 들어가는 JSON과 같은 내용이다. QR 인식이 안 돼도 앱에서 직접 만들고 등록할 수 있다.
- `Avahi/NSD`: 같은 Wi-Fi 안에서 `_sleepcare._tcp` 서비스를 찾는 기능이다. 초기에는 없어도 직접 endpoint 테스트부터 시작할 수 있다.

## 1. WSS 서버 먼저 띄우기

앱에서 누를 버튼:

- 아직 누르지 않는다. 먼저 Pi 서버가 떠 있어야 한다.

Pi에서 확인할 로그:

```bash
hostname -I
ss -ltnp | grep 8765
```

기대 예시:

```text
192.168.0.45
LISTEN 0 128 0.0.0.0:8765 ...
```

서버 시작 로그는 이런 식이면 좋다.

```text
[sleepcare-pi] WSS listening on 0.0.0.0:8765 path=/ws
[sleepcare-pi] TLS cert=./sleepcare_pi_cert.pem key=./sleepcare_pi_key.pem
```

성공 기준:

- Pi가 `0.0.0.0:8765` 또는 Pi IP의 `8765`에서 listen 중이다.
- 서버가 `/ws` WebSocket upgrade 요청을 받을 준비가 되어 있다.
- TLS 인증서와 개인키가 서로 맞는 쌍이다.

실패하면 볼 것:

- `ss -ltnp`에 포트가 없으면 서버가 뜨지 않은 것이다.
- 포트는 열렸는데 앱 연결이 실패하면 방화벽, 같은 Wi-Fi 여부, WSS path를 확인한다.
- 인증서와 개인키가 맞는지 확인한다.

```bash
openssl x509 -noout -modulus -in sleepcare_pi_cert.pem | openssl md5
openssl rsa  -noout -modulus -in sleepcare_pi_key.pem  | openssl md5
```

두 출력이 같아야 한다.

## 2. 앱에 직접 endpoint 입력

앱에서 입력할 값:

```text
host: 192.168.0.45
port: 8765
wsPath: /ws
deviceId: deskpi-a1
displayName: SleepCare Pi Desk
```

앱에서 누를 버튼:

- `연결 모드: 직접 endpoint`

Pi에서 확인할 로그:

- 아직 연결 요청이 오지 않는 것이 정상이다.
- 입력만으로는 앱이 Pi에 접속하지 않는다.

성공 기준:

- 카드의 입력값이 유지된다.
- 아직 trusted Pi로 저장되지 않는다.

실패하면 볼 것:

- `host`는 `https://`나 `wss://`를 붙이지 않는다.
- `wsPath`는 `/ws`처럼 `/`로 시작하는 값을 권장한다. 앱은 `/`가 없으면 자동으로 붙인다.
- `port`는 숫자만 입력한다.

## 3. SPKI 읽기

앱에서 누를 버튼:

- `SPKI 읽기`

앱이 하는 일:

- `wss://host:port/wsPath`로 한 번 접속한다.
- 이 단계에서는 인증서를 신뢰 저장하지 않고, 서버가 보여준 인증서의 public key 지문만 읽는다.
- 읽은 SPKI SHA-256을 카드에 표시한다.

Pi에서 확인할 로그:

```text
[sleepcare-pi] websocket client connected path=/ws
[sleepcare-pi] websocket closed reason=spki_probe_done
```

성공 시 앱 표시 예시:

```text
SPKI: abCDef1234...xYz987
마지막 명령: SPKI 읽기 성공: abCDef1234...xYz987
```

성공 기준:

- 앱 카드에 `SPKI`가 `아직 없음`이 아닌 값으로 표시된다.
- Pi 서버에는 짧은 연결이 한 번 들어왔다가 닫힌다.

실패하면 볼 것:

- `SPKI 읽기 시간이 초과되었습니다`: IP, 포트, 같은 Wi-Fi, 방화벽을 확인한다.
- `WSS handshake에서 서버 인증서를 읽지 못했습니다`: 서버가 `ws://`로 떠 있거나 TLS 설정이 빠졌을 수 있다.
- WebSocket upgrade가 실패하면 `wsPath`가 서버 경로와 같은지 확인한다.

## 4. pairing JSON 생성/등록

앱에서 누를 버튼:

1. `pairing JSON 생성`
2. `JSON으로 등록`

앱이 만드는 JSON 예시:

```json
{
  "proto": "sleepcare-pair-v1",
  "device_id": "deskpi-a1",
  "display_name": "SleepCare Pi Desk",
  "service": "_sleepcare._tcp",
  "ws": "/ws",
  "tls": 1,
  "spki_sha256": "BASE64_SHA256_OF_SERVER_PUBLIC_KEY",
  "issued_at_ms": 1777993200000,
  "pin_hint": "abCDef1234...xYz987"
}
```

필드 의미:

- `proto`: QR/pairing payload 버전이다. 현재는 `sleepcare-pair-v1`만 지원한다.
- `device_id`: Pi를 구분하는 안정적인 ID다. Avahi TXT record의 `device_id`와 같아야 한다.
- `display_name`: 앱 UI에 보여줄 이름이다.
- `service`: Avahi/NSD service type이다. 현재는 `_sleepcare._tcp`만 지원한다.
- `ws`: WSS WebSocket path다.
- `tls`: 반드시 `1`이어야 한다. 앱은 TLS 없는 연결을 등록하지 않는다.
- `spki_sha256`: 방금 읽은 서버 인증서 public key 지문이다.
- `pin_hint`: 사람이 확인하기 쉬운 짧은 표시용 지문이다.

Pi에서 확인할 로그:

- `pairing JSON 생성`만으로는 Pi에 요청이 가지 않는다.
- `JSON으로 등록`도 Pi에 요청이 가지 않는다. 앱 DataStore에 trusted Pi 정보만 저장한다.

성공 기준:

- `JSON으로 등록 성공`이 표시된다.
- 기기 연결 화면의 Raspberry Pi 등록 카드에 `displayName`, `deviceId`, 짧은 SPKI가 보인다.

실패하면 볼 것:

- `먼저 SPKI 읽기를 실행해 주세요`: 3단계를 먼저 끝낸다.
- `spki_sha256 must decode to 32 bytes`: 인증서 지문이 Base64 SHA-256 형식이 아니다.
- `Pairing requires tls=1`: Pi 등록은 WSS 전제이므로 `tls`는 1이어야 한다.

다시 강조한다. 이 등록은 QR과 같은 신뢰 등록 경로다. **디버그 전용 등록 프로토콜은 필요 없다.**

## 5. NSD 후보 검색

앱에서 누를 버튼:

- `NSD 후보 검색`

Pi에서 Avahi가 아직 없을 때:

- 후보가 0개여도 괜찮다.
- 이 경우 직접 endpoint 모드로 WSS, hello, session을 먼저 테스트하면 된다.

Pi에서 Avahi가 있을 때 기대 설정:

```text
service type: _sleepcare._tcp
TXT proto=v1
TXT tls=1
TXT device_id=deskpi-a1
TXT ws=/ws
```

앱 표시 예시:

```text
SleepCare Pi Desk · 192.168.0.45:8765
TXT {proto=v1, tls=1, device_id=deskpi-a1, ws=/ws}
```

성공 기준:

- 후보 목록에 Pi IP, 포트, TXT record가 보인다.
- `device_id`와 `ws`가 등록 JSON과 같다.
- `proto=v1`, `tls=1`이다.

실패하면 볼 것:

- 후보가 0개: 같은 Wi-Fi인지, Avahi가 실행 중인지 확인한다.
- 후보는 보이는데 등록 Pi(NSD) 연결이 실패: `device_id`, `ws`, `tls`, `proto` 중 하나가 등록값과 다를 가능성이 높다.
- Android NSD는 네트워크 상태에 민감하다. Pi와 폰 Wi-Fi를 껐다 켜고 다시 검색해 본다.

Pi에서 확인할 명령:

```bash
avahi-browse -rt _sleepcare._tcp
```

기대 출력에는 TXT record가 같이 보여야 한다.

## 6. hello 테스트

앱에서 누를 버튼:

- 직접 endpoint로 볼 때: `연결 모드: 직접 endpoint` 선택 후 `hello 테스트`
- 운영 등록 조건을 볼 때: `연결 모드: 등록 Pi(NSD)` 선택 후 `hello 테스트`

앱이 보내는 JSON 예시:

```json
{
  "v": 1,
  "t": "hello",
  "sid": null,
  "seq": 1,
  "src": "phone",
  "sent_at_ms": 1777993200000,
  "ack_required": true,
  "body": {
    "role": "android-app",
    "watch_available": false,
    "supports_eye_only": true
  }
}
```

Pi가 보내야 하는 JSON 예시:

```json
{
  "v": 1,
  "t": "hello_ack",
  "sid": null,
  "seq": 1,
  "src": "pi",
  "sent_at_ms": 1777993200100,
  "ack_required": false,
  "body": {
    "device_id": "deskpi-a1",
    "mode": "debug",
    "proto": "v1"
  }
}
```

Pi에서 확인할 로그:

```text
[sleepcare-pi] rx t=hello src=phone seq=1
[sleepcare-pi] tx t=hello_ack device_id=deskpi-a1 proto=v1
```

폰에서 확인할 logcat:

```powershell
adb logcat -s SleepCarePi
```

기대 출력:

```text
SleepCarePi: recv raw={"v":1,"t":"hello_ack",...}
SleepCarePi: recv type=hello_ack sid=null seq=1 ackRequired=false
```

`recv invalid raw=...`가 보이면 앱까지 패킷은 도착했지만 JSON envelope 형식이 맞지 않는 상태다.

성공 기준:

- 앱 카드에 `hello_ack 수신`이 표시된다.
- Pi 로그에 `hello` 수신과 `hello_ack` 송신이 모두 보인다.

실패하면 볼 것:

- 앱은 성공이라는데 Pi 로그가 없다: 앱이 다른 endpoint에 붙었거나, Pi 로그가 WebSocket message 단계가 아니라 HTTP 단계만 찍고 있을 수 있다.
- Pi 로그에는 `hello`가 있는데 앱이 timeout: `hello_ack`의 `t`가 정확히 `hello_ack`인지, JSON 최상위 구조가 맞는지 본다.
- `sid`는 null이어도 된다. hello는 세션 전 handshake다.

## 7. Eye-only 세션 테스트

앱에서 누를 버튼:

- `Eye-only 시작`

앱이 보내는 JSON 예시:

```json
{
  "v": 1,
  "t": "session.open",
  "sid": "pi-debug-2026-05-06-a1b2c3d4",
  "seq": 2,
  "src": "phone",
  "sent_at_ms": 1777993210000,
  "ack_required": true,
  "body": {
    "study_mode": "focus",
    "watch_available": false,
    "eye_only": true
  }
}
```

Pi가 먼저 보내야 하는 응답:

```json
{
  "v": 1,
  "t": "session.ack",
  "sid": "pi-debug-2026-05-06-a1b2c3d4",
  "seq": 2,
  "src": "pi",
  "sent_at_ms": 1777993210100,
  "ack_required": false,
  "body": {
    "accepted": true
  }
}
```

Pi가 이후 보낼 수 있는 risk.update 예시:

```json
{
  "v": 1,
  "t": "risk.update",
  "sid": "pi-debug-2026-05-06-a1b2c3d4",
  "seq": 3,
  "src": "pi",
  "sent_at_ms": 1777993215000,
  "ack_required": false,
  "body": {
    "mode": "eye-only",
    "eye_score": 0.18,
    "hr_score": null,
    "fused_score": 0.18,
    "state": "BASELINE",
    "recommended_flush_sec": null
  }
}
```

Pi에서 확인할 로그:

```text
[sleepcare-pi] rx t=session.open sid=pi-debug-2026-05-06-a1b2c3d4 eye_only=true watch_available=false
[sleepcare-pi] tx t=session.ack sid=pi-debug-2026-05-06-a1b2c3d4
[sleepcare-pi] tx t=risk.update sid=pi-debug-2026-05-06-a1b2c3d4 state=BASELINE
```

성공 기준:

- 앱 카드의 테스트 세션 ID가 `pi-debug-...`로 표시된다.
- `session.open 성공`이 표시된다.
- `risk.update`를 보내면 앱 카드의 risk 줄이 갱신된다.

실패하면 볼 것:

- `session.ack 대기 시간이 초과`: Pi가 `session.open`은 받았지만 `session.ack`를 안 보냈거나 `sid`가 다르다.
- `risk.update`가 앱에 안 뜸: `sid`가 현재 앱 카드의 테스트 세션 ID와 같은지 확인한다.
- `watch_available=false`, `eye_only=true`일 때 Pi는 심박 데이터가 없어도 실패하면 안 된다.

## 8. Eye+Synthetic HR 테스트

앱에서 누를 버튼:

1. `Eye+Synthetic HR 시작`
2. `Synthetic HR 전송`

`Eye+Synthetic HR 시작`에서 앱이 보내는 session.open 예시:

```json
{
  "v": 1,
  "t": "session.open",
  "sid": "pi-debug-2026-05-06-b2c3d4e5",
  "seq": 4,
  "src": "phone",
  "sent_at_ms": 1777993220000,
  "ack_required": true,
  "body": {
    "study_mode": "focus",
    "watch_available": true,
    "eye_only": false
  }
}
```

`Synthetic HR 전송`에서 앱이 보내는 hr.ingest 예시:

```json
{
  "v": 1,
  "t": "hr.ingest",
  "sid": "pi-debug-2026-05-06-b2c3d4e5",
  "seq": 5,
  "src": "phone",
  "sent_at_ms": 1777993225000,
  "ack_required": false,
  "body": {
    "sample_seq": 1,
    "watch_sensor_ts_ms": 1777993224900,
    "phone_rx_ms": 1777993225000,
    "bpm": 72,
    "hr_quality": "ok",
    "hr_status": 1,
    "ibi_ms": [820]
  }
}
```

앱은 한 번 누를 때 샘플 3건을 보낸다. `sample_seq`는 `1`, `2`, `3`처럼 증가한다.

Pi에서 확인할 로그:

```text
[sleepcare-pi] rx t=session.open sid=pi-debug-2026-05-06-b2c3d4e5 eye_only=false watch_available=true
[sleepcare-pi] tx t=session.ack sid=pi-debug-2026-05-06-b2c3d4e5
[sleepcare-pi] rx t=hr.ingest sid=pi-debug-2026-05-06-b2c3d4e5 sample_seq=1 bpm=72
[sleepcare-pi] rx t=hr.ingest sid=pi-debug-2026-05-06-b2c3d4e5 sample_seq=2 bpm=74
[sleepcare-pi] rx t=hr.ingest sid=pi-debug-2026-05-06-b2c3d4e5 sample_seq=3 bpm=73
```

성공 기준:

- Pi가 HR 샘플을 받아도 세션을 끊지 않는다.
- HR이 아직 모델에 반영되지 않았더라도 실패 처리하지 않는다.
- Pi가 `risk.update`를 보낼 때 `sid`를 같은 값으로 유지한다.

실패하면 볼 것:

- `hr.ingest`가 오기 전에 세션이 열렸는지 확인한다.
- Pi가 `watch_available=true`를 “실제 워치 필수”로 해석해 세션을 거부하면 안 된다. 이 테스트는 synthetic HR이다.
- `sample_seq` 중복 처리는 Pi 구현에서 idempotent하게 다루는 것이 좋다.

## 9. 종료 테스트

앱에서 누를 버튼:

- `종료`

앱이 보내는 JSON 예시:

```json
{
  "v": 1,
  "t": "session.close",
  "sid": "pi-debug-2026-05-06-b2c3d4e5",
  "seq": 8,
  "src": "phone",
  "sent_at_ms": 1777993230000,
  "ack_required": true,
  "body": {
    "reason": "user_stop"
  }
}
```

Pi가 보내야 하는 summary 예시:

```json
{
  "v": 1,
  "t": "session.summary",
  "sid": "pi-debug-2026-05-06-b2c3d4e5",
  "seq": 9,
  "src": "pi",
  "sent_at_ms": 1777993230200,
  "ack_required": false,
  "body": {
    "final_state": "IDLE",
    "total_alerts": 0,
    "peak_fused_score": 0.22,
    "mode": "eye+hr",
    "summary_reason": "user_stop"
  }
}
```

Pi에서 확인할 로그:

```text
[sleepcare-pi] rx t=session.close sid=pi-debug-2026-05-06-b2c3d4e5 reason=user_stop
[sleepcare-pi] tx t=session.summary sid=pi-debug-2026-05-06-b2c3d4e5 summary_reason=user_stop
```

성공 기준:

- 앱 카드에 `session.close 전송`이 표시된다.
- `session.summary`를 받으면 summary 줄이 갱신된다.
- `sid`는 시작 때 받은 세션 ID와 반드시 같아야 한다.

실패하면 볼 것:

- 앱에 `summary 대기 시간 초과`가 떠도 `session.close` 전송 자체는 되었을 수 있다. Pi 로그에서 close 수신 여부를 먼저 본다.
- `session.summary`의 `sid`가 다르면 앱은 무시한다.
- Pi가 close 후 WebSocket을 바로 닫더라도 summary를 먼저 보낸 뒤 닫는 것이 좋다.

## 10. 자주 막히는 상황

### JSON 오류

증상:

- 앱이 `pairing JSON 생성` 또는 `JSON으로 등록`에서 실패한다.

볼 것:

- `proto`는 `sleepcare-pair-v1`이어야 한다.
- `service`는 `_sleepcare._tcp`이어야 한다.
- `tls`는 `1`이어야 한다.
- `spki_sha256`은 Base64로 인코딩된 32바이트 SHA-256이어야 한다.

### SPKI 불일치

증상:

- `hello 테스트`에서 TLS/SPKI 관련 오류가 난다.

볼 것:

- `SPKI 읽기`를 다시 누른다.
- 새 인증서로 서버를 바꿨다면 `pairing JSON 생성`과 `JSON으로 등록`을 다시 한다.
- 인증서 파일은 바꿨는데 서버가 예전 인증서를 들고 실행 중인 경우가 많다.

### NSD 미발견

증상:

- `NSD 후보 검색` 결과가 0개다.

볼 것:

- 같은 Wi-Fi인지 확인한다.
- Pi에서 `avahi-daemon`이 실행 중인지 확인한다.
- 일단 직접 endpoint 모드로 WSS/hello/session이 되는지 먼저 확인한다. Avahi는 나중에 붙여도 된다.

### hello timeout

증상:

- 앱이 `hello_ack 대기 시간이 초과`라고 표시한다.

볼 것:

- Pi 로그에 `hello` 수신이 있는지 확인한다.
- 수신이 없다면 endpoint, WSS path, SPKI/TLS를 본다.
- 수신은 있는데 timeout이면 Pi가 `t=hello_ack`를 정확히 보내는지 확인한다.

### session ack timeout

증상:

- 앱이 `session.ack 대기 시간이 초과`라고 표시한다.

볼 것:

- Pi 로그에 `session.open` 수신이 있는지 확인한다.
- `session.ack`의 `sid`가 앱이 보낸 `sid`와 같은지 확인한다.
- `t` 값은 `session.ack`이어야 한다.

### risk sid 불일치

증상:

- Pi가 `risk.update`를 보내는데 앱 카드에 표시되지 않는다.

볼 것:

- 앱 카드의 `테스트 세션` 값을 복사해 Pi 로그의 `sid`와 비교한다.
- 앱은 다른 세션 ID의 `risk.update`, `alert.fire`, `session.summary`를 무시한다.

## 11. 추천 개발 순서

1. WSS 서버를 띄운다.
2. 앱에서 직접 endpoint를 입력한다.
3. `SPKI 읽기`가 성공하는지 본다.
4. `pairing JSON 생성`, `JSON으로 등록`이 되는지 본다.
5. `hello 테스트`가 성공하는지 본다.
6. `Eye-only 시작`과 `종료`를 먼저 성공시킨다.
7. `Eye+Synthetic HR 시작`, `Synthetic HR 전송`을 붙인다.
8. Avahi를 구현한 뒤 `NSD 후보 검색`과 `등록 Pi(NSD)` 모드로 운영 조건을 확인한다.

이 순서대로 하면 QR과 Avahi가 아직 없어도 WSS와 세션 프로토콜 개발을 먼저 진행할 수 있다. 마지막으로 다시 강조한다. **Pi 서버에 디버그 전용 message type을 추가하지 않는다. 운영 프로토콜을 그대로 받으면 된다.**

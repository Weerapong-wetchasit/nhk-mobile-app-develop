# DeviceAuth (device-bound PIN)

How the 6-digit PIN works end to end. The PIN is **not** a password sent to the
server — it is a *local gate* that unlocks a device-bound EC private key, which
then signs a server challenge. This replaced the old plaintext-local-PIN scheme.

> Guarding a sensitive action (revoke, change-PIN, …) with a fresh PIN?
> See **[pin-step-up.md](pin-step-up.md)** — this doc is the system overview.

## Why this design

- The PIN never leaves the device and is never stored in plaintext.
- Each device proves itself with a private key held in the platform keystore
  (Android Keystore / iOS Keychain); the server only ever sees the public key and
  signatures.
- A wrong PIN is caught locally and never triggers a signature — it is reported
  as a failed attempt so the server can drive lockout.

## Terminology

| Term | Meaning |
|---|---|
| **enrol** | Register this device's public key with the server → get a `deviceId` |
| **unlock** | Prove the user knows the PIN *and* holds the key: `challenge → sign → verify` → the server marks an active "unlock" for the session |
| **verifier** | A salted SHA-256 hash of the PIN stored locally to check the PIN offline (`base64(salt):base64(sha256(salt+pin))`) |

## Per-account keying

Every stored item is namespaced by the Firebase `uid`, so switching accounts /
logging out never leaks another account's state (see
[biometric-login.md](biometric-login.md)):

| Item | Key |
|---|---|
| Stored deviceId | `nhk_device_id_<uid>` (secure storage) |
| Local PIN verifier | `nhk_pin_verifier_<uid>` (secure storage) |
| Keystore key alias | `nhk_devkey_<uid>` |
| Biometric opt-in flag | `nhk_biometric_enabled_<uid>` |

"This device is set up" means **all three** of key + deviceId + verifier are
present for the current uid (`DeviceAuthRepository.isDeviceSetUp`). Any partial
state ⇒ re-enrol.

## Crypto contract

- Key pair: **EC P-256**, signatures over **SHA-256**.
- Public key sent at enrol: **SPKI DER, base64**.
- Signature sent at verify: **ASN.1 / DER `SEQUENCE { r, s }`, base64** (not raw
  `r‖s`).
- Private key never leaves the keystore; signing happens natively via
  `DeviceKeyService` (`nhk/device_key` platform channel).

## Lifecycle

### 1. Enrol

```
generate EC key (keystore)  →  POST /device-auth/devices/enroll { publicKey, platform, deviceLabel }
                            →  201, body = deviceId (GUID)
store deviceId + PIN verifier locally
```

### 2. Unlock (`challenge → sign → verify`)

```
local PIN gate (verifier)   →  POST /device-auth/challenges { deviceId, purpose:"Unlock" }  → { challengeId, nonce }
sign nonce with device key  →  POST /device-auth/challenges/{challengeId}/verify { signature }
                            →  { succeeded, unlockExpiresAtUtc, unlockAbsoluteExpiryUtc, ... }
```

A wrong PIN fails the **local** gate → reported via `POST
/device-auth/devices/{id}/failed-attempt` (advances server lockout) and returned
as a non-succeeded `VerifyResult` with `remainingAttempts` — never an exception.
The server **locks** the device after **5** consecutive wrong attempts and
**suspends** it after **10**. Reporting is mandatory — see
[pin-step-up.md → Wrong PIN & lockout](pin-step-up.md#wrong-pin--lockout).

### 3. Change PIN

Requires a valid unlock, then `POST /device-auth/devices/{id}/pin-changed` (bumps
the PIN version, invalidating other unlocks). The local verifier is rotated only
after the server accepts.

### 4. Background lock

When the app is backgrounded longer than the client threshold it locks and calls
`POST /device-auth/devices/{id}/lock`, forcing a re-unlock (see
[Session timers](#session-timers)).

## Session timers

An unlock stays valid under three timers:

| Timer | Value | Enforced by | Meaning |
|---|---|---|---|
| `BackgroundTimeout` | **5 min** | **client** | App backgrounded longer → lock + re-unlock (`MainWrapper` lifecycle listener) |
| `SlidingWindow` | 30 min | server | Extends on each use; idle past this → expires (`PIN_REQUIRED`) |
| `AbsoluteLifetime` | 4 h | server | Hard ceiling from the first PIN entry |

## Device management

A member can list and remove enrolled devices (Profile → จัดการอุปกรณ์):

- `GET /device-auth/devices` — list (`UserDeviceDto`: deviceId, deviceLabel,
  platform, status, enrolledAtUtc, lastSuccessAtUtc, isCurrentlyLocked,
  lockedUntilUtc).
- `POST /device-auth/devices/{id}/revoke` — revoke one other device.
- `POST /auth/me/revoke-all-devices` — revoke **all** incl. this device (the
  "lost phone" button) → wipes local state + signs out.

Both revokes require a **fresh** PIN unlock — see **[pin-step-up.md](pin-step-up.md)**.

## Error taxonomy

Device-auth endpoints return `{ "code", ... }` bodies, mapped to a typed
`DeviceAuthErrorCode` via `fromWire(code, status)`
(`lib/domain/models/device_auth/device_auth_error_code.dart`). Full code/status
table in [pin-step-up.md](pin-step-up.md#status-codes).

**Stale device → re-enrol.** A device that was **revoked/suspended** server-side
and then hits challenge/verify comes back as `403 DEVICE_MUST_BE_ACTIVE`
(→ `deviceNotActive`), *not* `404` — a genuine `404` means the device isn't found
at all. Both signal that the local state is stale: `clearLocal` and re-enrol. The
login PIN flow (`pin_code_viewmodel`) does this automatically; the step-up sheet
and change-PIN currently surface a generic error and rely on the routing gate to
re-check on the next navigation.

## Flutter classes

| Class / file | Role |
|---|---|
| `DeviceAuthApiClient` · `lib/data/services/api/api_client/device_auth_api_client.dart` | Raw endpoints (enroll, challenge, verify, failed-attempt, pin-changed, lock, list, revoke) |
| `DeviceAuthRepository` / `DeviceAuthRepositoryRemote` · `lib/data/repositories/device_auth/` | Orchestration: `isDeviceSetUp`, `enrollThisDevice`, `unlock`, `unlockWithBiometric`, `changePin`, `lockDevice`, `listDevices`, `revokeDevice`, `revokeAllDevices`, `clearLocal` |
| `DeviceKeyService` · `lib/data/services/local/device_key_service.dart` | Native keystore: generate key, sign, delete (`nhk/device_key` channel) |
| `DeviceAuthErrorCode` · `DeviceAuthException` | Typed failure + raw code/message |
| `VerifyResult`, `ChallengePurpose`, `FailedAttemptResult`, `UserDevice`/`DeviceStatus` · `lib/domain/models/device_auth/` | Domain models |
| `PinConfirmViewmodel` + `showPinConfirmSheet()` · `lib/ui/device_management/` | Reusable fresh-unlock PIN sheet (step-up) |

## iOS

The native key channel (`nhk/device_key`) is implemented for **Android only**. On
iOS the Dart side and UI build, but enrol/unlock/sign will not work until the iOS
`DeviceKeyPlugin` (Keychain + Secure Enclave, mirroring the Android channel) is
added. Tracked as a separate follow-up issue.

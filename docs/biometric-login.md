# Biometric login (Face ID / fingerprint)

Read this before touching PIN auth or adding another auth shortcut. It documents
how biometric unlock was layered onto the existing PIN flow, using
`local_auth` — real code, real file paths, from this project.

## The problem this pattern solves

The app's only login mechanic was a 6-digit PIN checked against
`flutter_secure_storage`. Biometrics needed to slot in as a **shortcut to the
same "verified" state**, not a replacement — the PIN keypad must always work,
even on a device with no biometric hardware, no enrolled fingerprint, or a
user who never opts in. That constraint drove every decision below: every
biometric code path fails silently back to the keypad, never to an error
screen.

## 1. Service layer wraps `local_auth`, nothing else touches it

`lib/data/services/local/biometric_auth_service.dart` is the only file that
imports `package:local_auth`. It mirrors the shape of the existing
`SecureStorageService` (`lib/data/services/local/secure_storage_service.dart`):
an abstract class + one impl, no extra ceremony.

`local_auth: ^3.0.2`'s `authenticate()` throws `LocalAuthException` (not
`PlatformException` — this is a common trap if you've used an older
`local_auth` before) with a `LocalAuthExceptionCode`. The service maps every
code to one of three outcomes in `BiometricAuthResult`
(`lib/domain/models/biometric/biometric_auth_result.dart`): `success`,
`cancelled`, `notAvailable`, `lockedOut`, `error`. Callers only ever branch on
that enum — nobody outside this file needs to know `local_auth`'s exception
shape.

## 2. Repository owns the per-account "enabled" flag — on by default

`lib/data/repositories/biometric/biometric_repository_impl.dart` combines the
service (device-level capability + the actual OS prompt) with a
secure-storage-backed **tri-state** preference: explicitly `'true'`,
explicitly `'false'`, or — the common case — no value stored at all.
Biometric login is **opt-out, not opt-in**: `isEnabledForCurrentAccount()`
treats "no explicit preference yet" as enabled, as long as the device
actually supports biometrics:

```dart
Future<bool> isEnabledForCurrentAccount() async {
  final stored = await _secureStorageService.read(key);
  if (stored == _valueDisabled) return false;
  if (stored == _valueEnabled) return true;
  // No explicit preference recorded — on by default when supported.
  final availability = await checkAvailability();
  return availability == BiometricAvailability.available;
}
```

This means a member never has to turn anything on: the first time they're
asked to unlock the app after PIN setup, biometrics already work. Turning it
off from Profile → Privacy writes an explicit `'false'`, which sticks until
they turn it back on. `clearForAccount()` (called on logout — see below)
deletes the key outright, reverting to "unset" so the *next* signed-in member
on that device gets their own on-by-default behavior rather than inheriting
the previous member's explicit choice.

That flag is keyed per-account, exactly like the device credential itself:

```dart
'nhk_biometric_enabled_${uid}'   // biometric repository
'nhk_device_id_${uid}'           // DeviceAuth repository
'nhk_pin_verifier_${uid}'        // DeviceAuth repository (salted PIN verifier)
```

The biometric flag uses the shared helper `lib/utils/account_storage_key.dart` —
`accountStorageKey(user, baseKey)` — instead of hand-rolling string
concatenation, so a signed-out state (`user == null`) is handled once,
correctly, everywhere. The DeviceAuth keys follow the same `<base><uid>` shape.
(The PIN is no longer stored in plaintext under `nhk_user_pin_` — a 6-digit PIN
now only gates a device-bound key; see `docs/pin-migration/`.) Per-account
keying is what makes account switching and
logout safe by construction: `logOut()` (`lib/ui/auth/logout/logout_viewmodel.dart`)
captures the uid before signing out and calls
`biometricRepository.clearForAccount(uid)` so a different member on the same
device never inherits a stale enabled flag.

## 3. The login screen auto-triggers — one atomic check-then-scan flow

`PinCodeViewmodel.attemptBiometricLogin()`
(`lib/ui/pin_code/view_models/pin_code_viewmodel.dart`) does the availability
check *and* the OS prompt in the same async call, and is called directly from
`build()` when `mode == PinFlowMode.login` (fire-and-forget — it runs after
the synchronous `build()` returns):

```dart
PinCodeState build(PinFlowMode mode) {
  ref.onDispose(() => _cooldownTimer?.cancel());
  if (mode == PinFlowMode.login) {
    attemptBiometricLogin();
  }
  return PinCodeState(mode: mode);
}
```

**Why this has to be one flow, not two:** the first version of this feature
split it into a "check availability" step kicked off from `build()` and a
separate "trigger the scan" step kicked off from the screen widget's
`initState` (gated on the availability flag `build()`'s check would
eventually set). Those two async flows raced — the screen's
`initState`/`addPostFrameCallback` fires after just one frame, but the
availability check needs two platform-channel round trips
(`checkAvailability()` + `isEnabledForCurrentAccount()`) that routinely take
longer than that. So the trigger step ran first, saw the flag still `false`,
and silently no-opped — biometric login *looked* broken because only the
manual retry button (which re-ran the check itself) actually worked. Doing
the check and the scan as a single awaited sequence removes the race
entirely: by the time the code decides whether to call
`repo.authenticate()`, the availability check has already finished.

On success it reuses the **exact** state transition `_verifyLoginPin` already
uses on a correct PIN — `isSuccess: true` +
`pinControllerProvider.notifier.verifySuccess()` — so from the router's
perspective a biometric unlock and a correct PIN are indistinguishable. On
any non-success result it just clears the `isBiometricAttempting` flag and
leaves the keypad as-is. Critically, it **never** calls
`_registerWrongAttempt()` — biometric failures don't consume PIN lockout
attempts; the OS handles its own biometric lockout independently.

A small `BiometricRetryButton` on the PIN screen calls the same
`attemptBiometricLogin()` so users can re-trigger it if they dismissed the OS
prompt by accident — it's a manual re-entry into the exact same flow, not a
separate code path.

## 4. No opt-in dialog — on-by-default means there's nothing to ask

An earlier version of this feature showed a one-time "enable biometric
login?" dialog right after PIN setup, deferred to `MainWrapper` to dodge a
router-redirect race. That's gone: since §2 makes biometric login on by
default whenever the device supports it, there's nothing to opt into — it
already works the next time the member is asked to unlock the app. The only
UI surface left is the always-available toggle in Profile → Privacy
(`MenuToggle` in `lib/ui/profile/screen/profile_screen.dart`) for the member
who wants to turn it *off*, which re-checks `checkAvailability()` before
turning it back on in case biometrics were disabled at the OS level since the
app started.

## 5. Platform config both platforms need

- **Android**: `android:permission.USE_BIOMETRIC` in `AndroidManifest.xml`,
  and `MainActivity.kt` must extend `FlutterFragmentActivity`, not
  `FlutterActivity` — `local_auth_android` requires a `FragmentActivity` host
  to show the prompt. Both were changed as part of this feature; if you ever
  see a `local_auth` crash mentioning `FragmentActivity`, this is why.
- **iOS**: `NSFaceIDUsageDescription` in `Info.plist` — without it, Face ID
  fails silently on real devices.

## Edge cases handled by construction, not by special-casing

| Scenario | Why it's already safe |
|---|---|
| No hardware / nothing enrolled | `checkAvailability()` returns non-`available`; every UI entry point (auto-trigger, retry button, toggle) checks this and no-ops/hides. |
| Biometrics revoked in device settings after enabling in-app | `authenticate()` maps to `notAvailable`; falls back to keypad. The stored flag is left alone (not force-disabled) so re-enrolling resumes biometric login without re-toggling. |
| App backgrounded mid-prompt | Handled by the OS dismissing its own native sheet; the `authenticate()` future just resolves non-success on return, same silent-failure path. |
| Simulator/emulator with no biometric hardware | Same as "no hardware" — never shown, never errors. |

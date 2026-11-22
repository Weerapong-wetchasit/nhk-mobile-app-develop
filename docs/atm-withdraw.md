# ATM withdraw (ถอนเงิน ATM) via Krungsri Open API

Read this before touching the atm-withdraw screen, the Krungsri API client, or anything that opens an external browser login. Bank spec: *Krungsri "Spec APIs for COOP Partner App" v1.7*; the withdrawal is §5.1 Case 2, "Transfer from COOP's Account to tokenized account number".

## Flow

| # | Step | Where |
|---|---|---|
| 1 | Home quick action "เอทีเอ็ม" (or the services menu) → `Routes.atmWithdraw` | `home_quick_actions.dart`, `service_center_category.dart` |
| 2 | Show remaining credit (วงเงินคงเหลือ): `GET /api/v1/atm/credit-limit` → `{creditLimit, availableAmount}` (backend `Atm` module, **mock data** there, see below) | `atmCreditLimitProvider` → `AtmWithdrawRepository.getCreditLimit()` → `AtmApiClient` |
| 3 | Member enters an amount (> 0, ≤ remaining, ≤ 2 decimals) and taps ถอนเงิน | `WithdrawAmountForm`, `WithdrawAmountValidation` |
| 4 | PIN | `showPinConfirmSheet` (the same server-verified fresh-unlock sheet device management uses) |
| 5 | `POST /api/v1/krungsri/link-account/prepare` → `{authorizeUrl, state}`. Open `authorizeUrl` in a browser popup; the member logs in at Krungsri | `KrungsriApiClient.prepareLink`, `KrungsriWebAuthService` (`flutter_web_auth_2`) |
| 6 | Krungsri redirects **straight to the app**: `coopapp://auth/callback?code=<bank code>&state=<state>`, or `?error=...`. `state` must equal the one from prepare. Then `POST /link-account/complete {code, state}` → bank `accessToken` | `parseKrungsriCallback`, `KrungsriApiClient.completeLink` |
| 7 | `POST /transfers/initiate {accessToken, amount}` → `transactionInitiationNumber`. The backend sends Krungsri `COOP_WITHDRAW_M` / `DBCR`, with `accountFrom` = the co-op account (`KrungsriApi:CoopBankCode/CoopAccountNumber`) and `accountTo` = `ACCT` proxy of the member's tokenized account | `initiateTransfer` |
| 8 | Review screen: amount, Krungsri account (masked), initiation number | `WithdrawReviewCard` |
| 9 | ยืนยัน → `POST /transfers/confirm {accessToken, transactionInitiationNumber}` | `AtmWithdrawViewmodel.confirm` |
| 10 | Result screen. Success (`WithdrawSuccess`: message + `transactionReferenceNumber`) or failure (`WithdrawFailed`: reason + initiation number, plus "ลองยืนยันอีกครั้ง" when `KrungsriErrorCode.isRetryableConfirm`) | `AtmWithdrawStep.success` / `.failed` |

Steps 5–7 are one call, `AtmWithdrawViewmodel.authorizeAndInitiate`. The bank token is kept in the ViewModel state (memory only, never persisted). So if initiate fails, "try again" goes straight to initiate and the member doesn't have to log in at the bank again, as long as the token hasn't expired.

The app checks the callback `state` against the one from prepare (a client-side CSRF check) before sending anything to the backend. `/complete` checks it again: `state` is single-use, tied to the user who called prepare, expires after 15 minutes, and otherwise fails with 400 `INVALID_LINK_STATE`.

## Why `flutter_web_auth_2` and not a GoRouter deep link

The callback is a custom-scheme redirect. If `MainActivity` declared the scheme, Flutter would hand `coopapp://auth/callback` to GoRouter as a deep link. There's no route for it, and it would run the auth redirect gate. Instead, `flutter_web_auth_2` registers its own `CallbackActivity` (Android, in `AndroidManifest.xml`, `scheme="coopapp" host="auth"`) and uses `ASWebAuthenticationSession` (iOS, which needs no `Info.plist` entry). Either way the redirect comes back to us as the `Future` result.

`coopapp://auth/callback` is the OAuth `redirect_uri`, set in four places, and all four must match:
- the redirect URI registered with Krungsri for this client
- backend `KrungsriApi:RedirectUrl`, which the backend sends both in the authorize URL and in the code → token exchange, where it must be identical
- `krungsriCallbackUrl` in `lib/data/services/external/krungsri_callback.dart`
- the `CallbackActivity` intent-filter in `android/app/src/main/AndroidManifest.xml`

The scheme is shared by every flavor. With dev and prod installed side by side, Android may show a chooser. If the member picks the wrong app, the redirect goes to that app's process. Our `authenticate()` never gets a result, and when the app resumes the plugin ends it as `CANCELED`, so nothing is shown. The fix is a scheme per flavor: a `manifestPlaceholders` value in `build.gradle.kts` plus a matching Dart constant per `AppEnv`. That also needs a `KrungsriApi:RedirectUrl` per environment and each redirect URI registered with Krungsri, so it waits on ops. RFC 8252 §7.1 recommends a reverse-DNS scheme (e.g. `com.psc.nhkcoop`) over a generic one like `coopapp`.

**Ephemeral login is iOS-only.** `preferEphemeral: true` makes `ASWebAuthenticationSession` private on iOS. On Android the service passes `intentFlags: ephemeralIntentFlags`, which only adds `FLAG_ACTIVITY_NO_HISTORY`. Custom Tabs share the browser's cookies, so a Krungsri session can carry over to the next withdrawal. If the bank login must happen every time, the backend has to request it in `authorizeUrl` (e.g. `prompt=login`, if Krungsri supports it).

**`taskAffinity`.** `MainActivity` keeps the Flutter template's `android:taskAffinity=""`. flutter_web_auth_2's README (issue #113) says the only guaranteed setup is `singleTop` with no `taskAffinity`. If, after the bank login, the Custom Tab doesn't close or the app doesn't come back to the front, remove `android:taskAffinity=""` first.

## Background lock bypass

`MainWrapper` re-locks to the PIN screen when the app comes back after ≥ 5 min in the background. A bank login can take that long, and re-locking would destroy the withdraw screen mid-flow. `ExternalAuthGuard` (`lib/ui/core/session/external_auth_guard.dart`, keepAlive) is set around `authorize()`. It also stays set for a 10 s grace window afterwards, because the plugin result can arrive before the resume event. While it's set, `_onResume` skips the lock. Any future feature that sends the member to an external browser login should use the same guard.

## PIN freshness

The Krungsri endpoints are not `[RequiresPin]` today. `mapKrungsriError` still turns `PIN_REQUIRED` / `FRESH_PIN_REQUIRED` / `PIN_VERSION_MISMATCH` into `DeviceAuthException`. `runAtmWithdrawAction` then re-prompts the PIN once and retries, the same way `_withFreshPin` does in device management. `confirm()` returns these as errors instead of moving to the failed screen. That covers the case where the backend gates transfers later: a bank login longer than 2 min would make the first PIN stale by initiate/confirm.

## Errors

`KrungsriErrorCode.fromWire` reads both backend error shapes: the flat `{code, message}` and ProblemDetails `{errorCode, detail}`. If the code is missing it falls back on HTTP status. If the member closes the popup, that's `canceled` and nothing is shown. Errors up to and including initiate appear as a snackbar on the amount screen (`atmWithdrawErrorMessage`). Confirm errors go to the failed result screen.

The bank's own error codes (e.g. `E024` insufficient fund) aren't visible to the app. The backend's 502 only includes `bankResponse` in Development, so bank-side failures show the generic `atmWithdrawErrorBank` message.

## Open items

1. **Remaining credit is mock data on the backend.** The app already calls the real endpoint `GET /api/v1/atm/credit-limit`. The backend module `src/Modules/Atm` answers from `Infrastructure/Queries/GetAtmCreditLimitQueryService.cs`, which returns a fixed 100,000 limit / 50,000 available for every member. To go live, change only that query service (plus a read-only DbContext in `AtmStartup`). The app needs no change.
2. **Ops config.** Krungsri must have `coopapp://auth/callback` registered, and every environment needs `KrungsriApi:CoopAccountNumber` (env `Nhkcoop_KrungsriApi__CoopAccountNumber`).
3. **No cancel endpoint.** "แก้ไขจำนวนเงิน" on the review screen leaves the initiated transfer as `INIT_SUCCESS` server-side, unconfirmed. No money moves.
4. **Confirm timeouts.** The spec's retry (§5.3) and inquiry (§5.4) APIs aren't wired.
5. Backend `state`/token caches are in-memory, so this only works against a single API instance.

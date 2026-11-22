# Environments & flavors (dev / staging / prod)

Read this before touching flavor config, `ApiConfig`/`AppConfig`, the
`firebase_initializer`, or the `env/` files. It covers how the app targets three
environments, how to run each, and how to finish the per-env Firebase wiring once
the real projects/domains exist.

## The model

An environment is **a native flavor + a matching config file**:

| Env | Flavor / applicationId | Config file (git-ignored) | Firebase | Scheme | Emulator |
|---|---|---|---|---|---|
| dev | `com.psc.nhkcoop.dev` | `env/env.json` | emulator | http | yes (`useEmulator`) |
| staging | `com.psc.nhkcoop.staging` | `env/env.staging.json` | real project | https | no |
| prod | `com.psc.nhkcoop` | `env/env.prod.json` | real project | https | no |

- **Flavor** (Android `productFlavors` in `android/app/build.gradle.kts`) sets the
  `applicationId` suffix — so all three install side by side — and the app label
  via the `${appLabel}` manifest placeholder. Each flavor's
  `google-services.json` lives in `android/app/src/<flavor>/`.
- **Config file** is passed with `--dart-define-from-file` and carries `ENV`,
  `BASE_URL`, and (dev only) the emulator host/port. Only the `*.example.json`
  templates are committed; the real files are git-ignored.

`ENV` is the single source of truth at runtime: `main.dart` → `EnvParser` →
`AppConfig.env`.

## Running

```bash
flutter run --flavor dev     --dart-define-from-file=env/env.json
flutter run --flavor staging --dart-define-from-file=env/env.staging.json
flutter run --flavor prod    --dart-define-from-file=env/env.prod.json
```

Each flavor must be paired with its own env file. In VS Code, add launch
configurations that do the same (`.vscode/` is git-ignored, so they stay local).

## How `ENV` drives behavior

- **Scheme** — `ApiConfig.getUri` picks the scheme from `ENV`, **not**
  `kReleaseMode`: dev → `http`, staging/prod → `https`. So a debug build pointed
  at staging/prod still uses https, and a release build pointed at the dev
  emulator still uses http.
- **Emulator** — `AppConfig.useEmulator` is true only for dev; that is the only
  env where `firebase_initializer` connects the Firebase Auth emulator. See
  [firebase-emulator.md](firebase-emulator.md).
- **Firebase options** — `firebase_initializer._optionsFor(env)` selects the
  per-env `FirebaseOptions`.
- **Logging** — level is set per env (dev=ALL, staging=INFO, prod=WARNING) and
  records print only on dev.
- **Fail-fast** — `AppConfig.validate()` throws at startup if `BASE_URL` is empty,
  carries a scheme (it must be host[:port] only), or a non-dev env points at a
  local host.

## Per-env Firebase (current placeholder → real)

Each env must use its **own** Firebase project so the ID-token audience matches
that env's backend. Today everything is a placeholder:

- all three `android/app/src/<flavor>/google-services.json` point at
  `nhk-coop-staging` (only the `package_name` differs so the gms plugin resolves
  one per flavor);
- `lib/firebase_options_dev.dart` also points at `nhk-coop-staging`;
- `firebase_initializer` returns the dev options for staging/prod as a
  placeholder (real imports/branches are stubbed in comments).

Note: because dev uses the emulator, which project its options name is
irrelevant for dev — the emulator intercepts all auth.

To make an env real:

1. Create/identify its Firebase project and register the Android/iOS app with the
   matching id (`com.psc.nhkcoop[.dev|.staging]`); add the signing SHA-1/256 for
   phone auth.
2. Generate its config (run once per env; needs your Firebase login):
   ```bash
   flutterfire configure \
     --project=<projectId> \
     --out=lib/firebase_options_<env>.dart \
     --platforms=android,ios \
     --android-package-name=com.psc.nhkcoop[.dev|.staging] \
     --ios-bundle-id=com.psc.nhkcoop[.dev|.staging] \
     --android-out=android/app/src/<flavor>/google-services.json
   ```
   This overwrites that env's `firebase_options_*.dart` + `google-services.json`.
3. Uncomment the matching import + branch in
   `lib/config/firebase_initializer.dart`.
4. Put the real host-only domain in `env/env.<env>.json`.

`flutterfire configure` only generates config files — it does not edit the
selection logic, so step 3 is manual. Re-run it for an env only when that
project's config changes (adding SHA later does **not** require a re-run).

## iOS

iOS flavors (Xcode schemes, per-flavor build configurations, bundle-id suffix,
and the per-config `GoogleService-Info.plist` copy step) are **not set up yet** —
a follow-up for when a Mac or a macOS CI runner is available. The run commands
above are Android today.

## Security notes

- Transport is https everywhere except the local dev emulator/backend; the
  Android `network_security_config` only permits cleartext to `10.0.2.2` /
  `localhost`.
- Never commit real `env/env*.json` (only `*.example.json`) or Firebase
  service-account keys. `google-services.json` / `firebase_options_*.dart` hold
  client API keys (not secrets) and are committed.
- Logs must never carry tokens, the `Authorization` header, PIN, request/response
  bodies, or PII (card/account numbers, balances) — regardless of env.

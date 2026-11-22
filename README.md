# nhkcoop

Mobile app for members of a Thai savings co-operative (สหกรณ์ออมทรัพย์) — view
shares, loans, deposits, dividends, monthly billing/receipts, and manage account
security (PIN, biometric unlock, enrolled devices).

**Stack:** Flutter · MVVM · [Riverpod](https://pub.dev/packages/flutter_riverpod)
(codegen) · [go_router](https://pub.dev/packages/go_router) ·
[Dio](https://pub.dev/packages/dio) · Firebase Auth (phone OTP) ·
device-bound PIN (DeviceAuth).

**Platforms:** Android (primary, fully working) · iOS (UI builds, but the native
DeviceAuth key channel is not implemented yet — see
[docs/device-auth.md](docs/device-auth.md#ios)).

---

## Table of contents

- [Getting started](#getting-started)
- [Running the app](#running-the-app)
- [Code generation](#code-generation)
- [Documentation index](#documentation-index)
- [Architecture at a glance](#architecture-at-a-glance)
- [Project structure](#project-structure)
- [Tech stack](#tech-stack)
- [Conventions](#conventions)

---

## Getting started

### Prerequisites

- Flutter SDK matching `environment.sdk: ^3.11.3` in [`pubspec.yaml`](pubspec.yaml)
  (run `flutter --version`)
- Android Studio / Xcode for device emulators
- Access to the backend API base URL and Firebase project (ask the team lead)

### Install

```bash
flutter pub get
```

### Environment config (required)

The app targets three environments via native **flavors** (dev / staging / prod)
plus a per-env config file passed with `--dart-define-from-file`. The real
`env/env*.json` files are **git-ignored** (they hold environment-specific
values); only the `*.example.json` templates are committed. Copy the one you need:

```bash
cp env/env.dev.example.json     env/env.json          # dev
cp env/env.staging.example.json env/env.staging.json  # staging
cp env/env.prod.example.json    env/env.prod.json     # prod
```

| Key | Purpose |
|---|---|
| `BASE_URL` | Backend API **host[:port]**, no scheme (e.g. `api.example.com`) — the scheme is chosen per env (dev→http, staging/prod→https) |
| `FIREBASE_BASE_URL_DEV` | Firebase Auth **emulator** host — used only when `ENV=dev` |
| `FIREBASE_AUTH_PORT` | Firebase Auth emulator port (number) — used only when `ENV=dev` |
| `ENV` | Environment: `dev` / `staging` / `prod` (`dev` turns on the Firebase Auth emulator; staging/prod use https + the real Firebase project) |

Startup **fails fast** (`AppConfig.validate()`) if `BASE_URL` is empty, carries a
scheme, or a non-dev env points at a local host.

Firebase config lives per flavor at `android/app/src/<flavor>/google-services.json`
(and, for iOS, the matching `GoogleService-Info.plist`). They currently all point
at the `nhk-coop-staging` project as a placeholder — regenerate per env with
`flutterfire configure` once the real dev/staging/prod projects exist. See
[docs/env-flavors.md](docs/env-flavors.md).

For local development, run the **Firebase Auth emulator** (log in with fake phone
numbers, no real SMS) — full step-by-step in
[docs/firebase-emulator.md](docs/firebase-emulator.md).

---

## Running the app

Pick the flavor and its env file together:

```bash
flutter run --flavor dev     --dart-define-from-file=env/env.json
flutter run --flavor staging --dart-define-from-file=env/env.staging.json
flutter run --flavor prod    --dart-define-from-file=env/env.prod.json
```

In VS Code, add launch configurations that pass `--flavor <env>
--dart-define-from-file=env/env.<env>.json` (`.vscode/` is git-ignored, so these
stay local to your machine).

> **iOS flavors** (Xcode schemes / build configs) are not set up yet — a
> follow-up for when a Mac or a macOS CI is available. The commands above are
> Android today.

---

## Code generation

Riverpod providers, freezed models and JSON serializers are generated. After
changing any `@riverpod`, `@freezed`, or `*_api_model.dart` file, run a **full**
build:

```bash
dart run build_runner build --delete-conflicting-outputs
```

> ⚠️ **Never** combine `--delete-conflicting-outputs` with `--build-filter` — it
> wipes every other generated file. Always run the full build.

Analyze and test:

```bash
flutter analyze
flutter test
```

---

## Documentation index

Start here, then dive into the area you're working on. All docs are real,
code-backed references — read the relevant one before building in that area.

| Doc | What it covers |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Project guide — design tokens, architecture summary, "read this before building X" pointers |
| [docs/env-flavors.md](docs/env-flavors.md) | dev/staging/prod flavors, per-env config + scheme/validation, per-env Firebase, how to run each |
| [docs/architecture.md](docs/architecture.md) | MVVM three-layer split, folder tree, ViewModel/DI/repository/routing patterns, naming conventions |
| [docs/design-system.md](docs/design-system.md) | Color palette, typography scale, spacing, a themed-widget example |
| [docs/auth-session.md](docs/auth-session.md) | Login, registration, `POST /auth/session`, the `appAuth`/`AppAuthStatus` routing gate |
| [docs/biometric-login.md](docs/biometric-login.md) | Biometric unlock (`local_auth`), per-account secure-storage keying |
| [docs/device-auth.md](docs/device-auth.md) | **DeviceAuth** — device-bound EC PIN system: enrolment, unlock, timers, device management |
| [docs/pin-step-up.md](docs/pin-step-up.md) | **PIN step-up guide** — how to guard a sensitive action with a fresh PIN: endpoints, responses, status codes, and the Flutter classes to use |
| [docs/firebase-emulator.md](docs/firebase-emulator.md) | **Firebase Local Emulator Suite** — step-by-step local-dev setup (Auth emulator + phone OTP) |
| [docs/receipt-page.md](docs/receipt-page.md) | Receipt/document capture-to-gallery pattern |

---

## Architecture at a glance

MVVM with a strict three-layer split, wired together with Riverpod providers:

```
ui/<feature>/{screen,view_models,widgets}      presentation (ConsumerWidget + @riverpod ViewModels)
        │  ref.read(<feature>RepositoryProvider)
        ▼
domain/models/<feature>                        pure Dart / freezed models (UI-facing)
        ▲  maps *ApiModel → domain model
        │
data/repositories/<feature>                    repository interface + remote impl (Result<T>)
data/services/api/{api_client,model}           Dio-based API clients + freezed DTOs
```

- **State:** Riverpod only (`@riverpod` codegen) — no Provider/Bloc/GetX.
- **DI:** every cross-layer wire lives in `config/di/<feature>_dependencies.dart`.
- **Routing:** a single `go_router` in `lib/routing/router.dart`; auth/onboarding
  gating is centralized in its `redirect`. Full details in
  [docs/architecture.md](docs/architecture.md).

---

## Project structure

```
lib/
  config/        env, firebase, logging, api config + di/ (Riverpod providers)
  data/          repositories/ + services/ (api clients, DTOs, local services)
  domain/        models/ (freezed, UI-facing)
  routing/       router.dart, routes.dart, app_auth_status.dart
  ui/            core/ (themes, shared widgets, l10n) + <feature>/
  utils/         Result<T>, formatters, date/number helpers
  main.dart
docs/            architecture, design-system, auth-session, biometric-login,
                 device-auth, pin-step-up, receipt-page
env/             env.{dev,staging,prod}.example.json (real env.*.json are git-ignored)
```

---

## Tech stack

| Package | Role |
|---|---|
| [flutter_riverpod](https://pub.dev/packages/flutter_riverpod) · [riverpod_annotation](https://pub.dev/packages/riverpod_annotation) | State management (`@riverpod` codegen) |
| [go_router](https://pub.dev/packages/go_router) | Declarative routing + auth redirect gate |
| [dio](https://pub.dev/packages/dio) | HTTP client (`BaseApiService`) |
| [freezed_annotation](https://pub.dev/packages/freezed_annotation) · [json_annotation](https://pub.dev/packages/json_annotation) | Immutable models / unions + JSON (de)serialization |
| [firebase_core](https://pub.dev/packages/firebase_core) · [firebase_auth](https://pub.dev/packages/firebase_auth) | Phone-OTP authentication |
| [flutter_secure_storage](https://pub.dev/packages/flutter_secure_storage) | Secure storage (deviceId, PIN verifier, key aliases) |
| [shared_preferences](https://pub.dev/packages/shared_preferences) | Lightweight local flags (e.g. onboarding seen) |
| [local_auth](https://pub.dev/packages/local_auth) | Biometric unlock |
| [crypto](https://pub.dev/packages/crypto) | Salted SHA-256 local PIN verifier |
| [intl](https://pub.dev/packages/intl) | Thai date/number formatting, localization |
| [gal](https://pub.dev/packages/gal) | Save rendered receipt to the device gallery |
| [flutter_markdown_plus](https://pub.dev/packages/flutter_markdown_plus) | Render Terms & Conditions markdown |
| [logging](https://pub.dev/packages/logging) | Structured logging |

Dev/codegen: [build_runner](https://pub.dev/packages/build_runner),
[freezed](https://pub.dev/packages/freezed),
[riverpod_generator](https://pub.dev/packages/riverpod_generator),
[json_serializable](https://pub.dev/packages/json_serializable),
[flutter_lints](https://pub.dev/packages/flutter_lints).

---

## Conventions

- Follow [docs/architecture.md](docs/architecture.md) for folder placement, naming,
  and ViewModel/repository shape; follow [docs/design-system.md](docs/design-system.md)
  for colors/typography/spacing. Don't invent new tokens — every value has a source file.
- Keep the data → domain → presentation split; ViewModels never touch `data/`
  directly, only `ref.read(<feature>RepositoryProvider)`.
- Re-run the full `build_runner` after touching any generated source.
- See [CLAUDE.md](CLAUDE.md) for the per-area "read before you build" checklist.
- New to the repo? [CONTRIBUTING.md](CONTRIBUTING.md) covers the branch/PR/commit
  and code-generation workflow.
```

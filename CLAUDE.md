# nhkcoop — Project Guide

Flutter MVVM app (Thai savings co-op member app) using **Riverpod** for state management and **go_router** for navigation.

## Design tokens (quick reference)

- **Colors** — `lib/ui/core/themes/colors.dart` (`AppColors`). Key semantic colors: `darkBlueTints1` (#00008B, primary), `darkBlue` (#072A63), `gold1` (#D2AF26), `green1` (#008B46), `red1` (#E74C3C, error), `white1` (#FFF7FA), `grey1`–`grey5`, `black1`. Light `ColorScheme` only — dark theme is stubbed but commented out.
- **Typography** — one font family app-wide: `NotoSansThaiLooped`. Sizes come from `FontSizes.fromScreenType()` (`lib/ui/core/themes/font_sizes.dart`) which scales per `ScreenType` (handset/tablet/desktop) and feeds `TextTheme` in `lib/ui/core/themes/theme.dart` (headline/title/body/label × large/medium/small).
- **Spacing/sizing** — `AppSpacing` (`lib/ui/core/themes/spacing.dart`, sm/md/lg/xl, responsive mobile↔tablet) and `Dimens` (`lib/ui/core/themes/dimens.dart`, screen padding/logo/button sizes, responsive via `Dimens.of(context)`). Standard corner radius in inputs/buttons is `16`.
- **State management** — **Riverpod** (`flutter_riverpod` + `riverpod_generator`, `@riverpod` codegen). This is the only state approach in the codebase — always use `@riverpod` classes/functions + `ConsumerWidget`/`ConsumerStatefulWidget`, never `setState` for business state or other state libs.
- **Architecture** — MVVM: `ui/<feature>/{screen,view_models,widgets}` → `domain/models` → `data/{repositories,services}`, wired via `config/di/*_dependencies.dart` Riverpod providers. Navigation is `go_router`, routes centralized in `lib/routing/router.dart` + `lib/routing/routes.dart`.

## Before building anything new

- **Building a new widget or screen?** Read `docs/design-system.md` first — full color palette, typography scale, and a real themed-widget example.
- **Building a new ViewModel, repository, or feature folder?** Read `docs/architecture.md` first — full folder tree, a real ViewModel, a real View↔ViewModel binding, and naming conventions.
- **Building a screen that shows a document the member keeps (receipt, certificate, statement) — especially one they can save to their gallery?** Read `docs/receipt-page.md` first — the fixed-width template pattern, why zoom lives in a separate full-screen viewer, the image-capture pipeline, and the release-only crashes to avoid.
- **Touching PIN auth or adding another login shortcut?** Read `docs/biometric-login.md` first — how biometric unlock wraps `local_auth` behind the same success path as PIN verification, why the availability check and the OS prompt must stay one atomic async flow (splitting them caused a real race), the on-by-default/opt-out design, and the per-account secure-storage keying that keeps account switching/logout safe.
- **Touching login, registration, session verification, or the `appAuth`/`AppAuthStatus` routing gate?** Read `docs/auth-session.md` first — the `POST /api/v1/auth/session` contract, why `USER_DISABLED` gets typed `CustomerException` handling instead of the generic error fallback every other endpoint uses, the full `AppAuthStatus` → route table, and the register request payload (`membershipFullName`).
- **Touching ATM withdraw (ถอนเงิน ATM), the Krungsri API, or anything that sends the member to an external browser login?** Read `docs/atm-withdraw.md` first — the 10-step flow (prepare → OAuth popup → `coopapp://auth/callback` → `POST /link-account/complete` → initiate `COOP_WITHDRAW_M` co-op → member → confirm → success/failed result), why the callback goes through `flutter_web_auth_2` instead of a GoRouter deep link, the `ExternalAuthGuard` that stops the background PIN lock from killing the flow, and the open items (mocked credit limit, ops config).

Do not guess at colors, spacing, or folder placement — every value above has a real source file; check it rather than inventing a new one.

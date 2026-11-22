# Architecture

Read this before adding a new ViewModel, repository, screen, or feature folder. All examples are real code from this project.

## Folder tree

```
lib/
  config/                     # env, firebase, logging, api config
    di/                       # Riverpod DI providers, one file per feature
      <feature>_dependencies.dart      # e.g. profile_dependencies.dart
      <feature>_dependencies.g.dart    # riverpod_generator output
    app_config.dart
    app_env.dart
    api_config.dart
    env_parser.dart
    firebase_initializer.dart
    logging_config.dart
  data/
    repositories/<feature>/
      <feature>_repository.dart          # abstract interface
      <feature>_repository_remote.dart   # concrete impl, calls api_client
    services/
      api/
        api_client/<feature>_api_client.dart
        model/<feature>/<name>_api_model.dart  # freezed + json_serializable DTOs
        network/base_api_service.dart
      local/local_data_service.dart
  domain/
    models/<feature>/<name>.dart         # freezed domain models (UI-facing)
  routing/
    router.dart                          # GoRouter provider + redirect logic
    routes.dart                          # route path constants
    app_auth_status.dart
  ui/
    core/
      themes/                            # colors.dart, theme.dart, spacing.dart, dimens.dart, font_sizes.dart
      ui/                                 # shared/reusable widgets (buttons, loading, snackbar, shimmer, ...)
      responsive/device_size.dart         # ScreenType / getFormFactor
      localization/applocalization.dart
    <feature>/
      screen/<feature>_screen.dart        # top-level route widget
      view_models/<feature>_viewmodel.dart
      widgets/                            # feature-local widgets, not shared
    main_wrapper.dart                     # bottom-nav shell (StatefulShellRoute)
  utils/                                  # Result<T>, formatters, helpers (not feature-specific)
  main.dart
```

Feature folders present today: `auth` (login, logout), `onboarding`, `pin_code`, `change_pin`, `device_management`, `profile`, `register`, `service_center`, `terms`, `home`, `assets`, `deposit_accounts`, `shares`, `loans`, `dividend`, `guarantors`, `beneficiary`, `monthly_billing`.

Every feature follows the same three-layer split: `data/repositories/<feature>` + `data/services/api/{api_client,model}/<feature>` (data layer) → `domain/models/<feature>` (pure Dart models, freezed) → `ui/<feature>/{screen,view_models,widgets}` (presentation). Wiring between layers happens exclusively through `config/di/<feature>_dependencies.dart`.

## State management: Riverpod

`flutter_riverpod` + `riverpod_generator`/`riverpod_annotation` (codegen, `@riverpod`). This is the **only** state management approach in the codebase — no Provider/Bloc/GetX. Every screen/widget that reads state is a `ConsumerWidget` or `ConsumerStatefulWidget`; local ephemeral UI state (e.g. a checkbox toggle) may still use plain `setState` inside a `ConsumerStatefulWidget` (see `TermsSheet`'s `isRead` flag), but anything that comes from a repository or is shared across widgets goes through a `@riverpod` provider.

## ViewModel conventions

- File: `lib/ui/<feature>/view_models/<feature>_viewmodel.dart`, paired with a generated `<feature>_viewmodel.g.dart` (`part '<feature>_viewmodel.g.dart';`).
- Two shapes are used, pick based on need:
  1. **Class-based** `@riverpod class Foo extends _$Foo { Future<T> build() async {...} }` when the provider needs multiple methods/actions (mutations) beyond the initial load. Example — `lib/ui/terms/view_models/terms_conditions_viewmodel.dart`:
     ```dart
     @riverpod
     class TermsConditionsViewmodel extends _$TermsConditionsViewmodel {
       @override
       Future<TermsConditions> build() async {
         return await _termsConditionstents();
       }

       Future<TermsConditions> _termsConditionstents() async {
         final repo = ref.read(termsConditionsRepositoryProvider);
         final result = await repo.getTermsConditionsContents();
         ...
       }
     }
     ```
  2. **Function-based** `@riverpod Future<T> fooBar(Ref ref) async {...}` for a plain derived/loaded value with no extra actions. Example — `lib/ui/profile/view_models/profile_viewmodel.dart` defines several: `getMemberProfile`, `personalInfoFields`, `memberSummaries`, etc. Function-based providers commonly derive from another provider with `ref.watch(otherProvider.selectAsync((v) => ...))` to slice a bigger model into a screen-section-sized one (see `personalInfoFields`, `contactInfoFields`).
- ViewModels never call `data/` directly — they call `ref.read(<feature>RepositoryProvider)` (or `ref.watch` when combining multiple async providers, see `home_viewmodel.dart`'s `combinedHomeData`) and return/throw domain models. Repository results use the app's `Result<T>` (`lib/utils/result.dart`) `Ok`/`Error` pattern, switched on to log and unwrap.
- A `Logger` per file: `final _log = Logger('<ClassOrFunctionName>');` (from `package:logging`).

## View ↔ ViewModel binding

Real example, `lib/ui/home/widgets/home_balance.dart`:

```dart
class HomeBalance extends ConsumerWidget {
  const HomeBalance({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final memberData = ref.watch(combinedHomeDataProvider).requireValue;
    return Container(
      ...
      child: Text('฿ ${NumberFormatter.toCurrencyWithDecimal(memberData.memberFinancialSummaries.totalDeposit)}'),
    );
  }
}
```

For screens that need to show loading/error/data states explicitly (rather than `.requireValue`), use `AsyncValue.when` — see `lib/ui/terms/widgets/terms_sheet.dart`:

```dart
final termsConditionsContents = ref.watch(termsConditionsViewmodelProvider);
...
child: termsConditionsContents.when(
  loading: () => const Loading(),
  error: (err, stack) => Text('${AppLocalization.of(context).errorMessage}: $err'),
  data: (data) => /* build UI from data */,
),
```

Screens (`ui/<feature>/screen/*.dart`) are typically plain `StatelessWidget`s that lay out theme/gradient/padding chrome and delegate the actual data-bound content to a widget in `widgets/` (which is the `ConsumerWidget`/`ConsumerStatefulWidget`) — see `TermsScreen` → `TermsSheet`.

## Dependency injection pattern

`config/di/<feature>_dependencies.dart` declares `@riverpod` providers that construct API clients and repositories, injecting a `Dio` client (the HTTP layer; API clients extend `BaseApiService` — `lib/data/services/api/network/base_api_service.dart`) and cross-cutting providers like the auth token:

```dart
// lib/config/di/profile_dependencies.dart
@riverpod
ProfileApiClient profileClient(Ref ref) {
  final client = ProfileApiClient(
    client: Dio(),
    tokenProvider: ref.watch(authTokenProvider),
  );
  return client;
}

@riverpod
ProfileRepositoryRemote profileRepository(Ref ref) {
  return ProfileRepositoryRemote(apiClient: ref.watch(profileClientProvider));
}
```
ViewModels then do `ref.read(profileRepositoryProvider)` — they never construct a repository or API client themselves.

## Repository pattern

- `data/repositories/<feature>/<feature>_repository.dart` — abstract class defining the contract, methods return `Future<Result<DomainModel>>`.
- `data/repositories/<feature>/<feature>_repository_remote.dart` — implements the interface, calls the feature's `ApiClient`, maps the `*ApiModel` (freezed DTO) to the `domain/models` type, wraps success/failure in `Result.ok`/`Result.error` (catching `Exception`).

## Navigation / routing

`go_router`, single `GoRouter` built by a `@riverpod` provider in `lib/routing/router.dart` (`goRouterProvider`). Route path constants live in `lib/routing/routes.dart` (`abstract final class Routes`) — always reference `Routes.foo`, never a raw path string. Auth/onboarding gating is centralized in the router's `redirect` callback, driven by `ref.read(appAuthProvider)` / `ref.read(onboardingStateProvider)` and a `ValueNotifier`-based `refreshListenable`. The bottom-nav tabs (home / uitwo / services / profile) are declared as `StatefulShellRoute.indexedStack` branches wrapped by `MainWrapper` (`lib/ui/main_wrapper.dart`); everything else (splash, login, register, terms, pin code, profile detail) is a plain top-level `GoRoute`.

## Cross-cutting systems

Some concerns span many features and have their own focused docs — read these
before touching the relevant area:

- **Auth / session / routing gate** → [auth-session.md](auth-session.md)
  (`POST /auth/session`, the `appAuth` / `AppAuthStatus` redirect table).
- **DeviceAuth (device-bound PIN)** → [device-auth.md](device-auth.md)
  (enrolment, unlock, session timers, device management).
- **PIN step-up** (require a fresh PIN before a sensitive action) →
  [pin-step-up.md](pin-step-up.md) (endpoints, responses, status codes, the
  `showPinConfirmSheet` recipe).
- **Biometric unlock & per-account secure-storage keying** →
  [biometric-login.md](biometric-login.md).

## Naming conventions

| What | Convention | Example |
|---|---|---|
| Feature folder | snake_case, singular domain noun | `lib/ui/profile/`, `lib/ui/terms/` |
| Screen file/class | `<feature>_screen.dart` / `<Feature>Screen` | `lib/ui/terms/screen/terms_screen.dart` |
| ViewModel file/class | `<feature>_viewmodel.dart` / `<Feature>Viewmodel` (class) or lowerCamelCase function name | `lib/ui/terms/view_models/terms_conditions_viewmodel.dart` |
| Widget file/class | `<what_it_is>.dart` / PascalCase matching filename | `lib/ui/core/ui/primary_button.dart` → `PrimaryButton` |
| Repository interface | `<feature>_repository.dart` / `<Feature>Repository` | `lib/data/repositories/profile/profile_repository.dart` |
| Repository impl | `<feature>_repository_remote.dart` / `<Feature>RepositoryRemote` | `lib/data/repositories/profile/profile_repository_remote.dart` |
| DI provider file | `<feature>_dependencies.dart` | `lib/config/di/profile_dependencies.dart` |
| Domain model | `domain/models/<feature>/<name>.dart`, freezed | `lib/domain/models/profile/member_profile.dart` |
| API DTO | `data/services/api/model/<feature>/<name>_api_model.dart`, freezed + json_serializable | `lib/data/services/api/model/profile/member_profile_api_model.dart` |
| Route constant | `Routes.<camelCase>` | `Routes.profileDetail` in `lib/routing/routes.dart` |
| Riverpod provider names | auto-generated from function/class name + `Provider` suffix | `combinedHomeDataProvider`, `termsConditionsViewmodelProvider` |

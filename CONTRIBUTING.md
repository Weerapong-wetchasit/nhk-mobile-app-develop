# Contributing to nhkcoop

Thanks for working on nhkcoop. This guide covers how to set up, make a change, and
get it merged. For *how the code is organized*, read
[docs/architecture.md](docs/architecture.md) first.

## Setup

See the [README](README.md#getting-started): `flutter pub get`, create
`env/env.json` from `env/env.dev.example.json`, then run with
`flutter run --flavor dev --dart-define-from-file=env/env.json`. The app has
dev/staging/prod flavors — see [docs/env-flavors.md](docs/env-flavors.md).

## Branch & PR workflow

- Branch off **`develop`** (the default/integration branch).
- Name the branch after its issue: `<issue-number>-<short-slug>`
  (e.g. `55-docs-project-readme`).
- One PR per issue. Open the PR against `develop` and link the issue
  (`Closes #<n>`).
- PRs are **squash-merged** into `develop`, so the PR title becomes the commit —
  keep it clear and conventional (see below).
- Keep the PR focused; if it has natural steps, use one commit per step so the diff
  is easy to review.

## Commit messages

Use Conventional-Commits style: `type(scope): subject`.

```
feat(device-auth): add device management screen
fix(pin): re-prompt on FRESH_PIN_REQUIRED
docs: add PIN step-up guide
refactor(auth): move session verification to /auth/session
```

Common types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`. Scope is the
feature/area (e.g. `device-auth`, `profile`). Write the subject in the imperative
("add", not "added").

## Code generation

Riverpod providers, freezed models and JSON serializers are generated. After
editing any `@riverpod`, `@freezed`, or `*_api_model.dart` source, run a **full**
build and commit the regenerated `*.g.dart` / `*.freezed.dart` files:

```bash
dart run build_runner build --delete-conflicting-outputs
```

> ⚠️ **Never** add `--build-filter` to that command — combined with
> `--delete-conflicting-outputs` it wipes every other generated file. Always run
> the full build.

If the diff shows generated files whose source you didn't touch (hash-only or
line-ending churn), restore them so the PR only contains real changes:

```bash
git diff --ignore-cr-at-eol --numstat   # find files with real changes
git restore <unchanged-generated-file>  # drop pure churn
```

## Before you push

Both must pass:

```bash
flutter analyze    # lints via analysis_options.yaml (package:flutter_lints)
flutter test
```

## Conventions

Don't invent new values — every convention has a source:

- **Folder placement, naming, ViewModel/repository/DI shape** →
  [docs/architecture.md](docs/architecture.md)
- **Colors, typography, spacing** → [docs/design-system.md](docs/design-system.md)
- **State:** Riverpod only (`@riverpod`); `ConsumerWidget`/`ConsumerStatefulWidget`,
  never `setState` for business state.
- **Routing:** reference `Routes.<name>`, never a raw path string.
- **Per-area "read before you build" checklist** → [CLAUDE.md](CLAUDE.md)

## PR checklist

- [ ] `flutter analyze` is clean
- [ ] `flutter test` passes
- [ ] `build_runner` was re-run and generated files are committed (no stray churn)
- [ ] Branch is off `develop`, PR linked to its issue
- [ ] Docs updated if a new feature/system was added

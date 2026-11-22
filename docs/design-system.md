# Design System

Read this before building any new widget or screen. All values below are pulled directly from the current codebase — check the source file if you need something not covered here.

## Colors

Source: `lib/ui/core/themes/colors.dart` — `abstract final class AppColors`.

| Token | Hex | Notes |
|---|---|---|
| `darkBlueTints1` | `#00008B` | Primary (light `ColorScheme.primary`) |
| `darkBlueTints2` | `#24249C` | Focused input border |
| `darkBlueTints3` | `#4949AC` | Secondary (`ColorScheme.secondary`) |
| `darkBlueTints4` | `#6D6DBD` | |
| `darkBlueTints5` | `#9292CD` | |
| `darkBlueTints6` | `#B6B6DE` | |
| `darkBlueTints7` | `#DBDBEE` | `primaryContainer` |
| `darkBlueTones1` | `#121289` | `onPrimaryContainer` |
| `darkBlueTones2` | `#252588` | |
| `darkBlueTones3` | `#373786` | |
| `darkBlueTones4` | `#494985` | |
| `darkBlueTones5` | `#5B5B83` | |
| `darkBlueTones6` | `#6E6E82` | |
| `darkBlueTones7` | `#808080` | |
| `darkBlue` | `#072A63` | Used for solid brand surfaces (e.g. balance card) |
| `gold1` | `#D2AF26` | Accent |
| `gold2` | `#E9D793` | Accent, lighter |
| `gold3` | `#8B8B00` | Accent, muted |
| `green1` | `#008B46` | Positive / inflow (e.g. deposit arrow) |
| `green2` | `#008B00` | |
| `green3` | `#468B00` | |
| `black1` | `#101010` | Primary text on light surfaces |
| `white1` | `#FFF7FA` | `onPrimary` / surface |
| `grey1` | `#F2F2F2` | |
| `grey2` | `#4D4D4D` | Body text (markdown `p`), input borders |
| `grey3` | `#A4A4A4` | Hint text, `bodySmall`/`labelLarge`/`labelSmall` color |
| `grey4` | `#E5E4E2` | |
| `grey5` | `#D3D3D3` | |
| `whiteTransparent` | `rgba(255,255,255,0.3)` | Figma-sourced overlay |
| `blackTransparent` | `rgba(0,0,0,0.3)` | Figma-sourced overlay |
| `red1` | `#E74C3C` | Error (`ColorScheme.error`), negative/outflow |

`AppColors.lightColorScheme` maps these into a Material `ColorScheme`:
```dart
static const lightColorScheme = ColorScheme(
  brightness: Brightness.light,
  primary: AppColors.darkBlueTints1,
  onPrimary: AppColors.white1,
  secondary: AppColors.darkBlueTints3,
  onSecondary: AppColors.white1,
  surface: AppColors.white1,
  onSurface: AppColors.darkBlueTints1,
  error: AppColors.red1,
  onError: AppColors.white1,
  primaryContainer: AppColors.darkBlueTints7,
  onPrimaryContainer: AppColors.darkBlueTones1,
);
```
There is no dark theme yet — a `darkColorScheme` exists but is commented out in `colors.dart`, and `AppTheme` only exposes `lightTheme`. Always use `Theme.of(context).colorScheme.*` for semantic colors and reach for a raw `AppColors.*` constant only when the semantic scheme doesn't have an equivalent (e.g. `green1`/`red1` for directional balances).

## Typography

Font family: **`NotoSansThaiLooped`** (weights 400/500/700, assets under `assets/google_fonts/`), set once in `AppTheme.lightTheme` via `fontFamily: 'NotoSansThaiLooped'`.

Sizes are **responsive** — not fixed — driven by `FontSizes.fromScreenType(ScreenType)` in `lib/ui/core/themes/font_sizes.dart`, computed once in `main.dart` from `getFormFactor(context)` and passed into `AppTheme.lightTheme(fontSizes)`.

| Style | Weight | handset | tablet | desktop |
|---|---|---|---|---|
| `headlineLarge` | w500 | 32 | 42 | 52 |
| `headlineMedium` | w500 | 24 | 34 | 44 |
| `headlineSmall` | w400 | 18 | 28 | 38 |
| `titleLarge` | w500 | 22 | 32 | 42 |
| `titleMedium` | w500 | 18 | 28 | 38 |
| `bodyLarge` | w400 | 18 | 28 | 38 |
| `bodyMedium` | w400 | 16 | 26 | 36 |
| `bodySmall` | w400, color `grey3` | 14 | 24 | 34 |
| `labelLarge` | w400, color `grey3` | 18 | 22 | 32 |
| `labelSmall` | w500, color `grey3` | 10 | 14 | 24 |

Defined in `lib/ui/core/themes/theme.dart` (`AppTheme._textTheme`). Always consume via `Theme.of(context).textTheme.<style>`, optionally `.copyWith(color: ...)` for one-off color overrides (this is the established pattern — see `home_balance.dart`, `terms_sheet.dart`).

## Spacing & sizing

- `AppSpacing` (`lib/ui/core/themes/spacing.dart`): `sm`/`md`/`lg`/`xl`/`boxOtp`, each responsive mobile↔tablet (e.g. `AppSpacing.md(context)` = 16 mobile / 24 tablet). Call as a function: `AppSpacing.xl(context)`.
- `Dimens` (`lib/ui/core/themes/dimens.dart`): screen-edge padding and component sizing, responsive mobile↔desktop via `Dimens.of(context)`. Provides `paddingScreenHorizontal`/`paddingScreenVertical`, `edgeInsetsScreenHorizontal`/`edgeInsetsScreenSymmetric`, `profilePictureSize`, `appLogoSize`/`appLogoSizeLarge`, `startButton`.
- Static constants `Dimens.paddingHorizontal` (20) / `Dimens.paddingVertical` (24) are the raw values `_DimensMobile` uses.
- Corner radius convention: **16** (input borders in `theme.dart`, cards like `home_balance.dart`). Buttons take an explicit `radius` param (see `PrimaryButton`).

## Real themed-widget example

`lib/ui/core/ui/primary_button.dart` — shows theme color, responsive `Dimens`, and conditional loading state, the standard pattern for interactive widgets:

```dart
class PrimaryButton extends StatelessWidget {
  const PrimaryButton({
    super.key,
    required this.radius,
    required this.isLoading,
    required this.textButton,
    required this.onPressed,
  });

  final double radius;
  final bool isLoading;
  final String textButton;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: Dimens.of(context).startButton,
      child: ElevatedButton(
        onPressed: isLoading ? null : onPressed,
        style: ElevatedButton.styleFrom(
          backgroundColor: Theme.of(context).primaryColor,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radius),
          ),
        ),
        child: isLoading
            ? const LoadingButton()
            : Text(
                textButton,
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                  color: Theme.of(context).colorScheme.onPrimary,
                ),
              ),
      ),
    );
  }
}
```

Notes on the pattern: never hardcode a size/padding that already has a `Dimens`/`AppSpacing` token; pull text style from `Theme.of(context).textTheme` and override only the color when needed; `InputDecorationTheme` (also in `theme.dart`) already defines border colors/radius/error states for all text fields app-wide, so form fields shouldn't redeclare borders.

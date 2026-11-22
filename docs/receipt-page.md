# Receipt pages (saveable documents)

Read this before building any screen whose job is to show a **document the
member keeps** — a receipt, a certificate, a statement — especially if they can
save it to their photo gallery.

The worked example throughout is `ใบเสร็จรับเงิน` in `lib/ui/monthly_billing/`.
All code below is real code from this project.

## The problem this pattern solves

A normal screen only has to look right on the phone in front of you. A document
screen has to do three jobs at once, and they pull against each other:

1. **Read on screen.** A phone is ~390pt wide; a formal document with a
   five-column table is not.
2. **Rasterise to a file.** The saved image must be identical on every handset,
   because it becomes the member's permanent copy.
3. **Stay in sync.** If the preview and the saved file are built by different
   widgets, they *will* drift apart.

The pattern is: **one fixed-width template widget**, scaled down for the
preview, captured at its own size for the file, and given a separate full-screen
viewer for reading.

## 1. Build the template

The template is a plain `StatelessWidget` that lays out at a **fixed logical
width** and is the single source of truth for the document.

```dart
class ReceiptTemplate extends StatelessWidget {
  static const double templateWidth = 480;
  ...
}
```

### Rules the template must follow

**Fixed width, content-driven height.** Pick the narrowest width at which the
content is still legible — for the receipt that is 480, the point at which the
Thai column headings (`เงินต้น`, `ดอกเบี้ย`) stop wrapping. Never make it
responsive: a document that reflows per device produces a different image per
device.

**Pin off text scaling.** A member with large system fonts would otherwise get
an overflowing, differently-proportioned document:

```dart
return MediaQuery.withNoTextScaling(
  child: DefaultTextStyle(
    style: const TextStyle(fontFamily: 'NotoSansThaiLooped', ...),
```

**Use explicit font sizes, not the `TextTheme`.** `FontSizes.fromScreenType()`
scales per device — correct for app chrome, wrong for a document. The template
sets sizes literally (`fontSize: 10.5`) so the output never changes.

**Give it an opaque background.** `toImage()` preserves transparency, and a
transparent receipt is unreadable against a dark gallery background:

```dart
child: Container(
  width: templateWidth,
  color: Colors.white,
```

**Never let a number wrap.** `1,234,567.89` broken across two lines is
unreadable on a financial document. Money cells shrink instead:

```dart
FittedBox(
  fit: BoxFit.scaleDown,
  alignment: align,
  child: Text(text, maxLines: 1, softWrap: false, ...),
)
```

**Expose any asset image as a static.** The screen needs it to precache — see
§3.

```dart
static const AssetImage logoImage = AssetImage('assets/logo/nhk_logo.png');
```

### Choosing the capture size

Multiply the design width by a **fixed** `pixelRatio`, never the device DPR:

| Design width | `pixelRatio` | Output |
| --- | --- | --- |
| 480pt | 3.0 | 1440 × ~2390px, well under 1 MB |

`ReceiptImageSaver.capturePixelRatio` holds this value. If encoding is slow on
low-end devices, 2.0 still yields a perfectly readable 960px-wide image.

## 2. Show it, and let members zoom

### On the page

Scale the template to fit and mark the capture target. The `RepaintBoundary`
goes **inside** the `FittedBox`, so it lays out at its natural 480pt and the
`FittedBox` only affects how it is painted:

```dart
_ReceiptPaper(
  onTap: () => ReceiptFullScreenViewer.show(context, ...),
  child: RepaintBoundary(
    key: _receiptBoundaryKey,
    child: ReceiptTemplate(...),
  ),
)
```

Everything outside that boundary — app bar, background, save button — is
excluded from the saved image.

### Why zoom is not done in place

The obvious approach is an `InteractiveViewer` around the document. It does not
work on a scrolling page:

- With `panEnabled: true`, the viewer swallows the vertical drag and the page
  stops scrolling.
- With `panEnabled: false`, pinch-zoom works but you cannot move around once
  zoomed in, which defeats the purpose.

So the page itself does **no** zooming. Tapping the document opens
`ReceiptFullScreenViewer`, which owns the whole screen and therefore has no
gesture to compete with. Give the document a visible affordance — the receipt
uses a corner zoom badge plus a `แตะที่ใบเสร็จเพื่อดูขนาดเต็ม` hint.

### The viewer

`InteractiveViewer` with `minScale: 1, maxScale: 4` over a `BoxFit.contain`
template, plus animated double-tap zoom. Two things to get right:

**Build the `AnimationController` in `initState`, never as a lazy
`late final`.** A lazy initialiser does not run until the first zoom, so
closing the viewer *without* zooming constructs the controller inside
`dispose()`. `vsync: this` then looks up `TickerMode` on an
already-deactivated element and throws
*"Looking up a deactivated widget's ancestor is unsafe"*. This bug shipped once;
do not reintroduce it.

```dart
late final AnimationController _animationController;

@override
void initState() {
  super.initState();
  _animationController = AnimationController(vsync: this, ...);
  _zoomCurve = CurvedAnimation(parent: _animationController, ...);
  _animationController.addListener(_applyZoomFrame);
}
```

**Reuse one `CurvedAnimation` and one listener.** Creating a fresh
`CurvedAnimation` per double-tap leaves every previous one attached to the
controller, all writing competing matrices every frame.

Double-tap zooms about the tapped point. The matrix is built with `setEntry`
rather than the `translate`/`scale` helpers, which take numeric arguments that
recent Flutter versions are deprecating:

```dart
return Matrix4.identity()
  ..setEntry(0, 0, scale)
  ..setEntry(1, 1, scale)
  ..setEntry(0, 3, -_doubleTapPosition.dx * (scale - 1))
  ..setEntry(1, 3, -_doubleTapPosition.dy * (scale - 1));
```

The viewer builds its **own** `ReceiptTemplate` rather than reusing the one
behind it, leaving the page's capture boundary untouched.

## 3. Save to the gallery

`lib/utils/receipt_image_saver.dart` owns the whole pipeline:

```
GlobalKey → RenderRepaintBoundary → endOfFrame → toImage(3.0)
          → PNG bytes → Gal.hasAccess/requestAccess → Gal.putImageBytes
```

It returns a `ReceiptSaveResult` (`success`, `permissionDenied`,
`notEnoughSpace`, `failed`) so the screen can pick the right message without
knowing anything about `GalException`.

### Never call `debugNeedsPaint`

The capture must not rasterise a half-painted document, but do **not** test for
it this way. `RenderObject.debugNeedsPaint` assigns its result inside an
`assert`, which is stripped in release builds — so it throws
`LateInitializationError` in production while working perfectly in debug. Always
wait one frame instead:

```dart
await SchedulerBinding.instance.endOfFrame;
final image = await boundary.toImage(pixelRatio: capturePixelRatio);
```

This is the general rule: **anything named `debug*` is debug-only.** Do not put
it on a production path.

### Precache asset images before capturing

`Image.asset` decodes asynchronously and `toImage()` captures only what is
currently painted, so a member who taps save quickly would get a document with a
blank letterhead. The screen starts the decode as soon as it has a context and
awaits it before capturing:

```dart
@override
void didChangeDependencies() {
  super.didChangeDependencies();
  _logoReady ??= precacheImage(ReceiptTemplate.logoImage, context);
}

Future<void> _onSavePressed(String receiptNo) async {
  final localization = AppLocalization.of(context);
  await _logoReady;
  if (!mounted) return;
  ...
}
```

### Permissions

`gal` handles the prompts; what the member sees depends on the platform:

| Platform | Prompt? | Config required |
| --- | --- | --- |
| Android 10+ (API 29+) | **None** — MediaStore under scoped storage | — |
| Android 9 and below | Storage permission | `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="29"` |
| iOS | Add-only Photos prompt, **once** | `NSPhotoLibraryAddUsageDescription` |

Two deliberate choices:

- **No album is passed** to `putImageBytes`. Passing one forces iOS to demand
  full library access; without it, add-only is enough and the app never gains
  read access to the member's photos.
- **The prompt fires on the save tap**, not on screen load, so members are not
  asked for gallery access merely to look at a receipt.

iOS only ever asks once. If the member declines, the app cannot re-prompt — the
`permissionDenied` message must point them at Settings rather than offer a
retry.

## Checklist for a new document screen

- [ ] Template is a fixed-width `StatelessWidget`, content-driven height
- [ ] `MediaQuery.withNoTextScaling` wraps it
- [ ] Explicit font sizes, not `TextTheme`
- [ ] Opaque background colour
- [ ] Numbers use `FittedBox` + `maxLines: 1`, never wrap
- [ ] Asset images exposed as statics and precached before capture
- [ ] `RepaintBoundary` inside the `FittedBox`, wrapping only the document
- [ ] No `InteractiveViewer` on the scrolling page; tap opens a full-screen viewer
- [ ] Viewer's `AnimationController` built in `initState`
- [ ] Capture waits on `endOfFrame`; no `debugNeedsPaint`
- [ ] Fixed `pixelRatio`, not the device DPR
- [ ] Android manifest + iOS `Info.plist` entries added
- [ ] Verified in a **release** build on a real device

That last item is not boilerplate. Both the `debugNeedsPaint` crash and the
viewer `dispose` crash are invisible to `flutter analyze` and to on-screen
inspection in debug.

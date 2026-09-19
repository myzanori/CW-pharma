# Changelog

All notable changes to the CW Pharmacy PDF Saver will be documented in this file.

## [v1.5.4] - 2026-09-19
> Rebuilt for **CW Pharmacy 1.1.4**. The upstream 1.1.3 -> 1.1.4 refactor silently
> broke several hooks; this release makes them resilient so future app updates can
> no longer quietly disable the module.

### Compatibility
- **Upstream app:** CW Pharmacy `1.1.3` -> `1.1.4`. The PDF viewer lifecycle moved to
  `BasePdfFileRendererActivity`, and the number of classes owning a screenshot flag grew
  from 12 to 23, which is what broke the previous hooks.
- **Module version:** `1.4.0` (versionCode `6`) -> `1.5.4` (versionCode `11`).
- Built against libxposed API 102, `minSdk` 26, `targetSdk` 36.

### Added
- The PDF download bar is now injected from `Activity.onResume` and detected from the
  intent extras (`url`/`uri`) plus a `pdf` class name, so it keeps working even when the
  viewer is renamed, moved or replaced. Covers `PdfViewerActivity`,
  `AndroidXPdfViewerActivity`, `PdfViewer2Activity`, `PdfWebViewActivity` and
  `NewPDFViewerActivity`.
- A runtime screenshot-gate scanner on `BaseDexClassLoader.findClass` that neutralises
  every `isScreenshotEnabled` variant it finds, instead of trusting a fixed class list.

### Fixed
- Launch crash caused by a `void` screenshot gate that the old hook only handled as a
  `boolean` return.
- Crash in the module's own list screen: `PdfAdapter.setCursor()` no longer closes the
  `CursorLoader`-owned cursor, which caused
  `attempt to re-open an already-closed object: SQLiteQuery` after a stop/start cycle.
- "Please disable screenshot" toast and the player auto-closing, by bypassing
  `mm.b.F(Activity)`, the predicate the player activities use to `finish()` themselves.
- The "Please Disable USB Debugging" gate: `Settings.Secure.getInt`,
  `Settings.Global.getInt` and `DashboardViewModel.isDevelopmentSettingsEnabled()` are
  neutralised, and `FirebaseVersionModel.getUsb()` is forced to `0` so the branch is
  skipped upstream even if the Settings hooks get inlined.

### Changed
- All hooks now run with `ExceptionMode.PROTECTIVE`, so a hook fault can no longer crash
  the host app.
- `FLAG_SECURE` is stripped in `Window.setFlags` and `Window.addFlags`; all ~170 flag
  sites in 1.1.4 funnel through these two.
- Release builds: fixed R8 / `lintVitalAnalyzeRelease` OOM by raising the Gradle heap and
  disabling `lintVital` for release builds.

## [v1.4.0] - 2026-08-18
### Added
- Injected a bottom download bar (starting from the left edge) into the PDF viewer that fetches, decrypts, and saves the PDF.
- The download bar auto-hides while the PDF is scrolled and reappears when scrolling stops.
- The download bar is positioned to end just before the app's existing FAB menu so the two never overlap.

### Changed
- Switched to the modern libxposed API 102 and bumped `minSdk` to 26.
- Fixed the release build's ProGuard/R8 rules so the `MainHook` entry class is never obfuscated (was silently breaking module loading).
- Rebranded the module/APK/download filename and all Telegram/GitHub links to `myzanori`.
- Rewired the scroll-to-hide listener to the content root instead of the PDFView so PDF scrolling/pinch is no longer broken.

### Fixed
- Fixed a launch crash caused by the app's `isScreenshotEnabled()` dereferencing a null `CrashViewModel`.
- Bypassed the app's boolean screenshot gates that blocked opening paid PDFs/videos.
- Disabled the app's "Screenshot Disabled. Restarting app" detection (Appx.a) that restart the app when a screenshot module is active.
- Removed the detectable installer-spoofing hooks and framework `Window.setFlags` hook, matching the older undetected module layout.

## [v1.2.0] - 2026-06-10
### Added
- Injected a global welcome popup dialog when launching CW Pharmacy to confirm module activation.
- Added quick action buttons on the popup to Join the Telegram Community and Star the project on GitHub.

## [v1.0.2] - 2026-06-10
### Changed
- Refined the floating action button UI by replacing "DL" text with a native download icon (⬇️).
- Lowered the minimum SDK requirement to Android 7.0 (API 24) to support older devices.
- Refined GitHub-to-Telegram Action workflows to dynamically inject custom APK names (`cw-pharma-[version]-myzanori.apk`).

## [v1.0.1] - 2026-06-10
### Changed
- Stripped away the experimental client-side paywall unlocker.
- Core focus cleanly adjusted strictly to the PDF Downloader & Decryptor features.
- Implemented automated CI/CD Github Actions pipelines for Telegram distribution.

## [v1.0.0] - 2026-06-10
### Added
- Initial Release of the CW Pharmacy module based on `libxposed` API 101.
- Injected custom Material Design Download button securely into the CW Pharmacy PDF Viewer.
- Added intelligent network decryption hooks that automatically fetch XOR/AES protected PDFs, strip their encryption layer, and safely extract the raw, readable `.pdf` to the device's `Downloads` directory.

# Camoron

AI-enabled camera for Android. Camoron captures through Camera2 with full manual controls and
enhances the image on-device (lens distortion correction, noise reduction, upscaling). It enhances
the photo the camera actually captured; it does not recreate or generate scene content.

## Status

MVP 1 in progress. See the milestone plan in the project thread.

| Milestone | State |
|---|---|
| 1. Project foundation, CI | Done |
| 2. Camera2 core, live preview, onboarding consent gate | Done |
| 3. Pro mode manual controls | ISO, shutter, EV, WB, RAW (DNG) and histogram done |
| 4. Processing pipeline | Enhance (scene-adaptive tone, clarity, detail; live on preview), Night merge, hardware noise reduction and distortion correction done; neural-network stages not started |
| 5. Saving with EXIF carry-over | Basic MediaStore save done |
| 6. Subscription, paywall, compliance screens | Not started (tier rules in `FeatureGate`) |

Camera UI plan v2 (phases A to F):

| Phase | State |
|---|---|
| A. Device scan, new layout, flash, timer, aspect ratio, settings | Done |
| B. Lenses and focus | Done |
| C. Looks and night | Done: six shot modes live on the preview and photo; Night via the maker extension or an on-device multi-frame merge; hardware noise reduction and distortion correction on stills |
| D. Video family | Video (1080p, 4K where supported), Cinematic 24 fps and Timelapse (5× to 60×), with Enhance and looks drawn into the recording on the GPU, stabilization, torch and sound |
| E. Portrait, Scan, Google Lens | Not started |
| F. Dual capture, Panorama | Not started |

## Architecture

Clean Architecture with MVVM, one Gradle module per layer:

```
:app                  Application, MainActivity, navigation
:core:common          Dispatchers, SurfacePreviewTarget
:core:designsystem    Compose theme and shared controls
:domain               Pure Kotlin models, repository interfaces, use cases
:data:camera          Camera2Controller, CapabilityMapper
:data:media           MediaStore writer, last-capture lookup
:data:processing      Saved-photo adjustments: aspect crop, selfie mirror
:data:settings        On-device consent and camera settings stores (DataStore)
:feature:onboarding   Disclaimer, minimum-spec check, permissions
:feature:camera       Camera screen, top menu, Pro mode controls
:feature:settings     Settings and Camera info screens
```

Key rules:

* Hardware capabilities are read once by `CapabilityMapper`; every manual control in the UI is
  enabled or disabled from the resulting `CameraCapabilities`. LEGACY devices never get manual
  sensor controls even if they report ranges.
* `CameraController.deviceProfile()` scans every camera at startup (flash, zoom range, focal
  length, close focus, frame rates, high-speed video, maker extensions, concurrent cameras,
  mandatory shutter sound). Menus and modes only appear when the profile says the hardware can do
  them.
* `SanitizeManualSettings` clamps user values to the camera's ranges before they reach Camera2.
* `FeatureGate` is the only place that decides what Pro (including the 1-month trial) and Basic
  (after the trial) can do.
* All processing is on-device. Consent is stored locally and excluded from backups.

## Building

Requires JDK 17 and the Android SDK (compileSdk 35).

```
./gradlew testDebugUnitTest :domain:test   # unit tests
./gradlew assembleDebug                    # debug APK
```

minSdk 26, targetSdk 35, application ID `com.camoron.app`.

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
| 3. Pro mode manual controls | ISO, shutter, EV, WB done; RAW capture next |
| 4. Processing pipeline (OpenCV + LiteRT) | Not started |
| 5. Saving with EXIF carry-over | Basic MediaStore save done |
| 6. Subscription, paywall, compliance screens | Not started (tier rules in `FeatureGate`) |

## Architecture

Clean Architecture with MVVM, one Gradle module per layer:

```
:app                  Application, MainActivity, navigation
:core:common          Dispatchers, SurfacePreviewTarget
:core:designsystem    Compose theme and shared controls
:domain               Pure Kotlin models, repository interfaces, use cases
:data:camera          Camera2Controller, CapabilityMapper
:data:media           MediaStore writer
:data:settings        On-device consent store (DataStore)
:feature:onboarding   Disclaimer, minimum-spec check, permissions
:feature:camera       Camera screen, Pro mode controls
```

Key rules:

* Hardware capabilities are read once by `CapabilityMapper`; every manual control in the UI is
  enabled or disabled from the resulting `CameraCapabilities`. LEGACY devices never get manual
  sensor controls even if they report ranges.
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

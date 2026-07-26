# TRELLIS 3D Studio

A native Android app (Kotlin + Jetpack Compose) for **text-to-image** and **image-to-3D**
generation, similar to Tripo3D.

## Features

- **Text to Image** — prompt-based image generation via the free
  [Pollinations FLUX](https://image.pollinations.ai) API (no key required), with a
  one-tap "Make it 3D" handoff.
- **Image to 3D** — pick an image from the gallery (or use a generated one) and send it
  to the **NVIDIA TRELLIS** API (`build.nvidia.com`) to produce a textured 3D model.
  Handles both synchronous and queued (202 + polling) NVCF responses.
- **3D preview** — GLB rendering with Filament via
  [SceneView](https://github.com/SceneView/sceneview-android); drag to rotate,
  pinch to zoom.
- **Download** — saves the `.glb` to `Downloads/TRELLIS` via MediaStore.
- **History** — past generations stored locally with Room (thumbnail + date); tap a 3D
  entry to reopen the viewer, tap an image entry to send it back to the 3D tab.
- Dark Material 3 UI, bottom navigation, graceful error handling with retry.

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Navigation Compose · Retrofit + OkHttp ·
Coil · Room (KSP) · SceneView/Filament

## NVIDIA API key

Enter your key in the app's **Settings** tab (get a free key at
[build.nvidia.com](https://build.nvidia.com) → microsoft/trellis; it starts with
`nvapi-`). The key is stored locally on the device via SharedPreferences.

Alternatively a default key can be baked in at build time as
`BuildConfig.TRELLIS_API_KEY` (used as fallback when Settings is empty):

```bash
./gradlew assembleDebug -PTRELLIS_API_KEY=nvapi-xxxxxxxx
```

Without any key the app runs fine; the 3D screen shows an error pointing to Settings.

## Build

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk` (minSdk 29, targetSdk 35).

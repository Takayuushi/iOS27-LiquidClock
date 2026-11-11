# iOS 27 Liquid Clock

An Android clock preview and home-screen widget inspired by liquid-glass visual design.

## Build

GitHub Actions builds a debug APK with JDK 17 and uploads it as an artifact. Open **Actions → Android APK Build** to download the latest build.

## Platform notes

- Minimum Android version: 8.0 (API 26).
- Android 12+ uses cross-window blur when supported by the device and system.
- Android 13+ uses AGSL for animated glass lighting and procedural optical shading.
- Older devices use a Canvas-drawn glass fallback.
- The floating preview is an opt-in application overlay. It does not replace or bypass Android's secure lock screen.
- AGSL cannot sample another application's live framebuffer. The compositor provides real background blur; the shader renders the surface's lighting and edge effects.

## Package

`com.takayuushi.ios27liquidclock`

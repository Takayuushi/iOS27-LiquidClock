# iOS 27 Liquid Clock

An Android 17 (API 37) clock preview and home-screen widget inspired by liquid-glass visual design.

## Platform

- Android 17 / API 37 only (minSdk = 37, compileSdk = 37, targetSdk = 37).
- JDK 17, Android Gradle Plugin 9.5.1, Gradle 9.6.1.
- AGP 9 built-in Kotlin support; no separate Kotlin Android or AppCompat plugin.
- AGSL RuntimeShader renders the animated glass surface, including lighting, chromatic edge accents, a procedural refraction field and interactive light response.
- WindowManager FLAG_BLUR_BEHIND with setBlurBehindRadius() provides real compositor blur of the content behind the overlay when the device enables cross-window blur.

## Important rendering limitation

Android does not expose another application's live framebuffer to an ordinary application overlay or AGSL shader. The compositor blurs the real content behind the overlay. The AGSL shader then renders the surface's own optical distortion and color refraction using an internal procedural color field; it cannot refract arbitrary pixels from the app underneath.

Cross-window blur is system-controlled and can be disabled by the device or runtime conditions. The service listens for availability changes and removes the blur flag when the compositor reports that blur is unavailable.

## Overlay behavior

The floating preview is explicitly started by the user, requires Android's Display over other apps permission, and runs as a declared special-use foreground service with a persistent notification. Tap the glass surface or use the notification's Stop action to dismiss it.

This is an application overlay preview. It does not replace, cover, or unlock Android's secure system lock screen.

## Home-screen widget

The home-screen widget uses standard RemoteViews and TextClock elements. Android widgets do not support arbitrary custom View rendering, so the AGSL animation is used in the in-app and floating preview, not in the launcher widget.

## Build

The GitHub Actions workflow builds app/build/outputs/apk/debug/app-debug.apk using JDK 17 and uploads it as an artifact. Open Actions → Android 17 APK Build to download it.

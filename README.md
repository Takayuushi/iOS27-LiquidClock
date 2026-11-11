# iOS 27 Liquid Clock

An Android 17 (API 37) clock preview and home-screen widget inspired by Apple's oversized Lock Screen typography.

## Package and platform

- Unified package, namespace and application ID: com.example.ios27clock.
- Android 17 / API 37 only (minSdk = 37, compileSdk = 37, targetSdk = 37).
- JDK 17, Android Gradle Plugin 9.5.1, Gradle 9.6.1.

## Home-screen widget renderer

The launcher widget uses a transparent ImageView inside RemoteViews. LiquidGlassWidgetProvider renders the time into an ARGB_8888 bitmap using Android Canvas and Paint, measures its font metrics and available widget dimensions from AppWidgetManager options, and fits the clock text to the usable bounds. The SF Pro Display OTF is copied into assets by GitHub Actions, copied into app-private storage at runtime, and loaded using Typeface.createFromFile.

The clock is drawn as one horizontal time string (h:mm or HH:mm) to match the iOS Big Clock composition. The provider rerenders on widget updates/resizes and system time, date, timezone, and locale changes.

## Wallpaper overlay

LiquidLockService creates a full-screen translucent TYPE_APPLICATION_OVERLAY window. FLAG_SHOW_WALLPAPER requests the system wallpaper surface behind the overlay; FLAG_BLUR_BEHIND with setBlurBehindRadius(45) asks the compositor to blur what is behind that window. The service listens for runtime cross-window blur availability changes.

The overlay is a visual preview. It does not replace, cover, or unlock Android's secure system lock screen. A full-screen preview also temporarily covers home-screen interaction; tap it or use the notification's Stop action to dismiss.

## Build

GitHub Actions downloads the SF Pro Display Thin OTF into res/font and copies it into assets/fonts for the Canvas renderer. The workflow builds app/build/outputs/apk/debug/app-debug.apk and uploads it as an artifact.

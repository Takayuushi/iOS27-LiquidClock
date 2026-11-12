# Liquid Clock Digit Lab

A separate Android APK that previews the same copied wallpaper renderer, SDF glyph mask and AGSL shader as the main project.

- Pick any wallpaper image.
- Type any 1–10 character digit/colon sample.
- Quick samples: `0123456789`, `00:00`, `11:11`, through `99:99`.
- Switch between Liquid Glass and Frosted Glass.
- Adjust SF Pro width style and weight.
- Drag the clock and pinch vertically for height or horizontally for width.

## Isolation
- Independent Gradle root under `digit-lab/`, not included in the production root settings.
- Separate application ID: `com.example.ios27clock.digitlab`.
- Separate files/preferences sandbox.
- No wallpaper service and no calls to the Android WallpaperManager; it previews only inside the test app.
- Production app source, manifest, Gradle settings and behaviour are unchanged.

Build locally with `gradle -p digit-lab assembleDebug`. GitHub Actions uploads a separate `liquid-clock-digit-lab-debug` artifact. SF Pro font assets are downloaded and subset at build time, not committed.

# iOS 27 Liquid Clock — Project Memory

> Living handover notes for future development sessions. Documentation only: this file is not read by the Android app, Gradle, or the rendering code.

## Project identity and working rules

- **Repository:** `Takayuushi/iOS27-LiquidClock`
- **Active development branch:** `feature/live-wallpaper-big-clock`
- **Do not switch to or commit to `main` unless explicitly asked.** The active feature branch contains the current advanced live-wallpaper work; `main` is substantially behind it.
- **Platform:** Android 17 / API 37, Kotlin, JDK 17, package `com.example.ios27clock`.
- **Visual goal:** an iOS-inspired oversized lock-screen clock rendered as convincing liquid glass over the user's own wallpaper, including depth/object occlusion.
- **Release naming agreed with the user:** first release is **Release 1**; the next iteration is **I++** (1++ = 2). The app icon direction is a liquid-glass Android logo with “27”.
- The user expects real repository inspection, commit inspection, and actual GitHub Actions/build-log checks before reporting status. Never describe a build as successful until its workflow run says `completed / success`.
- **After every code commit, update this file** with what changed, what remains, current commit/build state, known regressions and next test priorities. Keep the file documentation-only; do not wire it into app functionality.

## Important rendering architecture

- `LiquidWallpaperRenderer` is the shared clock compositor. It draws the wallpaper, clock glyph mask, AGSL liquid-glass shader, and foreground-object occlusion.
- `LiquidWallpaperService.LiquidEngine` uses this renderer for the installed live wallpaper.
- `ClockAppearanceActivity.WallpaperClockPreview` also uses this same renderer and is the in-app live-wallpaper preview. **Treat that preview and the installed wallpaper as one visual product:** changes should produce the same clock material and geometry in both. There are separate touch-event handlers, but not separate visual implementations.
- The renderer uses cached glyph masks and a signed-distance-field (SDF) bitmap for shader lighting/refraction. During gestures, use cached textures and canvas transforms; do not regenerate large bitmaps or font objects on every move event.
- The live wallpaper service and in-app preview both need gesture parity. When changing pinch behaviour, inspect both touch handlers and the shared renderer.

## Visual references and current target

- The user's first supplied screenshot (12 Nov 2026) is the **shine/material reference**: a highly reflective silver/chrome-looking 10:47 clock over diagonal blue, white, orange and black light streaks. The glass has crisp bright specular highlights, clear luminous bevels, and coloured environmental reflections.
- The user's third supplied screenshot (12 Nov 2026) is the **current lock-screen result**: the clock reads 11:43 over a Spider-Man wallpaper. The current result is a meaningful improvement, but still looks too translucent/frosted and lacks the brighter polished shine in the first reference.
- The second supplied screenshot shows the installed lock-screen/home wallpaper context on the Spider-Man image. Preserve the user's wallpaper and depth/object layering; do not replace or repaint the wallpaper.
- The clock should remain large, centred and readable. Keep the existing overall composition and typography while refining the material and gestures.

## Latest code change

### Commit `844bbb2d6689d3f1e94cd285a078c3819416a51c`

Message: **Polish liquid-glass highlights and add horizontal clock pinch**

Branch: `feature/live-wallpaper-big-clock`

Files changed:
- `app/src/main/java/com/example/ios27clock/LiquidWallpaperRenderer.kt`
- `app/src/main/java/com/example/ios27clock/LiquidWallpaperService.kt`
- `app/src/main/java/com/example/ios27clock/ClockAppearanceActivity.kt`

Changes:
1. **Liquid-glass material**
   - Increased the sharpness/intensity of the white specular highlight at the glass edge.
   - Added a narrow bright reflection ribbon and layered cool-blue and warm reflections across the glass face.
   - Increased the broad/narrow face reflection strength and slightly increased glass-face transmission.
   - Kept the Solid/frosted material response on its existing path/values as much as possible; visual tuning is still pending device comparison.
2. **Independent horizontal pinch**
   - Added a persisted `clock_width_scale` value, separate from `clock_scale`.
   - A horizontally oriented two-finger pinch adjusts width only; the existing vertical/radial pinch continues to adjust height.
   - Width is constrained against the available screen width.
   - The shared renderer performs the scaling using the cached mask/canvas transform during movement, then commits the geometry at gesture end.
   - Added the axis-aware gesture handling to both the installed wallpaper service and `WallpaperClockPreview`, so the in-app live-wallpaper preview follows the same behaviour.
3. **No deliberate layout redesign**
   - The existing clock position, date placement, font styling and wallpaper/depth composition were intended to remain unchanged.

### Build verification for commit `844bbb2`

- **GitHub Actions run:** [Build #264](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43281319087)
- **Commit SHA built:** `844bbb2d6689d3f1e94cd285a078c3819416a51c`
- **Workflow result:** completed successfully.
- **Successful steps included:** Android 17 SDK setup, JDK 17, Gradle setup, official SF Pro font extraction, debug APK build, and artifact upload.
- **Artifact name:** `iOS27-LiquidClock-Android17-debug`
- **Artifact ID:** `13973440777`
- **Artifact created:** 12 Nov 2026; expiry reported as 26 Nov 2026.
- This confirms the CI build completed. It does **not** confirm the appearance or touch behaviour has been tested on the user's phone.

## Current state: what is done vs. what is not

### Implemented in the latest commit
- Stronger liquid-glass specular/edge lighting and layered reflections.
- Horizontal clock width pinch with a separate saved width scale.
- Existing vertical height pinch retained.
- Matching gesture support in both the wallpaper service and in-app wallpaper preview.
- CI build #264 succeeded and uploaded the debug APK.

### Still needs testing or fixing
1. **Visual shine — highest priority**
   - Install/test build #264 and compare the same clock/wallpaper framing against the first screenshot reference.
   - Check that the new highlights look like polished reflective glass, not a white outline, chrome embossing, rainbow fringe, or opaque frosting.
   - Tune AGSL in small controlled changes; retain wallpaper-derived reflections and the depth effect.
2. **Horizontal pinch — not device-verified yet**
   - Test horizontal spreading/pinching changes width independently of height.
   - Test vertical spreading/pinching still changes height independently of width.
   - Confirm the clock remains centred, does not distort or jump, does not exceed the viewport, and the selected foreground object still occludes the clock correctly.
   - Confirm width persists after releasing the gesture, leaving/reopening Clock Studio, and reloading the wallpaper.
   - Test gestures both in the in-app wallpaper preview and on the installed live wallpaper.
3. **Preview/install parity**
   - Confirm the exact same material, clock proportions and depth/object masking in the in-app preview and installed wallpaper. Do not treat these as unrelated visual implementations.
4. **Memory pressure / OOM — known outstanding issue**
   - A supplied `crash-report.txt` from app version `1.0.243-debug` on Android 17 / Samsung SM-S936B records repeated `OutOfMemoryError` during font/weight changes in Clock Studio.
   - One failure occurs in `LiquidWallpaperRenderer.typefaceFor()` while `Typeface.Builder` allocates an approximately 21.4 MB direct buffer; the path goes through `rebuildGlyphMask()` / `reloadAppearance()`.
   - Another occurs during `createGlyphSdf()` / `rebuildGlyphSdf()` while the process has very little free heap.
   - Investigate font allocation/caching and SDF/bitmap lifetime before adding more memory-heavy rendering work. Avoid repeated Typeface construction, avoid duplicate large buffers, release retired masks/SDFs safely, and preserve the last valid preview on allocation failure.
   - The latest commit did not fix these OOM issues.
5. **Regression checks**
   - Ensure horizontal width changes do not alter the existing vertical scale or reset clock position.
   - Ensure date, wallpaper, foreground-depth occlusion, device tilt, and current font/material selections remain intact.
   - Ensure the Solid material mode remains usable and is not unintentionally affected by Glass-only shader tuning.

## How to continue next session

1. Read this file first, then inspect the current branch head and the latest Actions run. Do not assume the saved SHA or build status is still current.
2. Stay on `feature/live-wallpaper-big-clock`; do not switch branches without the user's instruction.
3. Test the successful build #264 on-device before making speculative visual changes, if that APK is still available.
4. Compare against the user's first supplied image for shine and the third supplied image for current lock-screen composition.
5. Make focused changes in the shared `LiquidWallpaperRenderer` and keep the preview/service gesture handlers in sync.
6. After each code commit, update this file with the commit SHA, exact changes, actual CI run/build number and result, tests performed, unresolved bugs and next priorities.

## Status snapshot

- **Last known active branch:** `feature/live-wallpaper-big-clock`
- **Last code commit:** `844bbb2d6689d3f1e94cd285a078c3819416a51c`
- **Last verified CI build:** #264 — success
- **On-device verification of build #264:** not yet reported
- **Top next priorities:** verify shine against the reference; test horizontal/vertical pinch independence and persistence; investigate the known Typeface/SDF memory failures.

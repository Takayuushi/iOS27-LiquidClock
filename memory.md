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

## Earlier code change (before wallpaper-source isolation)

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
- **Last code commit:** `8006b624b4839dc102eea3941f90827ae2ac0bf4`
- **Last verified CI build:** #267 — success
- **Build artifact:** `iOS27-LiquidClock-Android17-debug`, artifact ID `13978905024`
- **Build run:** [GitHub Actions #267](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43292170383)
- **On-device verification of visual refinement build #267:** pending
- **Top next priorities:** compare refined glass edges and Solid sheen on-device; assess the softened numeral 2 base; continue checking other digits; preserve wallpaper preview/applied isolation and horizontal/vertical pinch; investigate known Typeface/SDF memory failures.


## New issue reported and root cause (13 Nov 2026)

### Preview unexpectedly replaced the installed wallpaper

**Confirmed root cause in the previous code:**
- `MainActivity` imported a candidate image directly into `liquid_clock_wallpaper_source.img`.
- `ClockAppearanceActivity.WallpaperClockPreview` read that same file.
- The installed `LiquidWallpaperService` renderer also read that same file.
- `MainActivity` broadcast `ACTION_WALLPAPER_SOURCE_CHANGED` immediately after import; the installed service handled it by calling `renderer.reloadWallpaper()`.
- The foreground-mask editor also broadcast the same action after saving a candidate depth mask.
- Clock Studio additionally wrote preview appearance/geometry into the same `liquid_clock_composition` preferences used by the installed wallpaper, and its `onDestroy()` could broadcast appearance changes without an explicit Apply action.

This explains why simply importing/selecting a new wallpaper for preview could replace the background on the already-installed live wallpaper. The screenshot from 13 Nov 2026 shows the former Spider-Man wallpaper replaced by the blue diagonal-light wallpaper on the One UI home/lock wallpaper surface.

### Fix committed in this iteration

- Introduced `WallpaperStorage.kt` and `ClockWallpaperContent` to separate:
  - candidate/preview image and foreground mask;
  - immutable applied image and foreground mask;
  - preview composition preferences and applied composition preferences.
- The in-app Clock Studio preview uses candidate files and preview preferences.
- The Android system wallpaper preview engine (`Engine.isPreview == true`) also uses candidate files.
- The installed wallpaper engine uses only the applied snapshot. A one-time migration seeds it from the legacy shared source on upgraded installations.
- Importing an image or editing its foreground mask no longer broadcasts a refresh to the installed wallpaper service.
- Preview appearance/size/position changes are staged in preview preferences. Closing Clock Studio no longer broadcasts or commits them.
- Only the explicit **Apply current** action copies the candidate image, foreground mask and composition preferences to the applied snapshot, then notifies the active service and opens Android's native wallpaper preview.
- The renderer remains shared; only its selected source files and preference store differ between preview and applied engine instances.

### Verification state

- Wallpaper-isolation code commit: `10f0b1bbc88b80f47ea0db56011df5386890995d`.
- GitHub Actions build #266 completed successfully: [run #266](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43286817709).
- Device behaviour after the fix was verified by the user: previewing/importing the candidate wallpaper no longer automatically changed the installed lock screen. The explicit Apply flow still needs a full regression test.
- On first upgrade, the app can only migrate the currently stored legacy source into the applied snapshot. If the previous shared-source bug already replaced Spider-Man, the original image cannot be reconstructed from the overwritten app-private file; re-import Spider-Man as the candidate and use Apply current once to restore it.

### Next regression checks

1. With the app live wallpaper already active, import a different image and open/close Clock Studio. The installed wallpaper must remain unchanged.
2. Edit/save a preview foreground mask. The active wallpaper must remain unchanged.
3. Change font, weight, colour, material, clock position or pinch dimensions in Clock Studio, then exit without Apply. The installed wallpaper must retain its previous composition.
4. Tap Apply current. Confirm the system preview shows the candidate; set it and verify the applied wallpaper and foreground occlusion update.
5. Reopen Clock Studio. It should initialize from the last applied composition while still showing the currently selected candidate wallpaper.


## Visual refinement pass: contour polish and numeral 2 (13 Nov 2026)

### User feedback from the latest side-by-side screenshots
- The wallpaper source isolation is working on-device: selecting/previewing the new image no longer replaced the installed wallpaper.
- The clock is close to the target, but the glossy glass still shows rough/chromatic-looking edge fragments and needs more refinement.
- The frosted/Solid treatment needs a little more visible shine.
- The lower terminal/base of the numeral **2** looks too sharp/blocky and needs a small optical correction.
- Keep the current clock layout, overall proportions, date and wallpaper/depth composition unchanged. Other digits are acceptable for now; user will continue checking them.

### Implemented in this refinement commit
- Reduced the Glass mode's red/blue chromatic dispersion substantially, especially at the glyph boundary, to stop the edge from reading as cracked/rainbow-fringed.
- Reduced the refraction displacement slightly so the contour remains a single coherent lens instead of splitting the background into harsh edge fragments.
- Increased the SDF normal sampling baseline from 1.9px to 2.55px to smooth small distance-field stair-steps before they feed lighting/refraction.
- Broadened the sharp studio highlight ribbon and softened the high-energy caustic profile, while retaining brighter broad/narrow reflections for a polished-glass appearance.
- Increased Solid/Frosted mode specular response and added a broad, low-intensity satin sheen so the frosted glass catches more light without borrowing the sharp Glass-only reflection bands.
- Isolated the SF Pro numeral `2` outline during mask generation and applied a very small `CornerPathEffect` fillet only to that glyph. Other digits and their flatter terminals remain unchanged. The adjusted `2` is included in the combined glyph/SDF bounds.

### Build and device verification
- Previous wallpaper-isolation commit: `10f0b1bbc88b80f47ea0db56011df5386890995d`; GitHub Actions build #266 completed successfully.
- **Visual refinement commit:** `8006b624b4839dc102eea3941f90827ae2ac0bf4` ([commit](https://github.com/Takayuushi/iOS27-LiquidClock/commit/8006b624b4839dc102eea3941f90827ae2ac0bf4)).
- **GitHub Actions build #267:** completed successfully; debug APK build and artifact upload both passed ([run #267](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43292170383)).
- **Artifact:** `iOS27-LiquidClock-Android17-debug`, ID `13978905024`; expires 26 Nov 2026.
- The user has not yet tested visual refinement build #267 on-device. Compare both Glass and Solid screenshots after installation.

### Outstanding visual work
1. Check whether the glass edge is now smooth and continuous on both dark and high-contrast wallpapers; avoid restoring strong rainbow dispersion.
2. Check the 2's lower foot. If it is still too square, adjust only that glyph's small terminal treatment rather than changing the full font.
3. Confirm frosted/Solid mode has a gentle satin shine without becoming glossy chrome or losing its translucent frosted look.
4. Continue inspecting other digits during real use and make only glyph-specific changes when an actual defect is visible.
5. Preserve wallpaper preview/applied separation and horizontal pinch behaviour.


## Preview tap-to-dismiss controller behaviour (13 Nov 2026)

### User request
- The controller/appearance panel was only sliding down when a touch landed on the clock glyph itself.
- Change it so **any touch in the exposed wallpaper preview area** slides the controller panel down.
- The clock's position must not matter: even if it is dragged below/behind the controller panel, the user can tap another visible part of the wallpaper preview to dismiss the panel.
- Preserve clock drag and the existing horizontal/vertical two-finger pinch interactions when the touch begins on the clock.
- The floating arrow remains the way to expand the controller panel again.

### Implemented
- Renamed the WallpaperClockPreview interaction callback from clock-specific onClockInteraction to preview-wide onPreviewTouch.
- Every ACTION_DOWN reaching the preview now invokes the panel-collapse callback and is captured by the preview.
- Renderer hit-testing is retained only to decide whether a gesture should also initiate clock dragging. A touch outside the glyph dismisses the controls without moving the clock.
- Existing pointer/pinch paths are unchanged; touches on the actual clock continue to drag or resize it.
- A visible control-panel overlay still receives its own child-control touches; this change applies to the exposed wallpaper preview area.

### Verification
- Commit: `942b3e1eb16ead07f5efd0a7c0aa7c46abea3001`.
- GitHub Actions build #269 completed successfully: [run #269](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43298209777).
- Artifact: `iOS27-LiquidClock-Android17-debug`, ID `13980262070`; expires 26 Nov 2026.
- The user has not yet reported testing the tap-anywhere controller behaviour on-device.
- Test: tap empty preview space; the panel should slide down. Tap the clock; the panel should slide down and dragging should still move it. With the clock placed underneath the panel, tap a different exposed preview area to dismiss the panel. Tap the floating arrow to show the panel again.


## Inner-contour cleanup and brighter glass pass (13 Nov 2026)

### Latest on-device feedback
- The user reports remaining roughness/light leakage inside the lower clock digits, particularly the inside of `5`, the counter/inner wall of `0`, and the narrow `1`.
- The material should be shinier overall, but the highlights must remain coherent. Do not increase rainbow/chromatic splitting to create shine.
- The new screenshot is from the blue diagonal-light wallpaper. Preserve the overall layout, current glyph proportions, wallpaper and depth composition.

### Root cause investigated
- The SDF uses a two-pass 3:4 chamfer metric. Its diagonal distance ratio (4/3) is noticeably lower than the Euclidean diagonal ratio (sqrt(2)); this introduces directional error into the distance gradient used for refraction and lighting. The error can be most visible around inner counters and narrow curved strokes.
- The shader also still had multi-pixel red/blue separation and strong contour displacement, which can turn high-contrast wallpaper lines into apparent cracks and light leaks inside the glyphs.

### Implemented in current pass
- Upgrade SDF chamfer distances from 3:4 to 5:7 cardinal/diagonal costs; update encoding, decoding and maximum distance units consistently.
- Increase finite-difference normal sampling baseline to 3.15 output pixels and fade unstable low-gradient normals only for refraction, preserving unit surface normals for lighting.
- Reduce glass displacement and channel dispersion; blend the remaining refracted channel split back toward the original colour image so the lens stays coherent.
- Broaden and strengthen the main specular highlight and face reflections to add shine without relying on edge colour fringing.
- Increase Glass body opacity slightly for more readable reflective material while keeping wallpaper visible through the face.

### Build and device verification
- Previous controller tap-to-dismiss build: #269 passed; user hasn't yet given a device test result for that touch interaction.
- Previous inner-contour pass: commit `71051865c8bf11dc26ebf2037137212b29deba68`; build #270 passed and uploaded artifact `iOS27-LiquidClock-Android17-debug` (ID `13981836170`): [run #270](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43300061790).
- The user tested build #270 and reported that the contour artifacts were reduced but still visible. The user also pointed out that the displayed `1` silhouette does not match the reference.
- Do not report the visual fix as verified until the new APK is installed and checked on-device.
- Priority test digits: `0` (inner and outer edges), `5` (inner curve), `1` (narrow stem/flag), then other digits as encountered.
- Confirm shine is stronger but the inner counters remain clean, with no obvious channel-split shards, bright seams, or halo leakage.
- Existing wallpaper-source isolation, tap-anywhere-to-collapse controls, clock drag, and horizontal/vertical pinch must remain intact.


## Refine inner contours and correct the number 1 silhouette (13 Nov 2026)

### User's new screenshot feedback
- Build #270 reduced but did not eliminate visible cracks/light leakage inside the digits.
- The `1` glyph itself was overlooked: its silhouette differs from the iOS reference. In particular, its top flag and base need to match the reference lock-screen numeral rather than simply using the condensed SF Pro text `1`.
- Keep the clock's current scale, positioning, time/date hierarchy, wallpaper, and depth composition unchanged.

### Root implementation detail discovered
- The renderer's `buildVectorDigitPath` and adaptive numeral helpers are currently unused. The active glyph path produces each digit through `glyphPaint.getTextPath(...)` from the condensed SF Pro font.
- The visible `1` therefore did not use the old custom numeral path. It now needs to be routed through a dedicated closed silhouette in the active glyph composition path.

### Implemented in current pass
- Added a dedicated filled `1` silhouette fitted to the existing measured glyph bounds: short angled top flag, straight stem, and small flat baseline foot. Tracking, digit advance and overall clock layout remain unchanged.
- Added sub-pixel edge-coverage correction when encoding the SDF. The distance field retains partial alpha coverage from antialiased mask pixels instead of snapping all edge pixels to fully inside/outside.
- Reduced Glass background displacement and red/blue channel splitting to prevent high-contrast wallpaper lines from appearing as cracks or light leaks inside the digits.
- Increased broad and narrow white face reflections slightly, so additional shine comes from a coherent reflective surface instead of stronger chromatic refraction.
- Kept the 5:7 chamfer metric introduced in build #270.

### Verification state
- Code commit and CI build for this pass: pending.
- Device comparison pending. Inspect the `1` silhouette against the first reference screenshot, and check inner edges on `0`, `5`, and the narrow strokes.
- Confirm inner wallpaper lines bend smoothly and do not split into bright shards; the glass should remain visibly reflective.
- Preserve preview/applied wallpaper isolation, tap-anywhere controller dismissal, clock dragging, horizontal and vertical pinch, and Solid/Frosted mode.

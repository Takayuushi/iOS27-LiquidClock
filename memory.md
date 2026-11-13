# iOS 27 Liquid Clock — Project Memory

> Living handover notes for future development sessions. Documentation only: this file is not read by the Android app, Gradle, or the rendering code.

## NON-NEGOTIABLE HANDOVER RULE — Apple font and renderer parity

This section is deliberately repeated near the top of memory.md so it is not missed when a new chat starts or the previous chat reaches its length limit.

### Official Apple SF Pro only
- The production app and the separate Digit Lab APK must use **Apple's official SF Pro variable font and Display faces**, extracted from Apple's official `SF-Pro.dmg`.
- Official source URL: `https://devimages-cdn.apple.com/design/resources/download/SF-Pro.dmg`.
- **Never use the old GitHub mirror** (`raw.githubusercontent.com/sahibjotsaggu/San-Francisco-Pro-Fonts`) as the font source. That pre-switch mirror did not provide the variable-font behaviour we need, especially the real weight/width variation axes.
- Preserve and validate the actual Apple variable-font axes: `wght` (weight) and `wdth` (width). Do not replace them with static faces, synthetic bolding, a system fallback, a community font, or a font subset that has lost either axis.
- Production switched in commit `19f727982706117a6c62622e948da67f8296b0ff` (“Use Apple's official SF Pro variable font and display faces”), 12 Nov 2026 at 12:18 UTC. Build #190 was the first production CI run to install the official Apple package (font step passed; later unrelated Kotlin compile error); build #192 was the first successful APK using it.
- Digit Lab must also download the same official Apple DMG. Its digit-only subset is acceptable only when it retains and validates both `wght` and `wdth` axes. GitHub is the CI/source-code host, not the font provider.

### Production / Digit Lab relationship — test bench only
- **The production iOS 27 Liquid Clock app is the only product being developed and maintained.** Digit Lab is a separate disposable APK used to inspect digits and material effects without waiting for the real clock to tick.
- Do not treat Digit Lab as a second product or create a parallel feature roadmap.
- **Renderer architecture was unified in commit `466b7a287e514a62ad550b7e91e2b77c969fae71`.** Production includes `:clock-renderer`; `digit-lab/settings.gradle.kts` includes the exact same module from `../clock-renderer`. Both APKs compile the same `LiquidWallpaperRenderer.kt` and AGSL source. Do not describe Digit Lab as using a copied/stale renderer or attempt manual file sync while this shared-module arrangement remains in place.
- Digit Lab's test-only text source is injected by `DigitLabActivity` through `RendererClockTextSource`; do not add fixed-time test hooks or controls to production.
- Keep Digit Lab's separate package ID, isolated files/preferences and no-`WallpaperManager` behaviour so it cannot alter the real app or installed wallpaper.

### Material scope guard — do not regress Solid
- The app's production **Solid** material is already approved by the user and must be treated as finished/protected. Digit Lab labels the corresponding option **Frosted Glass**; these are the same material path under different UI labels.
- The only material currently being developed is production **Glass** / Digit Lab **Liquid Glass**. Do not tune, redesign, retune, or otherwise change Solid/Frosted Glass while working on Liquid Glass.
- Keep every new Liquid Glass-only shader contribution behind `(1.0 - solidMode)` or in a separately computed Glass path. Preserve the Solid path's wallpaper sampling, positive SDF-normal displacement direction, `1.5 + solidEdge * 6.0 + solidBevel * 1.1` displacement, `0.68) tilt contribution, blur, face, opacity (`0.78`), edge lighting and sheen.
- Renderer commit `da687bb` accidentally put the new negative refraction direction into the shared warp expression. Commit `d2dec8b2044fc6e04b2ad79b6624af2f15d5f226` corrects this by splitting `glassWarp` and `solidWarp`; at `solidMode == 1`, the Solid warp again matches the previous implementation exactly. Never collapse these paths into one direction-sensitive expression again.
- Before each future shader commit, explicitly review all output-affecting expressions at `solidMode == 1` and compare them with the last user-approved Solid implementation. Do not infer safety merely because a variable is named `glass*`.
- Typography/layout is shared across materials and should be fixed globally when requested. The tighter digit-pair tracking committed in `13121da` applies identically to Glass and Solid (and both Digit Lab materials), while leaving tracking adjacent to the colon unchanged. This is not a Solid material/shader change.

### Latest screenshot assessment (13 Nov 2026, ~14:00 IST)
- User tested build #293 / Digit Lab #15 from commit `d2dec8b`. Refraction is visible, but the clock still reads as a flat distortion outline rather than a raised 3D glass object. On the diagonal-streak wallpaper, the refraction creates conspicuous wavy bands, especially across the `4`.
- The cause is not insufficient edge brightness. In `d2dec8b`, both `glassAreaReflection` and tight glints were gated by `glassProfileCoverage`, which fades to zero across the centre of a stroke. The face therefore received almost no reflection and only the contour remained visible.
- New follow-up commit `869692f763ddccef54944f52638b8ab0febf5e40` widens the rounded cap radius from 13 to 20 px; derives refraction displacement from that same cap's slope (capped at 22 px) rather than the mismatched 38 px distance curve; adds a restrained wallpaper-coloured environment reflection across the face with stronger Fresnel toward the shoulder; restores a weak broad specular across the front cap; and increases the Glass-only lift shadow.
- The Solid/Frosted path is protected: its positive SDF-normal warp, displacement, tilt, blur, face, opacity, sheen and shadow values are selected unchanged when `solidMode == 1`. All new face/reflection/refraction contributions are Glass-only.
- Build #293 and Digit Lab #15 (commit `d2dec8b`) both succeeded. New workflows #294 and #16 are now running from `869692f`; the visual result has not yet been tested.
### Latest visual assessment and height-map implementation (13 Nov 2026)
- User tested production #294 / Digit Lab #16 from commit `869692f`. The output still looked like a bright outer rim surrounding the old flat/transparent interior. User explicitly rejected it as an acceptable Liquid Glass result.
- User supplied a Gemini rendering pipeline: render glyph mask; generate a smooth height field; calculate 3D normals from its gradient; refract and disperse RGB background samples; add directional specular rim lighting.
- Current implementation commit `e195e17d9c3e52f7d074153675bffe9abe0aacda` follows that pipeline in the shared renderer:
  - Builds a convex dome height field analytically from SDF distance: `h(d)=sqrt(2*R*d-d*d)`, with a 20px profile radius.
  - Samples the height field at neighbouring SDF texels and derives the 3D surface normal from its finite-difference gradient.
  - Uses Snell refraction for air/glass IOR 1.50 and a 30px optical thickness.
  - Adds restrained chromatic dispersion by sampling red and blue at opposite ~1.8px ray offsets while green remains on the central refracted ray.
  - Computes local environment reflection by reflecting the view vector about the same 3D normal, then applies Schlick Fresnel.
  - Uses the height-map normal for directional diffuse and specular lighting; removes the arbitrary screen-space reflection strips from Liquid Glass.
- The approved Solid/Frosted renderer remains protected. `solidWarp` preserves its original positive SDF-normal displacement, displacement expression and 0.68 tilt; Solid still uses its prior blurred wallpaper, face, 0.78 opacity, solid bevel/specular/sheen, and unmodified non-Glass shadow. All new height-map, Snell, dispersion and reflection contributions are selected only by Liquid Glass.
- New build workflows #295 (Production) and #17 (Digit Lab) were triggered from `e195e17`; their completion and the on-device appearance are pending. This is the first implementation that follows the complete height-map/refraction/dispersion sequence; do not claim success before compilation and the user's screenshot comparison.
### Digit spacing correction (13 Nov 2026)
- User noted that the screenshot's digit gaps were wider than the iOS reference, particularly `1–0` and `4–7`.
- This is a shared typography/layout issue, not a material issue. It must apply to both production materials (**Glass and Solid**) and both corresponding Digit Lab options (**Liquid Glass and Frosted Glass**), because all use the shared `:clock-renderer` glyph layout.
- Commit `13121dac4a34c55f7524eeba57af8ab7c81f8f20` (“Tighten digit-pair spacing across Glass and Solid”) reduces digit-to-digit tracking from `TRACKING_EM * 0.55` to `TRACKING_EM * 0.20` (about 64% less pair tracking). Spacing either side of the colon remains at the previous `TRACKING_EM * 0.55`; colon advance itself is unchanged. Font files and Apple's `wdth` axis are not modified.
- The spacing adjustment only changes glyph advances/composition; it does not touch the AGSL material branches, so it does not alter the approved Solid material treatment.
- Production build #296 and Digit Lab build #18 were triggered from `13121da`; on-device spacing comparison is pending.
### Current work to resume after a new chat
- **Primary open issue: Liquid Glass material quality.** The user says recent Digit Lab output looks like thin transparent outlines rather than thick, polished, refractive glass. The target is the reflective silver/chrome-like reference with visible glass volume, rounded shoulders, strong directional specular reflections and wallpaper-derived colour/refraction—not an all-around white bevel or an opaque frosted face.
- The screenshot pair supplied on 13 Nov 2026 is timestamped approximately 13:00–13:01 IST (07:30–07:31 UTC). The latest shader visible at that time was commit `e1be6953d1442948fc959619821706d114173b70`. The later change `54679f0154a1a70d8d52796153a07f13a279f0b4` landed at 13:08 IST and its APKs were built after those screenshots; the screenshots therefore do not verify that later shader.
- Commit `54679f0` changes the Liquid Glass stroke profile to a rounded SDF-derived glass-rod cross-section and introduces a broad face reflection plus tighter glints. It deliberately removes the previous independent outline/caustic strokes to avoid embossed text. This is a new visual pass, **not yet confirmed as a fix on the user's device**.
- Do not reopen old reports about individual glyphs, pinch behaviour, or wallpaper isolation unless the user reports a regression. Preserve the existing fixes and verify renderer parity/gesture behaviour when testing the latest APKs.
- User installed and tested production #290 / Digit Lab #12 after commit `54679f0` on 13 Nov 2026 around 13:33–13:34 IST. The new screenshot still showed a faint, outline-dominant glass effect with no meaningful improvement. Do not claim the rounded-profile pass solved the material issue.
- Root cause identified in the AGSL: Liquid Glass used `glassOpacity = mix(0.35, 0.46, glassLens)` while sampling the same wallpaper already drawn behind the glyph. Premultiplied alpha therefore composited nearly identical background pixels over one another, cancelling most face/refraction changes and leaving edge highlights dominant.
- Follow-up renderer commit `a4db737bbf63359258cb9abacdb003fd31bff54f` raises Liquid Glass body opacity to 0.88–0.95, adds an opposite-side wallpaper environment sample for reflection, restores face-wide softbox and narrow reflection ribbons, and strengthens the broad area reflection. Frosted Glass remains on its separate opacity/sheens path. CI/build and device verification are pending at this point.
- Official Apple SF Pro variable font and Display faces, extracted from Apple's official `SF-Pro.dmg`, remain mandatory. Preserve both real `wght` and `wdth` axes.

## Project identity and working rules

- **Repository:** `Takayuushi/iOS27-LiquidClock`
- **Active development branch:** `feature/live-wallpaper-big-clock`
- **Do not switch to or commit to `main` unless explicitly asked.** The active feature branch contains the current advanced live-wallpaper work; `main` is substantially behind it.
- **Platform:** Android 17 / API 37, Kotlin, JDK 17, package `com.example.ios27clock`.
- **CRITICAL FONT SOURCE:** the production app uses **official Apple SF Pro**, downloaded during CI from Apple's `https://devimages-cdn.apple.com/design/resources/download/SF-Pro.dmg`. Do not describe it as a GitHub font or use the old GitHub mirror. See the dated source-switch/build-log record below. The separate Digit Lab also downloads the official Apple DMG and subsets that same official variable font to digits/colon.
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
- **Successful steps included:** Android 17 SDK setup, JDK 17, Gradle setup, extraction of official Apple SF Pro assets from Apple's DMG, debug APK build, and artifact upload.
- **Artifact name:** `iOS27-LiquidClock-Android17-debug`
- **Artifact ID:** `13973440777`
- **Artifact created:** 12 Nov 2026; expiry reported as 26 Nov 2026.
- This confirms the CI build completed. It does **not** confirm the appearance or touch behaviour has been tested on the user's phone.

## Current state: what is done vs. what is not

### Implemented in the current source
- Production and Digit Lab now share the same `:clock-renderer` Gradle library; both build the same LiquidWallpaperRenderer and AGSL shader.
- Independent horizontal width pinch and vertical height pinch are implemented in the wallpaper service and in-app preview.
- Candidate/preview wallpaper and composition data are isolated from the applied live wallpaper; only the explicit Apply action commits them.
- The renderer uses official Apple SF Pro variable font assets, with real `wght` and `wdth` axes retained and validated by CI.
- The 13 Nov shader iterations introduced SDF-derived 3D glyph normals and then a rounded glass-rod surface profile.

### Current visual issue
- The latest user screenshots show Liquid Glass looking too transparent and outline-dominant, especially on low-detail or dark wallpaper regions. The face needs more visible optical volume and environmental reflection without becoming opaque/frosted or returning to a crude all-around white bevel.
- Screenshots were captured before commit `54679f0154a1a70d8d52796153a07f13a279f0b4`; install and visually evaluate the newer builds before judging that pass.
- Keep Liquid Glass and Frosted Glass separate. Do not weaken Frosted/Solid while tuning Liquid Glass.

### Existing regression priorities (verify, do not assume broken)
- Horizontal pinch must change width independently of height; vertical pinch must retain its previous behaviour. Check centring, persistence and bounds in both preview and installed wallpaper.
- Preserve foreground-object occlusion, date, wallpaper source isolation, device tilt, selected font/material settings and explicit Apply workflow.
- The historical Typeface/SDF OutOfMemory reports remain worth monitoring, but do not add memory-heavy rendering work without profiling and a controlled test.
- Do not declare a visual fix from a successful CI build alone. Compilation/artifact upload and on-device appearance are separate verification steps.

## How to continue next session

1. Read this file, inspect the current head of `feature/live-wallpaper-big-clock`, and check the latest Actions runs before making changes.
2. Do not switch to `main` or another branch unless the user explicitly asks.
3. The current code SHA is `54679f0154a1a70d8d52796153a07f13a279f0b4`. Production build #290 and Digit Lab build #12 both succeeded from that exact SHA.
4. First obtain on-device feedback for that post-screenshot rounded-profile shader pass. Keep the same wallpaper, sample, material, width, weight and approximate clock scale when comparing production and Digit Lab.
5. If the latest pass still looks like an outline, redesign the face/environment reflection contribution in the shared `clock-renderer` AGSL rather than merely increasing edge brightness. Keep wallpaper refraction, bevel normals and alpha/body response coherent.
6. After each source-code commit, update this file with the exact change, SHA, actual CI run/result, on-device verification and remaining issues. A documentation-only update does not establish a visual fix.

## Status snapshot

- **Active branch:** `feature/live-wallpaper-big-clock`
- **Latest renderer source commit:** `13121dac4a34c55f7524eeba57af8ab7c81f8f20` — “Tighten digit-pair spacing across Glass and Solid”
- **Latest Glass shader implementation:** `e195e17d9c3e52f7d074153675bffe9abe0aacda` — SDF height map, 3D normals, Snell refraction, RGB dispersion and normal-driven Fresnel. This has not yet received user on-device acceptance.
- **Latest spacing change:** shared digit-to-digit tracking reduced to 20% of TRACKING_EM; colon-adjacent spacing and colon advance remain unchanged. This intentionally affects both Glass and Solid because it is typography/layout, not material rendering.
- **Material guard:** production Solid / Digit Lab Frosted Glass remains approved and its renderer path is protected. No shader or material code changed in commit `13121da`.
- **Latest completed production build:** #295 — success from `e195e17`: [Actions #295](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43399830056)
- **Latest completed Digit Lab build:** #17 — success from `e195e17`: [Actions #17](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43399830010)
- **Current production build:** #296, run ID `43403237661`, from `13121da`; pending at time of writing: [Actions #296](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43403237661)
- **Current Digit Lab build:** #18, run ID `43403237776`, from `13121da`; pending at time of writing: [Actions #18](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43403237776)
- **On-device status:** Glass shader from `e195e17` and digit spacing from `13121da` are not yet verified on device.
- **Primary next priority:** test build #296/#18. Check tighter `10` and `47` pairs while confirming colon gaps are unchanged; test both materials and verify Solid's material appearance is identical. Continue the Glass visual target separately.
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
- Commit `0b9ff7b080a1f05a8d75aca9f3abb9747bf7ecd5`; GitHub Actions build #271 completed successfully and uploaded artifact `iOS27-LiquidClock-Android17-debug` (ID `13982136419`): [run #271](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43301561208).
- The user tested the build and reported that the inner curves still show bright fractured wallpaper streaks and the `1` remains too narrow compared with the reference.
- The next correction pass is now focused on widening the `1` using its advance cell and smoothing the glass face with local diffusion instead of chromatic splitting.
- Device comparison for the next correction is pending. Inspect the `1` silhouette against the original reference screenshot, and check inner edges on `0`, `9`, and `2`.
- Confirm inner wallpaper lines bend smoothly and do not split into bright shards; the glass should remain visibly reflective.
- Preserve preview/applied wallpaper isolation, tap-anywhere controller dismissal, clock dragging, horizontal and vertical pinch, and Solid/Frosted mode.


## Widen reference numeral 1 and smooth glass interior streaks (13 Nov 2026)

### User feedback after build #271
- The lower `0` still looks unpolished, with bright broken-looking streaks; similar cracks/bleeding remain along the curves of `9` and `2`.
- The new `1` still does not match the reference; the rendered ones remain much too narrow.
- Screenshots compared: `2:09` and `2:11` over the diagonal blue/black/orange wallpaper.

### Root cause
- The dedicated `1` path was still fitted into the original SF Pro outline's narrow `pathBounds`. Although its internal shape was custom, it inherited the narrow width of the font's original `1`, so its flag and foot could not reach the reference proportions.
- The apparent fractures inside `0`, `9`, and `2` are strongly correlated with high-contrast wallpaper streaks crossing the transparent glass. The current refracted sample retains too much sharp local contrast, while even a small red/blue split can make those lines appear doubled.

### Implemented in this pass
- Refit the custom `1` to a wider rectangle based on the glyph's measured advance cell, capped below the advance so it does not collide with adjacent digits. The clock's tracking and overall layout remain unchanged.
- Widened the stem and extended the baseline foot of the custom `1` to better match the reference's display numeral.
- Increased the SDF finite-difference sample step used for refraction normals to 4px to smooth direction changes around curved inner/outer edges. The surface-lighting normal itself remains unchanged.
- Replaced the remaining red/blue chromatic samples with four short-range (8px) wallpaper samples blended at 24% into the Glass face. This provides mild local optical diffusion without turning the Glass mode into the broad Frosted mode.
- Increased the accent-derived glass body contribution and body opacity slightly to stop high-contrast wallpaper lines from cutting too harshly through the glass.
- Kept the broad face reflection bands, layout, digit positions, wallpaper isolation, controller gesture handling, and horizontal/vertical pinch unchanged.

### Verification
- Commit `4cec7c4f3b8e4e49d00b3808eda0ff04c288bfb9`; GitHub Actions build #272 completed successfully and uploaded artifact `iOS27-LiquidClock-Android17-debug` (ID `13983279106`): [run #272](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43303351545).
- The user tested build #272 and reported the missing hour digit in a `2:20` clock, plus continuing white bands inside `0` and `2`, wrinkled curved surfaces, and an imperfect `7`.
- The next correction stores every special `2` glyph instead of allowing repeated occurrences to overwrite one another.
- Watch for excessive blur, opaque/frosted appearance, muddy glass colour, glyph collisions, or loss of reflective shine. If any occur, adjust diffusion mix/radius first rather than increasing edge dispersion.


## Fix repeated numeral 2 being dropped from the clock mask (13 Nov 2026)

### User-reported regression in build #272
- In the 2:20 screenshot the hour `2` disappeared, leaving the display looking like `:20`.
- The upper `7` shape also needs a separate reference comparison.
- The thick white band through the `0` and the wrinkled surface along the `2` curve remain. These are not considered fixed by build #272.

### Exact cause
- The glyph composition previously held a single nullable `digitTwoPath`.
- While iterating through the time string, each encountered `2` replaced the same variable. In a time such as `2:20`, the second `2` overwrote the first.
- The final mask drew only the last stored `2`, so the hour digit vanished. This is deterministic and independent of time formatting or the Android wallpaper service.

### Implemented correction
- Replaced the single `digitTwoPath` with a list of special `2` paths.
- Every occurrence is now transformed with the shared natural and vertical clock matrices, included in final bounds, and drawn into the mask with the same subtle `CornerPathEffect`.
- Repeated `2` glyphs can no longer replace one another in the final mask.
- No clock layout, string formatting, tracking, or time source changes were made.

### Verification
- Commit `512180ae17d990f68f711f93caa2194b06948998`.
- GitHub Actions build #273 completed successfully and uploaded `iOS27-LiquidClock-Android17-debug`, artifact ID `13984475435`: [run #273](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43305462651).
- The build confirms compilation and artifact upload only; on-device verification of repeated digit times is still pending.
- Test explicit times containing multiple twos, especially `2:20`, `12:22`, and `22:22`; confirm every digit is visible, correctly positioned, and not clipped.
- Recheck the `7` silhouette independently against the reference.
- Continue to inspect the white bands through `0` and `2` and the wrinkled curved surface. Those remain open visual defects and require a separate controlled shader investigation.


## Smooth white streak bands and curved-surface wrinkles (13 Nov 2026)

### User feedback after build #272
- The hour `2` was missing from `2:20` due to the single-path overwrite. This is fixed in code commit `512180ae17d990f68f711f93caa2194b06948998`; build #273 passed and uploaded artifact `13984475435`.
- The user also reports the thick white band remains inside `0` and `2`, and a wrinkled/striped texture remains on the curved surface of `2`.
- The `7` silhouette is still flagged for review against the iOS reference.

### Shader pass implemented after the repeated-2 fix
- Removed the separate four-tap 8px Glass blur, which was small and directionally biased.
- Reused the renderer's existing eight-direction sample set for both material modes: Glass uses an 18px sample radius; Solid/Frosted retains its original 38px radius.
- Blended 34% of the local eight-direction sample into the Glass face to soften bright diagonal wallpaper streaks without replacing the transparent glass body.
- Increased the SDF normal sampling baseline to 5.25px to smooth curvature-driven normal changes and reduce the wrinkled look.
- Broadened the narrow/bright reflection ribbons and reduced their peak intensities (0.50 to 0.40 and 0.80 to 0.60), leaving the broad reflection and contour specular in place. The goal is to remove flat white bands while keeping polished edge shine.

### Verification
- Shader pass commit: `1d3aba3030cfa74a16cc5025376bac17392ce230`.
- GitHub Actions build #275 completed successfully; APK compilation and artifact upload passed: [run #275](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43306023366).
- Artifact: `iOS27-LiquidClock-Android17-debug`, ID `13983737589`; expires 26 Nov 2026.
- On-device compare both wallpaper types: high-contrast diagonal light wallpaper and Spider-Man. Check that the 0/2 white bands are less harsh, the 2's curve has a smoother continuous reflection, and overall Glass remains reflective rather than cloudy.
- Verify the repeated-2 fix with `2:20`, `12:22` and `22:22`.
- Review the `7` silhouette as a separate glyph issue; do not change it blindly without matching the reference.


## Standalone Digit Lab APK requested (13 Nov 2026)

### Scope and architecture
- Digit Lab is a separate APK, not a production-app page, option or test mode.
- Separate application ID: `com.example.ios27clock.digitlab`; it can be installed alongside production.
- It has its own app files/preferences, no wallpaper service and no calls to `WallpaperManager`.
- **Current renderer implementation is shared, not copied.** The standalone `digit-lab/settings.gradle.kts` includes `:clock-renderer` from `../clock-renderer`, so production and Digit Lab compile the same renderer source and AGSL.
- `DigitLabActivity` injects a test-only clock text source so arbitrary digit strings can be rendered; production has no Digit Lab controls or fixed-time testing code.
- Features include image selection, custom digit/colon input, material selection, width/weight adjustment, dragging and independent horizontal/vertical pinch.
- Its workflow `.github/workflows/digit-lab.yml` obtains the official Apple SF-Pro.dmg directly from Apple's CDN and subsets the official variable font to digits/colon while preserving real `wght` and `wdth` axes.

### Build history and current artifacts
- Initial standalone project and its first compile correction are historical; the current workflow is built from the shared renderer module.
- Digit Lab run #12 completed successfully from commit `54679f0154a1a70d8d52796153a07f13a279f0b4`: [GitHub Actions run #12](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43387165956).
- Artifact: `liquid-clock-digit-lab-debug`, ID `14019535593`, created 13 Nov 2026 07:39:45 UTC; expires 13 Dec 2026 07:39:44 UTC.
- CI success verifies build and artifact upload only. The visual appearance of this latest rounded-profile shader has not yet been confirmed on the user's device.
- The user installed and tested the #291 / Digit Lab #13 build from commit `a4db737bbf63359258cb9abacdb003fd31bff54f` (13 Nov 2026, approximately 13:46 IST). The result was judged frosted/opaque white typography, not Liquid Glass. This pass is a visual failure despite both APK builds succeeding.
- Root cause in that pass: it combined near-opaque face output with an environment-reflection blend across the face and broad softbox/ribbon highlights. The wallpaper refraction remained too weak to compensate, so the face looked painted/frosted.
- Follow-up commit `da687bb4e8a1196c9f3cc8d0a409e565078ab8b1` changes only the intended Liquid Glass material model: edge-driven SDF refraction using a curved lens-depth profile and up to 38 px displacement; removes Glass face blur and broad face-wide white/environment fill; keeps face RGB as refracted wallpaper; moves environment reflection/specular contribution to the curved SDF shoulder. The positive/negative warp sign in that commit was inadvertently shared with Solid and is corrected below.
- User clarified on 13 Nov 2026 that production Solid / Digit Lab Frosted Glass is already perfect and must not be changed. Only Glass / Liquid Glass is in scope.
- Commit `d2dec8b2044fc6e04b2ad79b6624af2f15d5f226` splits the warp into separate `glassWarp` and `solidWarp` paths. At `solidMode == 1`, it restores the previous Solid normal direction, displacement and tilt exactly; Liquid Glass alone uses the new negative-direction SDF lens warp. The remaining Solid output path stays selected by `solidMode` and retains its prior blur, face, opacity and sheen values.
- Build and on-device verification for `d2dec8b` are pending. Do not call it a successful visual fix until the user tests Liquid Glass and confirms Solid/Frosted remains unchanged.
## CRITICAL — Official Apple SF Pro source and exact switch build (verified from Actions logs)

This is a must-preserve project fact. Do not call the font a GitHub font, a community SF font, or a generic SF Pro download.

### Exact production switch
- **Source-switch commit:** `19f727982706117a6c62622e948da67f8296b0ff` — **“Use Apple's official SF Pro variable font and display faces”**.
- **Commit timestamp:** 12 Nov 2026, 12:18:15 UTC.
- **First production CI run using the official Apple package:** [Build #190](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43216671589), commit `19f7279`.
- Build #190's log explicitly downloads `https://devimages-cdn.apple.com/design/resources/download/SF-Pro.dmg`, reports the DMG as **218,830,848 bytes**, extracts the Apple package, verifies the variable font, and reports successful installation of official Apple SF Pro assets into build assets. The font-install workflow step passed. The APK compile later failed for an unrelated Kotlin error: `ClockAppearanceActivity.kt:109:13 Unresolved reference 'shadowLayer'`. Do not mistake that compile failure for a font extraction failure.
- **First successful production APK after the switch:** [Build #192](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43216821202), commit `8b6725015fec28fef0d40acde0f47edd4d2d0bd4`. Its log repeats the official Apple DMG download/extraction, validates the real axes (`wdth: 30–150`, default 100; `wght: 1–1000`, default 400), then reports `BUILD SUCCESSFUL` and uploads artifact `iOS27-LiquidClock-Android17-debug`, ID `13943323945`.
- Source switch diff: `19f7279` removes the pinned `raw.githubusercontent.com/sahibjotsaggu/San-Francisco-Pro-Fonts` URLs and replaces them with Apple's official DMG download and extraction. The former GitHub mirror was used only before this commit.

### What the production build consumes
- Official Apple variable font: `SF-Pro.ttf`, copied into `app/src/main/assets/fonts/SF-Pro.ttf`.
- Official Apple Display faces extracted from the same DMG: `SF-Pro-Display-Thin.otf`, `SF-Pro-Display-Light.otf`, and `SF-Pro-Display-Bold.otf`, copied into app font resources/assets as needed by the renderer and UI.
- The active renderer uses Apple's genuine variable-font `wght` and `wdth` axes. CI validates those axes. Subsequent CI commits may subset the official font to clock glyphs, but the source remains Apple's official DMG.
- Current production workflow: `.github/workflows/build.yml`; its “Install official Apple SF Pro variable font and display faces” step downloads directly from Apple's CDN.

### Digit Lab font provenance
- Digit Lab is a separate application/Gradle root, but it uses the **same official Apple source**, not a substitute font.
- `.github/scripts/prepare-digit-lab-fonts.sh` downloads Apple's `SF-Pro.dmg` from `devimages-cdn.apple.com`, extracts `SF-Pro.ttf` and `SF-Pro-Display-Thin.otf`, and subsets the official variable font to `0123456789:` while preserving Apple's `wght` and `wdth` axes.
- GitHub stores the workflow/script and builds the APK; it is not the font source. Never conflate GitHub-hosted CI with GitHub-hosted font assets.

### Required wording for future handovers
- Say **“official Apple SF Pro variable font and Display faces, extracted from Apple's SF-Pro.dmg”**.
- Never say or imply “GitHub font” when referring to the current production or Digit Lab font.

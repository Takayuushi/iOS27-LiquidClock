# iOS 27 Liquid Clock — Project Memory

> Living handover notes for future development sessions. Documentation only: this file is not read by the Android app, Gradle, or the rendering code.

## Digit Lab APK in-place update signing (14 Nov 2026)

- The separate Digit Lab package ID remains `com.example.ios27clock.digitlab`; its workflow already sets `versionCode = GITHUB_RUN_NUMBER`, so later workflow runs get increasing version codes.
- The cause of Android's “App not installed” error when upgrading was the missing stable signing identity: each GitHub-hosted Digit Lab runner generated its own default Android debug keystore, so successive APKs had different signing certificates.
- Fixed in `.github/workflows/digit-lab.yml`: cache `~/.android/debug.keystore` with a branch-scoped key, `digit-lab-debug-signing-${{ github.ref_name }}-v1`, then create it only when the cache is absent. The existing workflow concurrency group serializes branch builds so the cache is created once and reused. This signing key is separate from the production app's key.
- After installing the first APK produced by the persistent-key workflow, subsequent Digit Lab APKs from the same branch can be installed over the existing app without uninstalling; Android keeps Digit Lab's private data/preferences. No OTA updater, in-app download feature, package-ID change, renderer change, or production app signing change is involved.
- **One-time migration required:** older Digit Lab APKs were signed by ephemeral per-run keys. Android cannot upgrade those installations to the new persistent certificate. Uninstall the old Digit Lab once, then install the first APK from the updated workflow. This is limited to Digit Lab and does not require uninstalling or changing the production clock app.
- README documents the migration and normal in-place update procedure. Verify the first updated workflow run passes, confirm its APK installs cleanly after the one-time migration, then confirm the next build updates over it without uninstalling.

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
- Colon optical treatment is material-specific: the requested flatter colon applies only to production Glass / Digit Lab Liquid Glass. Solid / Frosted colon rendering must retain its original 20px optical profile and exact appearance. The new colon mask is a small cropped bitmap; do not replace it with a full-screen second mask.

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
### Colon flattening and spacing-build correction (13 Nov 2026)
- User noted that the colon dots looked too spherical/raised and should be flatter, while the existing digit-pair spacing change should remain shared by Glass and Solid.
- Builds #296 and Digit Lab #18 from `13121da` both failed Kotlin compilation because one obsolete `val tracking = fontSize * trackingEm` line remained after the tracking implementation changed to `trackingAfter()`. Exact CI error: unresolved reference `trackingEm` at `LiquidWallpaperRenderer.kt:1194:35`.
- Commit `3b8383ca3296896ad17fab63ef37b44bf2eb9d35` fixes the stale reference and adds a separate cropped colon mask to the shared renderer. The mask is a tiny crop around only the two dot positions, not another full-screen allocation.
- In AGSL, the colon mask selects a flatter 8px height-map radius for Liquid Glass only. All ordinary Glass glyphs retain the 20px profile. With `solidMode == 1`, the radius remains 20px, preserving the approved Solid/Frosted colon material.
- Digit-to-digit tracking remains at 20% of `TRACKING_EM`; colon-adjacent tracking and colon advance remain unchanged.
- Production build #297 and Digit Lab build #19 are triggered from `3b8383c`; status and on-device result pending.
### Digit spacing correction (13 Nov 2026)
- User noted that the screenshot's digit gaps were wider than the iOS reference, particularly `1–0` and `4–7`.
- This is a shared typography/layout issue, not a material issue. It must apply to both production materials (**Glass and Solid**) and both corresponding Digit Lab options (**Liquid Glass and Frosted Glass**), because all use the shared `:clock-renderer` glyph layout.
- Commit `13121dac4a34c55f7524eeba57af8ab7c81f8f20` (“Tighten digit-pair spacing across Glass and Solid”) reduces digit-to-digit tracking from `TRACKING_EM * 0.55` to `TRACKING_EM * 0.20` (about 64% less pair tracking). Spacing either side of the colon remains at the previous `TRACKING_EM * 0.55`; colon advance itself is unchanged. Font files and Apple's `wdth` axis are not modified.
- The spacing adjustment only changes glyph advances/composition; it does not touch the AGSL material branches, so it does not alter the approved Solid material treatment.
- Production build #296 and Digit Lab build #18 were triggered from `13121da`; on-device spacing comparison is pending.
### Latest user evaluation and optical-light pass (13 Nov 2026, ~15:29 IST)
- The user's latest two Digit Lab screenshots in the preceding message are from the latest successfully built pair at that point: production #297 and Digit Lab #19, both from commit `3b8383ca3296896ad17fab63ef37b44bf2eb9d35`. Do not question whether the user tested the latest builds or imply their screenshots may be stale.
- User's assessment: still not the desired material. Gemini's comparative analysis says the current version has true refractive geometry and a rounded bevel profile, but is too dark/recessed on dark wallpaper and lacks internal light bounce, a thicker/brighter directional rim, stronger chromatic dispersion/saturation, and longer edge refraction offsets to pull nearby bright pixels into the glass.
- User also says adjacent digit pairs still have slightly too much spacing; colon still needs to be flatter, nearly a disc.
- Follow-up commit `a17e2ac048041b252cbff0e53539643851e2cf6a` adds a Glass-only optical lighting pass:
  - widens the digit convex profile radius from 20px to 24px (Glass only), keeping Solid at 20px;
  - broadens Glass edge response and adds a stronger top-left directional rim using the existing rim band;
  - increases Glass two-light specular intensities;
  - raises Snell optical thickness from 30px to 34px with up to 1.22x edge pull;
  - increases glass blur/bounce sample radius from 8px to 22px (Solid remains 38px), screen-blends a 1.55x brightness-boosted blurred environment at 30%, and adds restrained 1.35x saturation plus an image-derived inner tint;
  - increases RGB dispersion spread from 1.8px to a 2.4–4.2px slope-dependent spread;
  - tightens digit-pair tracking again from 0.20x to 0.02x TRACKING_EM. Colon-adjacent tracking remains 0.55x and colon advance is unchanged;
  - flattens only the Liquid Glass colon to a 3.2px profile; Solid/Frosted colon remains at 20px.
- Production Solid / Digit Lab Frosted Glass remains protected. All added shader response is Glass-only; radius selection resolves to 20px at `solidMode == 1`, blur radius remains 38px, existing Solid specular/frosted response remains selected, and the shared typography tracking applies by user request.
- Production build #298 and Digit Lab build #20 were triggered from `a17e2ac`; CI and on-device results pending. Do not claim the optical changes solve the issue until the user's next side-by-side screenshots.
### Latest user screenshot assessment and spacing/bounce correction (13 Nov 2026, ~15:44 IST)
- The user explicitly confirms the beach and diagonal wallpaper screenshots are from the latest successful builds #298/#20 (commit `a17e2ac`). Do not question the test recency or imply the user tested an older build.
- User rejected the result: the beach wallpaper became nearly frosted/opaque due to excessive internal light bounce, even though the dark diagonal wallpaper benefited. Digit spacing remained too wide, especially `4–7` (minutes), and spacing around the colon was also too loose.
- Cause of beach frosting: the previous Glass path used a fixed 30% screen blend of `frostedWallpaper * 1.55 + bias`, independent of the underlying pixel luminance.
- Commit `08d5b9529300f349592b234db630935bbf23d14f` changes:
  - Replaces the fixed 30% bounce with a luminance-adaptive contribution: `glassBounceStrength = 0.18 * (1 - smoothstep(0.16, 0.42, refractedLuma))`. Bright beach/sky pixels now get almost no internal haze; genuinely dark pixels can still receive a modest lift. The blur source multiplier is reduced from 1.55 to 1.28 and bias reduced to (0.012, 0.014, 0.020).
  - Replaces nominal font advances/tracking with actual SF Pro outline-to-outline layout. Each glyph's `getTextPath` bounds (after the same optical width transform) are measured, and the next glyph origin is positioned so the visible outline gap is exactly controlled. Target gaps: 0.018em for digit pairs and 0.016em on each side of the colon. This applies to both production Glass and Solid, and both Digit Lab material options because layout is shared.
  - Colon is positioned by its actual dot centre with a visual-bound diameter based on the same `COLON_DOT_RADIUS_EM`; the existing 3.2px Glass-only flattened optical profile is retained. Solid keeps its 20px profile.
- The approved Solid/Frosted material path remains protected: new bounce is selected into the Glass RGB before the existing `mix(..., center, solidMode)`, and the Glass-only 3.2/24px profile returns to 20px at `solidMode == 1`. The outline-based kerning is intentionally global per user's request and is not a material shader change.
- Production build #299 and Digit Lab build #21 are running from `08d5b95`; compile/artifact and on-device verification are pending. Do not claim the brighter dark-wallpaper balance is solved until the user compares both wallpapers.
### Follow-up shoulder reflection correction (13 Nov 2026, ~15:45 IST)
- After the user reported that the beach-wallpaper version of #298/#20 looked nearly frosted, code inspection found a second source of white fill in addition to the fixed screen-blended bounce: `glassAreaReflection` was strongest for a front-facing normal (dot with key half-vector near 0.9), so it added a broad white reflection across flat face areas.
- Commit `c42e382909b52881d9159e24d877932283940c5a` gates the broad Glass softbox reflection with `smoothstep(0.08, 0.48, glassProfileSlope)`. The broad reflection now appears on the curved shoulder using the same SDF-height-map slope that defines the normal; it no longer paints a white fill over the flat centre. This contribution remains Glass-only through the existing `(1.0 - solidMode)` output gate.
- The combined candidate for testing is now `c42e382`, which includes the adaptive dark-only bounce and outline-to-outline kerning from `08d5b95`, plus this shoulder-only reflection gate.
- Production build #300 and Digit Lab #22 were triggered from `c42e382`; on-device verification is pending. Earlier #299/#21 workflows from parent `08d5b95` may still be running, but #300/#22 are the final candidate for the next device comparison.
### User approval of spacing and request to reduce excessive Glass glow (13 Nov 2026, ~15:59 IST)
- User tested the latest successful production #300 and Digit Lab #22 builds from `c42e382`. They explicitly said the digit spacing is now good and approved the tighter gaps.
- User observed that the bright Glass outline glow is too strong; the `4` and `7` look a little broader as a result.
- Confirmed architecture: root production includes `:clock-renderer`; Digit Lab includes the exact shared module via `project(":clock-renderer").projectDir = file("../clock-renderer")`. Glyph layout is shared, so visible-outline kerning applies to both production Glass and Solid and to both Digit Lab options. The user requested this global typography change; do not roll it back by material.
- New renderer commit `6b0f1d9f0713ff8350e3fcf900b76169ee0b7a9b` (“Reduce Liquid Glass rim glow and restore digit profile width”) makes Glass-only changes:
  - Returns Glass digit profile radius from 24px to 20px; the separate Glass colon remains at 3.2px. Solid remains at 20px.
  - Narrows the Glass rim exponential width from 6.2 to 4.8.
  - Reduces broad area reflection scale 0.82→0.64 and output 1.12→0.96.
  - Reduces key glint scale 1.18→0.88 and output 1.55→1.22.
  - Reduces fill glint scale 0.72→0.56 and output 1.02→0.88.
  - Reduces separate rim-light output 0.34→0.23.
  - All these material outputs remain gated by `(1.0 - solidMode)`; no Solid shader/material values were changed.
- Production build #301 and Digit Lab build #23 were triggered from `6b0f1d9`; CI and on-device verification pending.
### Latest user evaluation and spacing / Glass optical refinement (13 Nov 2026, ~16:21 IST)
- User tested the successful Production #301 and Digit Lab #23 builds from `6b0f1d9` and supplied the latest beach and dark diagonal wallpaper screenshots.
- User says the colon is now too close to the minutes, the hour pair needs a little more breathing room, while the approved `4–7` minute-pair gap should remain. The `4` lower horizontal stroke and `7` upper horizontal stroke also appear visually too broad. The beach image still needs more convincing Liquid Glass material character.
- New commit `9cc30ea2ff03cea1c20b0a6de9303655ad4cad67` (“Tune colon and hour spacing and refine Glass shoulder lighting”) makes role-specific shared glyph spacing:
  - hour pair `1–0`: `0.024em` (previously 0.018em);
  - minute pair `4–7`: stays at the user-approved `0.018em`;
  - hour digit to colon: remains `0.016em`;
  - colon to first minute digit: increases to `0.030em`.
  This is shared outline-to-outline layout and therefore applies in both production Glass/Solid and both Digit Lab modes.
- Same commit softens specular on horizontal-facing Glass shoulders by 26% using the height-map normal's Y component; this is intended to stop the horizontal cap highlights at the 4 base and 7 top making those strokes look overly broad, without altering SF Pro outlines or axes.
- Adds a modest sharp, unblurred, wallpaper-derived key-environment reflection to the Liquid Glass face (3.5% base up to 11% at the curved shoulder). This is gated by `(1.0 - solidMode)`, so it does not enter Solid. Glass shoulder side shading increases from 0.16 to 0.22, also Glass-only. No white fill or blurred backdrop layer was added.
- Solid/Frosted material code remains protected. Shared spacing changes apply to Solid because the user explicitly requested that typography changes be shared; all new reflection/highlight/shadow adjustments are Glass-only.
- Production build #302 and Digit Lab build #24 were triggered from `9cc30ea`; CI and on-device verification pending.
### Final micro-adjustment before production app verification (13 Nov 2026, ~16:40 IST)
- User tested Production #302 and Digit Lab #24 from `9cc30ea`, says the clock is almost finished. Latest screenshots show the `1–0` hour pair is slightly too far apart, while the `4–7` minute pair needs a tiny increase. User did not request any change to colon spacing or the colon optical profile.
- Commit `71a36ce6a93b4ffa011d832bdf0f712956da6ab8` (“Fine-tune hour and minute pair gaps”) changes only two shared outline-layout constants:
  - `hourPairGapEm`: 0.024em → 0.021em (brings 1–0 slightly closer);
  - `minutePairGapEm`: 0.018em → 0.021em (opens 4–7 slightly);
  - colon gaps stay exactly 0.016em (hour-to-colon) and 0.030em (colon-to-first-minute);
  - no font files, variable axes, glyph outlines, material shader values, colon dot size or optical profile changed.
- This is shared `:clock-renderer` typography and therefore affects both production Glass and Solid plus both Digit Lab options.
- Production build #303 and Digit Lab build #25 were triggered from `71a36ce`; CI and device verification pending.
- User plans to test the original production app after this final micro-adjustment. If it behaves correctly, they expect to declare the project done. Do not start additional visual redesigns unless the user reports a specific regression.
### Clarification: hour pair vs. hour-to-colon gap (13 Nov 2026)
- User corrected the interpretation of the previous spacing request: **the `10` pair itself is NOT too tight**. The issue is that the complete hour pair `10` sits too close to the colon, specifically the visible gap between `0` and `:`. Do not change the `1–0` gap based on that feedback.
- The previous commit `71a36ce` incorrectly changed `hourPairGapEm` 0.024em→0.021em. Commit `b44c11c734e7d96ec50cede1c35f3a91e014d6b5` restores it to 0.024em and instead increases only `hourToColonGapEm` from 0.016em to 0.024em.
- Keep `minutePairGapEm` (4–7) at the user's requested slight increase of 0.021em. Keep `colonToMinuteGapEm` at 0.030em. No font, material, glyph profile, or colon geometry changes.
- Production #304 and Digit Lab #26 have been triggered from `b44c11c`; CI/device verification pending.
### Clarification of spacing-feedback baseline (13 Nov 2026)
- User clarified that the earlier “almost there” opinion (slightly adjust the hour/colon and 4–7 areas) was based on the earlier screenshot comparison, **not the latest #303/#25 build**. Do not treat that opinion as evaluation of #303/#25 or infer further changes from it.
- User's explicit subsequent instruction was to keep `1–0` unchanged, slightly increase `0–:`, and slightly increase `:–4`.
- Latest commit `9f3a145641e685b3894fc298691c0c2ec8d6c5ae` changes only the two colon-adjacent spacing constants:
  - `hourPairGapEm` / `1–0`: remains 0.024em.
  - `minutePairGapEm` / `4–7`: remains 0.021em.
  - `hourToColonGapEm` / `0–:`: 0.024em → 0.027em.
  - `colonToMinuteGapEm` / `:–4`: 0.030em → 0.033em.
- The new commit cancels the in-progress #304/#26 workflows from parent `b44c11c`. The correct current candidate is Production #305 and Digit Lab #27 from `9f3a145`. Both are running; the on-device result has not yet been tested.
### Fair lock-screen comparison and Liquid Glass vibrancy pass (13 Nov 2026, ~16:52 IST)
- User supplied a level-playing-field comparison: Digit Lab clock over the exact diagonal light-streak wallpaper, beside the original iOS lock-screen clock on that same wallpaper. The user removed the original clock with Galaxy eraser before using the wallpaper for Digit Lab. Treat these screenshots as the latest visual comparison; do not question the user's test or compare against a different wallpaper.
- Assessment: the iOS clock looks brighter, more vivid, jewel-like, with saturated warm/cool colour and luminous internal light across the whole glass stroke. Our output looks comparatively dull/dark, mostly transmitting the wallpaper with a thin perimeter effect. The missing quality is whole-volume coloured light transport, not simply more white edge glow.
- Latest actual renderer commit: cc4719c5dd5f4c6f051190b5f8c2cdf48943947f — “Restore Liquid Glass vibrancy with adaptive coloured light”. Verified GitHub compare confirms it changes LiquidWallpaperRenderer.kt plus memory; the source blob SHA changed to f2d3e2d0661a1ef70b2103795d9c878eda0e0964.
- Glass-only changes in cc4719c:
  - Face illumination increased from baseline 0.54 + 0.34*key + 0.18*fill to 0.70 + 0.28*key + 0.14*fill, to keep the transmitted wallpaper lively rather than dark/cut-out.
  - Adds paired key/fill wallpaper environment samples (42px key / 34px fill offsets), mixed 64/36, then raises sharp image-derived face reflection from 3.5–11% to 7.5–20% according to surface slope.
  - Saturation of refracted image increased from 1.35x to 1.52x.
  - Chromatic dispersion increased from 2.4–4.2px to 3.2–5.8px along the refracted ray.
  - Blurred light-capture radius for Glass increased from 22px to 30px; Solid stays 38px.
  - Internal screen-bounce now maxes at 28%, gated by BOTH pixel darkness and local blurred-neighbour luminance. This is intended to pull saturated light into dark wallpaper regions without causing the beach/sky to become frosted.
  - White rim/glint intensities are not increased. Typography, outline kerning, colon spacing, font axes and material geometry are unchanged.
- Solid/Frosted protection: all new optical values are selected by the existing Glass-vs-Solid mix/gate. Blur radius is 38px for Solid; glassFaceLighting is mixed to exactly 1.0 for Solid; new face reflection, refraction dispersion, and light bounce are mixed back to the original Solid source. No production Solid material settings are changed.
- Production #306 and Digit Lab #28 workflows were triggered from cc4719c; status and on-device acceptance pending.
- Earlier current spacing in source remains: 1–0 0.024em; 0–: 0.027em; :–4 0.033em; 4–7 0.021em. Do not alter these as part of optical tuning.
### Same-wallpaper comparison: brightness and 4/7 apparent thickness (13 Nov 2026, ~18:47 IST)
- User tested Production #306 / Digit Lab #28 from commit cc4719c and supplied three images: Digit Lab over the beach wallpaper, Digit Lab over the diagonal streak wallpaper, and the iOS lock-screen reference over the same diagonal wallpaper.
- User assessment: our version is still substantially dimmer/duller than iOS; improved but not at the reference level. The horizontal strokes/caps on 4 and 7 also look wider in this build.
- Image comparison notes: the Digit Lab clock in the screenshot is larger than the native iOS reference (roughly 20% taller and around 10% wider by visible digit bounds). This and the selected Digit Lab weight value 757 (a heavy setting) affect absolute pixel stroke thickness. The user should be told clearly, without implying their comparison is invalid. The same scale should be matched before pixel-width comparisons.
- Renderer inspection: commit cc4719c did not change glyph paths, wght/wdth axes, glyphWidthProfile, glyphHeightFactor, or kerning. The horizontal bar apparent spread is likely a perceptual effect from (a) heavy user-selected wght=757, (b) larger Digit Lab clock scale, and (c) the new face reflection contribution that was not gated by horizontal shoulder orientation. The 4 glyph optical X profile remains 1.08; 7 remains 0.96; glyphHeightFactor for both is 0.985.
- New commit 25893a9e8888d8e457f91327b64865b477e90d14 — “Increase Liquid Glass inner colour and confine reflections to curved bevels”:
  - Glass horizontal highlight multiplier is reduced from 0.74 to 0.58 at strongly horizontal-facing shoulders.
  - The face reflection is now screen-blended (colour-preserving, additive light pickup) rather than a straight linear mix, and multiplied by glassHorizontalHighlight so it follows curved shoulders instead of filling flat bars.
  - Glass face lighting increases from 0.70 + 0.28*key + 0.14*fill to 0.82 + 0.30*key + 0.16*fill.
  - Adaptive internal colour bounce increases from 28% to 38%, with blur capture radius 42px for Glass only (Solid remains 38px); blurred bounce sample multiplier rises to 1.52 and slight channel bias.
  - No glyph geometry, font wght/wdth axis, line spacing, colon size/spacing, or explicit white rim/glint intensity changed.
- Solid protection: Glass face lighting is mixed to exactly 1.0 for Solid; bounce is selected out by the existing solidMode mix; environment blur is 38px in Solid; face reflection contribution is gated by (1-solidMode); horizontal glass highlight affects only Glass terms. The approved Solid material is untouched.
- Production #307 and Digit Lab #29 were triggered from 25893a9; CI and on-device verification pending.

### Beach frosting and same-wallpaper comparison correction (13 Nov 2026, ~19:29 IST)
- User tested Production #307 and Digit Lab #29 from `25893a9`. Latest screenshots include the beach wallpaper preview, the diagonal streak wallpaper preview, and the iOS reference clock over the same diagonal wallpaper. User says ours is still not at iOS brightness/vibrancy and the beach version reads almost like Frosted Glass.
- Source diagnosis: #307 stacked multiple full-luminance reflection contributions (`glassFaceReflectivity = Fresnel * 0.88`, a 5.5–24% screen blend of two directional environment samples, and a further 22% Fresnel mix) plus a 42px blurred sample contributing up to 38% internal bounce. On bright pink/blue beach sky this can wash the face into a pale layer; on the high-contrast diagonal wallpaper the same reflections pick up vivid streak colours and look better.
- Commit `686220caa878bc7b92516206bed190434868c93d` — “Prevent beach frosting with luminance-aware chromatic reflections”:
  - reduces Glass blur radius from 42px to 22px; Solid remains 38px.
  - reduces bounce maximum from 38% to 20%, tightens darkness gate to luma 0.08–0.24, requires nearby illumination at luma 0.10–0.32, and reduces blurred sample gain/bias.
  - caps Fresnel reflection multiplier at 0.64 (from 0.88) and attenuates it for high-luminance environment samples. Reflection colour is separated into luma/chroma; chroma gets a small 1.18x boost.
  - removes the broad screen-blend reflection. Replaces it with chroma pickup from directional wallpaper samples and only a small luminance lift on genuinely dark glyph-face regions. Horizontal-surface limiter remains active.
  - reduces the final stacked Fresnel reflection from 22% full-colour mix to 12% chroma-only contribution.
  - spacing, glyph geometry, official SF Pro axes, colon geometry and white rim values unchanged.
- Solid/Frosted protection: blur stays 38px under `solidMode`; new bounce is mixed to the original solid source; face reflectivity is used only to form the Glass face before the Solid/Frosted `body` selection; directional chroma pickup and final Fresnel contribution are explicitly gated by `(1.0 - solidMode)`. Approved Solid renderer remains unchanged.
- Production #308 and Digit Lab #30 were triggered from `686220c`; CI and on-device validation pending.
### Lower-terminal highlight diagnosis and targeted Glass fix (13 Nov 2026, ~19:48 IST)
- User tested Production #308 / Digit Lab #30 from `686220c` and says the iOS comparison is still more illuminated, especially at the lower end-cap of the 7's descending stroke. User will continue providing direct same-wallpaper comparisons until it is right.
- Inspection of the exact screenshot pair at 945x2048 and 944x2048 confirms the missing effect is a tight cyan-white directional highlight at the lower terminal, not just more overall brightness. The iOS reference has a visible cap reflection; the Digit Lab version mainly has the thin perimeter rim there.
- Root cause in shader: to prevent the horizontal bars on 4 and 7 looking too wide, `glassHorizontalHighlight = mix(1.0, 0.58, horizontalShoulder)` suppresses reflection for strongly vertical Y-facing surface normals. That also attenuates the 7's terminal cap. Existing fill/key glints are likewise tied to the broad-bar limiter, so the cap highlight has no independent directional term.
- New commit `9c06eb5146c5975e769e8a30ae840eba34a71e2f` — “Add directional Liquid Glass end-cap reflection” adds a localized, lower-left-facing cap lobe after existing rim terms:
  - 2D outward SDF normal aligned to `(-0.28, 0.96)` with a 12th-power narrow angular lobe;
  - edge-only activation gated by `glassProfileSlope` and `normalConfidence`;
  - a separate 3D half-vector for the cap, creating a compact high-intensity specular return;
  - adds wallpaper-derived reflection chroma plus a restrained cyan-white core.
  - Explicitly Glass-only via `(1.0 - solidMode)`; does not change broad horizontal highlights, glyph mask/path geometry, font axes, kerning or Solid.
- Production build #309 has started from `9c06eb5`, run ID `43441833708`; Digit Lab build status not yet present in latest run listing at time of note. CI and device test pending.
- Existing spacing remains: `1–0` 0.024em, `0–:` 0.027em, `:–4` 0.033em, `4–7` 0.021em. Do not change.
### Corrected 7 end-cap illumination direction and tiny 1–0 gap increase (13 Nov 2026, ~20:08 IST)
- User tested Production #309 and Digit Lab #31 from commit `9c06eb5`; both Actions runs succeeded. The current screenshots show that the dedicated terminal highlight is still too weak, while the 1–0 spacing needs a tiny increase.
- Visual inspection of the same-wallpaper reference plus the composite overlay identifies a direction mistake in the previous cap lobe: SF Pro's 7 descends from upper-left toward lower-right, so the lower terminal's outward normal is approximately `(+0.28,+0.96)`. The earlier code pointed `(-0.28,+0.96)` and the cap specular half-vector was aimed lower-left, explaining why the special highlight barely appeared.
- New source commit `0b3789d1d7645c8220382dd0fd82651892234c05` — “Aim 7 terminal highlight correctly and refine glass illumination” makes these exact changes:
  - `terminalAxis` is corrected from `(-0.28,0.96)` to `(+0.28,0.96)`; dedicated 3D cap light X direction is flipped to +0.32; angular exponents broaden 12→8 (2D) and 12→9 (3D); wallpaper-chroma cap peak increases 0.82→1.02 and cyan-white core 0.24→0.34. This is a local Glass-only end-cap contribution; existing broad bar limiter remains untouched.
  - shared visible outline gap `1–0` increases from 0.024em to 0.026em (a small +0.002em adjustment at user's request). Other gaps stay 4–7 0.021em, 0–: 0.027em, :–4 0.033em.
  - whole-glass colour pickup is raised modestly without reintroducing a frosted veil: adaptive dark/local-light bounce max 0.20→0.24; blurred bounce sample gain 1.38→1.44; environment chroma gain 1.18→1.28; directional chroma pickup 1.20→1.34; final chromatic Fresnel 0.12→0.15. The 22px Glass blur, strict local darkness/highlight gates and high-luminance reflection protection remain unchanged.
  - no glyph outline geometry, official font source/axes, horizontal bar highlight limiter, colon geometry, or any Solid material settings changed.
- Build #309 and Digit Lab #31 are confirmed successful from parent `9c06eb5`; these latest screenshots prove the original lower-left cap lobe was not sufficient. New builds for `0b3789d` have been triggered; run IDs to be verified.
- Do not claim success until the user checks the new lower-right 7 terminal reflection and the tiny 1–0 spacing increase, plus the beach wallpaper for no frosted regression. Continue same-wallpaper side-by-side comparisons.
### Gemini theory review and targeted refinement pass (13 Nov 2026, ~20:20 IST)
- User provided Gemini's evaluation of the latest #310/#32 comparison over the same diagonal wallpaper. Gemini says the color/chromatic dispersion and jewel-like presence are now close; smooth interior refraction and colon dots are strong. Remaining differences: (1) the iOS reference maintains a subtle silver ambient body over pure-black wallpaper, while our 1 and upper-left 0 can go nearly black; (2) Apple's edge reflection is a wider, softer, directional bevel instead of our thin sharp rim; (3) our refracted white streaks are sharper than Apple's subtly diffused beams.
- Assessment: all three observations are directionally credible. Do NOT apply Gemini's fixed `mix(finalColor, ambientGradient, 0.25)` indiscriminately. That could make the beach preview milky/frosted again. Use a dark-pixel-gated ambient floor, interior mask, and vertical gradient; keep bright areas fully image-derived. Likewise, widen but dim the rim to avoid a white drawn outline. Apply only a low-weight local diffusion kernel to the refraction sample, not a global wallpaper blur.
- Latest tested builds before refinement: Production #310 and Digit Lab #32 succeeded from `0b3789d`; user screenshot `Screenshot_20261113_202033.jpg` (945x2048, 8:20 time, weight 619, compressed width) compared against `RDT_20261112_1627083837676783770026508(9).jpg` (944x2048) on the same diagonal wallpaper.
- New commit `566df1cc84e4fe379af41c959d7441276e459156` — “Add adaptive ambient scatter and soften Liquid Glass rim”:
  - Adds a vertical silver-blue ambient gradient inside the Glass glyph, but only where the final transmitted face is nearly black (luma gate 0.025–0.20) and inside the rounded stroke core (`glassProfileCoverage` mask). The gradient runs approximately RGB 0.34/0.37/0.43 to 0.21/0.24/0.30, blended at up to 52%, and is explicitly multiplied by `(1.0-solidMode)`. Its output over pure black is therefore a limited ~0.11–0.19 ambient lift, not a fixed whole-digit gray overlay. Bright beach sky/sand remain essentially unchanged.
  - Widens Glass rim falloff from 4.8px to 6.2px and reduces peak output 0.23→0.18, retaining directional key-light and horizontal-shoulder gating. Solid rim is unchanged.
  - Adds four local refraction taps at ±2px X/Y; their average is mixed into the chromatically refracted image at 10–15% based on surface slope. This softly diffuses harsh streak edges without globally blurring wallpaper or changing Solid (the Glass-transmitted branch is mixed back to original center for Solid).
  - No changes to variable font source/axes, glyph paths, spacing, colon profile, terminal cap lobe, or Solid material.
- Production build #311 and Digit Lab #33 triggered from `566df1c`; CI and on-device testing pending.
- Current spacing remains: 1–0 0.026em; 4–7 0.021em; 0–: 0.027em; :–4 0.033em. Preserve.
- User wants continued same-wallpaper comparison until it matches. Do not claim the beach or ambient-light issue is resolved until tested on device; specifically inspect black-background parts of 1/0, rim softness and refraction blur, plus beach wallpaper clarity.
### Correct colon spacing and restore crystal-clear refraction (13 Nov 2026, ~20:50 IST)
- User tested Production #311 / Digit Lab #33 from commit `566df1c`. Gemini's suggestions resulted in an overly soft / blurry read: the orange diagonal streak through `0` and the vertical arms of `4` and `7` should bend as a clear crystal lens, not look locally blurred or merely transparent.
- User also observed the colon gaps remain wrong: increase the `0–:` gap; increase `:–4` only a tiny amount, and ensure the visible `:–4` gap is still smaller than the `0–:` gap. Do not change `1–0` or `4–7`.
- Source values before correction were `hourToColonGapEm=0.027` and `colonToMinuteGapEm=0.033`, meaning the :–4 gap was numerically larger. New commit `10726db00956273b65800e83885c14e16aa120ac` (“Restore crystal-clear refraction and balance colon spacing”) changes them to:
  - 1–0 remains 0.026em.
  - 0–: increases 0.027→0.0355em.
  - :–4 changes 0.033→0.034em (tiny increase, now lower than 0–:).
  - 4–7 remains 0.021em.
- Removes the entire four-tap 2px local diffusion kernel introduced in `566df1c`; no local blur remains in the refracted color sample.
- Strengthens actual Snell refraction rather than adding blur: Glass IOR 1.50→1.52; optical thickness 34px→42px; curved edge-pull multiplier 1.22→1.30. Existing surface-normal / SDF lens geometry, dispersion and wallpaper sample stay otherwise unchanged.
- The commit diff has been checked: only these typography spacing constants, Snell IOR/thickness/edge pull, and deletion of local diffusion changed. No glyph paths, font source/axes, colon geometry, Solid warp or Solid shader path changed.
- Production #312 run ID `43452031296` and Digit Lab #34 run ID `43452031200` are both running from `10726db`; CI/device verification pending.
- All earlier ambient scatter, soft rim and lower-terminal cap improvements remain from parent commits.
- Do not claim the refraction is fixed until the user compares the orange streak through 0 and vertical arms of 4/7 on the exact same wallpaper. Also verify 0–: is now wider than :–4, with 1–0 and 4–7 unchanged.
### Unified optical model and pair-spacing refinement (13 Nov 2026, ~21:11 IST)
- User tested Production #312 / Digit Lab #34 from commit `10726db`. Their new same-wallpaper screenshots say:
  - The visible 1–0 pair still needs a little more gap; the previous 0.026em increase was too subtle/not apparent.
  - 4–7 should be a little tighter.
  - Some of the background appears improved, but the orange streak through 0 and the vertical arms of 4/7 still read as too transparent / blurry rather than a coherent crystal lens.
  - User explicitly clarified that 0/4/7 are examples to direct attention to the optical blending, NOT a request for separate digit-specific shader tricks. The clock must use one shared material/refraction model, so future time changes preserve the effect without digit-specific tuning.
- Existing source inspection found an explicit lower-right `7` terminal highlight lobe (hard-coded `terminalAxis` / `terminalCapSpecular`) from commit `9c06eb5`. This contradicted the user's unified-material requirement.
- New commit `4c8d8286bd5c29160f0003d317d9ebb84c8044b7` — “Unify glyph refraction and refine shared clock spacing”:
  - Shared pair gaps only: `hourPairGapEm` (1–0) 0.026→0.028em; `minutePairGapEm` (4–7) 0.021→0.019em. Colon gaps stay 0–: 0.0355em and :–4 0.034em.
  - Removes the explicit 7-specific `terminalAxis`/`terminalCapSpecular` block.
  - Adds one common `glassBevelSpecular`, derived from the same `glassSurfaceNormal`, `keyHalfVector`, `glassProfileSlope` and `normalConfidence` used for every glyph. Applies to all glyphs equally; no numeral ID, coordinate, or one-off digit branching.
  - Tightens finite-difference SDF normal sampling from 5.25px to 4.25px and changes height-gradient denominator 10.5→8.5 to match, to produce a sharper shared surface normal.
  - Increases shared Glass optical path thickness 42→46px and edge-pull 1.30→1.34; physical IOR remains 1.52. This strengthens actual Snell refraction uniformly rather than adding blur.
  - No glyph paths, official font axes, colon profile, material Solid source/warp, or per-digit shaping code changed.
- Production #313 run ID `43456230894` and Digit Lab #35 run ID `43456230829` triggered from `4c8d828`; CI pending at time of note.
- Solid/Frosted remains protected: all new bevel terms are multiplied by `(1.0-solidMode)`; Solid warp is unchanged. Shared typography spacing naturally applies to both materials and both app modes.
- User requests continued side-by-side same-wallpaper comparisons and explicitly says to use unified material logic, not per-digit patches. Next acceptance checks: 1–0 gap visible; 4–7 slightly tighter; orange streak is bent/reflected through the 0; vertical arms remain crisp/crystalline; no frosted beach regression; Solid unchanged.
### Scope correction: latest report did not ask to change 4–7 (13 Nov 2026, ~21:30 IST)
- User correctly challenged the latest response: their immediately preceding report did NOT request any 4–7 spacing change. They had asked for 4–7 tightening in an earlier report, but that was already implemented as 0.019em in commit `4c8d828`. The most recent screenshot feedback asked only to increase 1–0, slightly increase transmitted-background blur/diffusion, and improve unified blending at the 7's vertical terminal.
- I mistakenly changed the shared 4–7 gap again from 0.019em to 0.017em in commit `f47ee08`. User did not ask for this.
- Corrective commit `d5816c05d0ed05aed1dbd2f7528919ca5605ad20` (“Keep latest spacing pass limited to requested 1–0 gap”) restores `minutePairGapEm` from 0.017em to 0.019em. This is the accepted previous 4–7 value; do not make any further 4–7 changes unless the user explicitly asks.
- Current requested spacing is 1–0 0.036em (clearer ~few px increase from 0.028); 4–7 0.019em (preserved); 0–: 0.0355em (preserved); :–4 0.034em (preserved).
- The user's latest optical note: in the reference, two adjacent white wallpaper bands blend together more than in our glass; ours retains clearly separated white bands. They want slightly stronger unified transmission blur/diffusion so bands merge more as they pass through the glass, and the blue/white/black regions at the lower end of the 7's vertical arm should blend as a coherent crystal lens rather than remain distinctly segmented. This is a global material requirement, not per-digit handling.
- The candidate renderer pass in `f47ee08` added a shared 3.8px four-direction microfacet transmission sample mixed 30–38%, and changed pair spacing 1–0 0.028→0.036em / 4–7 0.019→0.017em. Commit `d5816c0` corrects only the latter; all other `f47ee08` changes remain.
- Builds #314 and Digit Lab #36 from `f47ee08` were triggered but are now superseded for testing because their 4–7 value is 0.017em. Do not hand these builds to the user as the corrected final candidate.
- New builds from `d5816c0` must be identified by exact head SHA and offered instead. CI/device test pending.
- Do not claim the optical blend is complete until user verifies same-wallpaper comparison.
### Reference-measured spacing audit and micro-adjustment (13 Nov 2026, ~21:45 IST)
- User explicitly authorizes the assistant to identify very small spacing differences by comparing the project's clock against the actual iOS lock-screen reference; do not blindly keep user-approved spacing if a measurable mismatch remains. The user also said the 4–7 gap currently looks perfect, but gives permission to adjust tiny amounts only when comparison evidence supports it.
- Compared the exact reference capture `RDT_20261112_1627083837676783770026508(10).jpg` (944x2048, iOS 10:47 over diagonal wallpaper) with the latest Digit Lab capture `Screenshot_20261113_212940.jpg` (945x2048, same wallpaper / 10:47). They are close in overall scale and composition; a wallpaper-based alignment plus enlarged clock crops was used to inspect glyph boundaries rather than judging by memory or a different image.
- Assessment: 4–7 is already very close to reference; leave at 0.019em. :–4 also appears close; leave at 0.034em. Two subtle under-spacing differences remain: 1–0 is just a little too tight, and 0–: is slightly too tight. Make small, not large, changes.
- New commit `39ad2b6b7bf4be6f17929ce33022c98a34c464da` — “Micro-tune shared 1–0 and 0–colon spacing against iOS reference”:
  - `hourPairGapEm` (1–0 / all hour digit pairs): 0.036→0.040em.
  - `hourToColonGapEm` (0–: / all hour-to-colon pairs): 0.0355→0.038em.
  - `minutePairGapEm` (4–7 / all minute digit pairs) stays exactly 0.019em.
  - `colonToMinuteGapEm` (:–4) stays exactly 0.034em.
- This remains role-based, shared typography layout, not digit-specific. All time values use the same pair-role logic. No font source/axes, glyph geometry, Solid rendering, or shader optical parameters changed.
- Production #316 run ID `43461985770` and Digit Lab #38 run ID `43461986030` started from exactly `39ad2b6`; CI and device comparison pending.
- User wants future spacing checks across any digits in the time string by reference comparison, but do not change a pair if already matching. Preserve 4–7 unless a future same-size comparison shows a clear mismatch.
### New same-wallpaper comparison: stronger band diffusion and internal volume light (13 Nov 2026, ~22:10 IST)
- User says spacing is now accepted and should no longer be changed as part of this issue. Current spacing from `39ad2b6`: 1–0 0.040em; 4–7 0.019em; 0–: 0.038em; :–4 0.034em. Do not alter these without a separate explicit request or compelling new reference comparison.
- Latest user screenshots: `Screenshot_20261113_220238.jpg` and `Screenshot_20261113_220204.jpg`, same 945×2048 frame. Reference capture used for comparison: `RDT_20261112_1627083837676783770026508(10).jpg` (944×2048, same diagonal wallpaper and iOS 10:47 lock-screen clock).
- The side-by-side crop shows Apple's digits spread adjacent white wallpaper bands into a more continuous, softly illuminated glass volume. The current Digit Lab transmission retains more distinct high-contrast white/blue/black band boundaries, especially at the 7 terminal. The user wants increased blur/diffusion, stronger internal illumination/body, and unified rendering across all glyphs—not digit-specific patches.
- Diagnosis of previous `39ad2b6` build #316 / Digit Lab #38: its microfacet kernel was only 3.8px, 4 cardinal taps, blended 30–38%. Since 64% of the source was still central sample weight, the effective neighbor contribution was roughly 19–24%. It was too weak to merge the white bands meaningfully.
- New commit `dd7eca11de80a70ec9b6421c6442deba4c21b76b` — “Strengthen shared Liquid Glass band diffusion and volume illumination”:
  - Replaces the isotropic 4-tap microfacet blur with a shared, local-gradient-oriented diffusion. Four luma probe samples (±4px X/Y) estimate the wallpaper's high-contrast edge normal; the blur axis follows that gradient, across bands rather than along their length.
  - Adaptive radius increases from 4.8px in low contrast to 7.6px in high contrast, with symmetric near/far taps at 0.62× and 1.34× radius.
  - Normalized microfacet sample weights: center 0.34, near pair 0.21 each, far pair 0.12 each (sum 1.0). Diffusion mix 0.22–0.58, modulated gently by the common SDF glass-profile slope. This raises the effective neighbour contribution to max ~38%, compared with 24% before, and spans a wider region in high-contrast bands.
  - Adds a restrained shared volume-scatter blend (maximum 8.5%) based on local microfacet luminance and interior glass coverage. It lifts/merges illuminated midtones but attenuates nearly-white samples to avoid washing bright backgrounds into Frosted Glass.
  - Applies to every glyph by local wallpaper gradient and common Glass surface data; no digit identity, fixed digit coordinates or per-numeral terms. Solid still uses the original `center` sample via `mix(glassTransmitted, center, solidMode)`.
  - No changes to kerning, glyph outlines, official SF Pro source/axes, IOR, glass path length, or Solid/Frosted implementation.
- Production #317 run ID `43464918499` and Digit Lab #39 run ID `43464918599` started from exact SHA `dd7eca1`; CI and device validation pending.
- Next test should use same diagonal wallpaper and compare whether adjacent white bands become a continuous soft reflection within 0/4/7, especially 7 terminal, without erasing crystal transparency or making the beach wallpaper milky. Check Solid remains pixel-identical.
### Follow-up: preserve beach improvement; strengthen diagonal wallpaper band blending only (13 Nov 2026, ~22:25 IST)
- User supplied latest tests `Screenshot_20261113_221640.jpg` (beach wallpaper, 945×2048), `Screenshot_20261113_221545.jpg` (diagonal wallpaper Digit Lab, 945×2048), and exact iOS comparison `RDT_20261112_1627083837676783770026508(20261113-164744).jpg` (944×2048).
- Visual inspection: the beach implementation from the previous pass is improved and the user explicitly says it is okay; preserve it. On the same diagonal wallpaper, the current `0` still shows the two white bands as distinct sharp stripes compared with the reference's more continuous, softly blended transmission. The `7` still has a harder blue/white boundary than the reference, especially through its diagonal stroke/terminal region. User says these are material observations, not requests for digit-specific handling.
- Previous candidate `dd7eca1` was verified built successfully: Production #317 / Digit Lab #39 (both same SHA). The new screenshots are actual-device feedback on that build.
- Latest commit `daf9df4297db1f44311c533d2047924cea601048` — “Increase shared Liquid Glass band blending against iOS reference”:
  - Changes only the shared contrast-directed microfacet transmission diffusion.
  - Adaptive sampling radius: 4.8–7.6px → 6.8–10.8px.
  - Far tap distance: 1.34× → 1.38× radius.
  - Normalized sample weights: center 0.34→0.28; near pair 0.21→0.20 each; far pair 0.12→0.16 each (sum remains 1.0).
  - Diffusion mix range: 0.22–0.58 → 0.25–0.72; surface-slope gate floor 0.82→0.90.
  - No changes to ambient/volume illumination (volume scatter remains 8.5%), glass reflections, edge lighting, wallpaper sampling outside this kernel, typography, spacing, fonts, or glyph geometry.
  - Still one common, wallpaper-gradient-driven algorithm across every glyph/time; no digit-specific conditions.
  - Solid remains unchanged: the Solid branch still mixes transmitted colour back to the original center wallpaper sample.
- Production #318 run ID `43466827742` and Digit Lab #40 run ID `43466827587` started from exact SHA `daf9df4`; CI and device validation pending.
- User confirms current spacing is acceptable; don't change any spacing on this optical pass. Current values: 1–0 0.040em; 4–7 0.019em; 0–: 0.038em; :–4 0.034em.
- Next test: on the diagonal wallpaper compare whether 0's two white bands now merge more closely to reference and whether 7's blue/white transmission boundary becomes softer without losing crystal clarity. Recheck beach wallpaper stays as good as prior candidate. No spacing or Solid changes.
### Final polish from Gemini review: ambient sheen, internal scattering and 1–0 gap (13 Nov 2026, ~22:55 IST)
- User shared Gemini's assessment of candidate build #318 / Digit Lab #40 and additional same-wallpaper screenshots. Gemini considers color/refraction, colon lenses and rim bevel very close (~95% visual parity; this is Gemini's estimate, not an objective measurement). Remaining optical details:
  1. The left half/stem and top hook of the 1 need a continuous brushed-silver ambient sheen over dark wallpaper. Our current luma-gated ambient fill leaves a dark horizontal break and gray/dark hook.
  2. Internal body scattering in 4 and 7 is still too sharp/high-contrast compared with Apple's softer, more translucent/milky glass.
  3. User also repeats that 1–0 remains too close in Solid/Frosted mode and asks for a tiny extra gap.
- Reviewed the exact screenshots:
  - Digit Lab diagonal wallpaper: Screenshot_20261113_222832.jpg (945×2048, clock 10:47, Liquid Glass selected).
  - Solid/Frosted beach preview: Screenshot_20261113_223346.jpg (945×2048, Solid/Frosted selected).
  - Liquid Glass beach preview: Screenshot_20261113_221640(1).jpg (945×2048, Liquid Glass selected).
  - Actual iOS reference: RDT_20261112_1627083837676783770026508(20261113-164744).jpg (944×2048).
- Previous candidate #318 / #40 from daf9df4 was confirmed successful. Same-size crop comparison confirms iOS 1 has a more continuous silver body in its dark left stem and top hook; iOS 4/7 have softer internal body scattering while retaining recognizable wallpaper.
- New commit b6a159d586da63e070a4338851d85197feb80e27 — “Refine dark ambient sheen and internal glass scattering”:
  - 1–0 shared gap 0.040→0.044em. Tiny ~one-to-two pixel-scale increment. All other gaps unchanged: 4–7 0.019em; 0–: 0.038em; :–4 0.034em. This is role-based layout and affects Solid and Glass.
  - Dark-region ambient scatter multiplier 0.52→0.70. With the current top gradient 0.34/0.37/0.43, this raises the fully dark interior contribution by roughly +0.06 per channel; it remains gated by local darkness, glyph interior and (1.0-solidMode). No constant overlay on bright beach wallpaper and no Solid material changes.
  - Shared volume scatter 0.085→0.14. Existing near-white protection 1 - 0.42*smoothstep(0.72,0.94,luma) remains, so the contribution is at most about 8.1% on near-white highlights, and about 14% at lit midtones. Internal scatter target is slightly brighter/cooler: microfacetTransmission*1.07 + RGB(0.014,0.018,0.024).
  - No changes to the accepted directional band diffusion from daf9df4 (radius 6.8–10.8px, blend 0.25–0.72), reflections, rim, IOR, spacing roles besides 1–0, fonts, glyphs or Solid shader.
- Production #319 run ID 43469911731 and Digit Lab #41 run ID 43469912000 started from exact SHA b6a159d; CI pending.
- Solid/Frosted preservation: ambient scatter is Glass-only via (1.0-solidMode); new volume-scatter path blends into Glass transmitted sample and is bypassed by existing Solid center-sample mix. The shared 1–0 kerning change intentionally affects both materials.
- User has said beach implementation is improved; preserve it. After new build test, examine the beach Liquid Glass screenshot again to ensure the higher internal scatter does not make it milky.
- Next check: compare dark left side and hook of 1 against reference; examine softer body scatter in 4/7; confirm the tiny 1–0 gap in both Solid/Frosted and Liquid Glass; check beach remains clear. Do not adjust other gaps.
### User clarification: keep typography and optical rendering unified; latest build had no obvious optical delta (13 Nov 2026, ~23:20 IST)
- User explicitly clarified: do not make the 1–0 spacing unique to Solid mode. The first rule remains: all digit layout/spacing must use the same shared layout calculation for both Liquid Glass and Solid/Frosted. Blending/refraction/illumination must also be a unified glyph-independent shader/material model, not special treatment for 0/1/4/7 or separate per-digit paths.
- User says the latest #319/#41 build did not show a visible difference in blur/blending/illumination compared with the prior build; their human eye could not discern an improvement. Do not describe those parameter changes as a proven visual improvement. Treat the optical issue as unresolved and trace why changes are not perceptible before further parameter escalation.
- Source inspection confirms `hourPairGapEm` is consumed by the common `visualGlyphOrigins()` layout function in `rebuildGlyphMask()`, before material selection/rendering. There is no `solidMode` condition in spacing. The same spacing applies to both materials and all hour digit pairs.
- User says 1–0 still looks too close, particularly in Solid/Frosted mode (their screenshots make it easier to see), and wants a small additional increase. New commit `96011622909aa4e771753d146ccedf4f46f0d8ed` — “Increase shared 1–0 kerning for both glass materials” changes the one shared `hourPairGapEm` value from 0.044em to 0.050em. It changes both modes equally; no Solid-only branch or value was introduced.
- All other spacing remains unchanged: 4–7 0.019em, 0–: 0.038em, :–4 0.034em.
- Optical source at `b6a159d` still contains shared directional transmission blending (local wallpaper gradient, radius 6.8–10.8px, 0.25–0.72 mix), 14% max volume-scatter factor, and Glass-only dark ambient sheen 0.70. It is image-driven and glyph-independent; no per-digit special cases. However the user reports no clear visible delta between builds, so this implementation is NOT yet accepted as improved. Inspect final composition/weighting and confirm build path before adjusting again.
- Current build runs from `9601162`: Digit Lab #42 run ID `43473115066` (in progress); Production #320 run ID `43473115188` (queued). Both share the same SHA.
- Next: verify #42/#320; on-device compare 1–0 in both modes; confirm no difference in 4–7 or colon spacing. Then diagnose whether the shared transmission/volume scatter is actually contributing enough to final `body` after compositing, reflections, tint, and lighting. Preserve the improved beach implementation and Solid material; do not introduce a Solid-specific gap or digit-specific optical logic.
### Tiny final 1–0 spacing increment; optical pass still needs stronger perceptible result (13 Nov 2026, ~23:15 IST)
- User tested #42 / #320 screenshots:
  - `Screenshot_20261113_230515.jpg`: diagonal wallpaper, Liquid Glass selected.
  - `Screenshot_20261113_230535.jpg`: same wallpaper, Solid/Frosted selected.
  - `Screenshot_20261113_230549.jpg`: beach wallpaper, Solid/Frosted selected.
- Visual review: beach Solid remains intact. In diagonal Solid/Frosted screenshot, the 1–0 pair still feels slightly too tight; user requests only a tiny additional gap. Latest screenshot set contains no Apple reference image, but prior same-wallpaper references remain in project chat.
- Important build clarification: #42 and #320 are based on `9601162`, which changed only shared typography gap from 0.044→0.050em; shader source is identical to #41/#319. Therefore no optical blending difference is expected between #41 and #42. The user still says the Glass appearance is not at iOS reference level, and the earlier ambient/scatter adjustment has not produced an obvious perceived improvement.
- New commit `a6e3825cacc855180f9b62441c12e38b2d21d653` — “Fine-tune shared 1–0 spacing by a fraction”:
  - Shared `hourPairGapEm` now 0.053em (from 0.050em), an additional 0.003em only.
  - This is the same `visualGlyphOrigins()` shared visible-bounds layout path, used regardless of Solid/Glass material. No Solid-specific logic.
  - All other gaps unchanged: 4–7 0.019em; 0–: 0.038em; :–4 0.034em.
  - Optical shader unchanged in this commit.
- CI from exact SHA `a6e3825`: Digit Lab #43 run ID `43475347715` in progress; Production #321 run ID `43475347682` in progress.
- Code inspection found current `interiorVolume` SDF mask is calculated but unused. Current volume scatter mix is applied to `glassTransmitted` before environment reflection/tint/final colour; its source is near to the microfacet transmission, so its visible effect may be subtle. Current directional diffusion is shared and image-gradient-driven; ambient silver fill is Glass-only and limited by local darkness + `glassProfileCoverage`. Diagnose actual contribution and final composite before blindly increasing numbers again.
- User's non-negotiable unified rule remains: no digit-specific hacks; no Solid-only kerning; common visible-bounds layout for all digit pairs; common glyph-independent glass optical model. Solid and Glass still have legitimate distinct material appearance paths, but shared typography is identical.
- Preserve the accepted beach Liquid Glass result as reference; current beach screenshot is Solid/Frosted, not a new confirmation of Glass beach.
- Next: verify #43/#321; user compares the tiny 1–0 increase. Then continue one intentional optical change at a time based on trace through final compositing. Avoid claiming improvement from parameter edits without a visible comparison.
### User accepts shared spacing and validates consistent rendering across changing times (13 Nov 2026, ~23:30 IST)
- User tested Production #321 / Digit Lab #43 from commit `a6e3825` and supplied multiple live-time screenshots on the same diagonal-streak wallpaper. Test values shown include 10:47, 04:14, 02:59, 07:23, 06:33, 01:50, and 10:50/10:48. These are intentional cross-digit combinations to validate that the same optical treatment holds as the time changes.
- User says the latest shared 1–0 spacing now looks perfect. **Freeze spacing at current values; do not make additional kerning changes unless the user reports a new spacing problem.**
- User explicitly appreciates that no digit was treated uniquely: keep the shared, glyph-independent rendering/layout architecture. Do not introduce per-digit optical exceptions.
- User deliberately did not assess the optical blending/scattering in this round because `a6e3825` was only a tiny shared 1–0 spacing change. Optical quality remains an open task and should be judged in a future build with an actual shader change and same-wallpaper comparison.
- Both workflows for `a6e3825` completed successfully: Production #321 (run ID `43475347682`) and Digit Lab #43 (run ID `43475347715`). Both use the same shared renderer module.
- Keep Solid/Frosted protected. Shared spacing update has now been visually accepted across the two materials; no material-specific spacing.
### Current work to resume after a new chat
### Liquid Glass optical pass — interior scattering and wallpaper-band blending (13 Nov 2026, ~23:45 IST)
### Subtle shared edge-light polish after #45 comparison (14 Nov 2026)
- Latest device-comparison pair is correctly mapped: `Screenshot_20261114_000301.jpg` is our Digit Lab #45 (the controller panel is visible); `RDT_20261112_1627083837676783770026508(20261113-183432).jpg` is Apple's iOS reference (clean lock screen). Preserve this mapping.
- User says the #45 result is closer to the target. Gemini's comparison highlights two small remaining differences: slightly stronger silver-white outer-edge contrast over black, and a brighter directional highlight on angled surfaces such as the upper flag of the 1. The 99% estimate is subjective, not measured.
- User's absolute rule remains: one shared material/lighting model for all glyphs; never write special-case lighting for digit IDs or screen regions. The same SDF normals, geometry gates and wallpaper response must work as the time changes.
- New renderer commit `9651e9c2e53766e4174d4fc5992612ccbb6b4a90` — “Polish unified Liquid Glass edge reflections”:
  - Shared key-light glint multiplier 1.22→1.28 (about +5%).
  - Shared grazing-angle silver rim multiplier 0.18→0.20 (about +11%).
  - Both contributions remain gated by SDF-derived normals and `(1.0 - solidMode)`; Solid/Frosted receives neither change.
  - No digit-specific logic, no typography/spacing changes, no change to diffusion/scattering or beach-wallpaper response.
- Static checks passed: accepted spacing is unchanged (hour pair 0.053em, minute pair 0.019em, 0–colon 0.038em, colon–minute 0.034em); Solid still uses the original 38px blur; both new reflection terms remain Liquid Glass-only; no digit-ID lighting condition was added.
- **Builds for `9651e9c`:** Digit Lab #46 run `43484314847`, currently in progress: [Actions #46](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43484314847). Production #324 run `43484314816`, currently in progress: [Actions #324](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43484314816).
- **Previous build pair:** Digit Lab #45 and production #323 both completed/success from `6a3a20a`. The user has confirmed the #45 optical appearance is closer, but not yet confirmed full parity.
- Next visual check: compare the new build against the same iOS screenshot over the identical diagonal wallpaper. Ensure the small global highlight lift does not create an all-around white outline or over-brighten other glyphs. Recheck beach wallpaper and Frosted/Solid mode. Do not claim parity from CI alone.

### Second unified optical refinement after corrected image comparison (13 Nov 2026, ~23:55 IST)
- Correct image mapping for the latest upload pair: **first image (`Screenshot_20261113_235119.jpg`) is our Digit Lab #44; second image (`RDT_...182239.jpg`) is Apple's iOS reference.** Do not reverse these. The first also visibly includes the Digit Lab control panel; second is the clean lock screen with flashlight/camera/Work controls.
- User's explicit governing rule: one unified optical model for all glyphs. Do not add digit identity, coordinate-region, or per-character branches to improve only 1, 0, 4, or 7. The same geometry-derived response and wallpaper-driven diffusion must apply consistently when time changes. Keep accepted spacing frozen.
- Visual read of #44 vs iOS: ours remains too dark/outline-dominant, with strongly separated wallpaper streaks visible through strokes; iOS presents a brighter and more continuous body with softer internal band transitions. This is still not 1:1.
- New shared renderer commit `6a3a20ad983d6cda0ac7eba0b1f169b0cd7b6eef` — “Strengthen unified Liquid Glass body and diffuse streaks”:
  - Reduces Glass depth-mask saturation distance from 42px to 26px; Solid's selected endpoint remains 13px. Raises the common Glass core volume contribution from 0.72 to 0.90.
  - Widens wallpaper-gradient diffusion radius from 10–15px to 14–20px; far taps now reach about 27.6px across strong bands.
  - Reweights the five-tap transmission kernel towards near/far samples (centre 0.22, near pair 0.21 each, far pair 0.18 each) and raises contrast-adaptive blend to 0.42–0.86.
  - Reduces post-diffusion colour saturation adaptively from 1.48 in ordinary areas to 1.20 where wallpaper band contrast is high. This should avoid re-amplifying white/blue bands after the diffusion step.
  - All these are one shared SDF/image-gradient model; no glyph IDs or screen-coordinate regions are referenced. Typography/spacing unchanged.
- **Solid safety:** Glass interior endpoint is selected only at `solidMode=0`; at `solidMode=1` the endpoint remains the prior 13px. Solid still uses 38px blur and centre wallpaper transmission; new Glass scattering/fill stays blended out. Static source validation passed.
- **Latest candidate builds:** Digit Lab #46 run `43484314847` and Production #324 run `43484314816`, commit `9651e9c`, currently in progress. See the links in the dated note above.
- Do not claim a visual improvement until the user installs/tests the new Digit Lab and compares same wallpaper to the iOS reference. Recheck beach wallpaper afterwards for excessive blur/frosting.

- **Reference image order is explicit:** first image supplied in the 13 Nov 2026 turn is Apple's original iOS clock; second image is our Digit Lab #43 result. Always treat image 1 as the target and image 2 as our current output. Do not reverse them.
- User's spacing acceptance stands: shared typography values are frozen at 1–0 0.053em; 4–7 0.019em; 0–: 0.038em; :–4 0.034em. Do not touch kerning unless user reports a new issue.
- User asked to move on to Liquid Glass blending/scattering for iOS visual parity. Solid / Digit Lab Frosted remains approved and protected. Production Glass / Digit Lab Liquid Glass is the only material being tuned.
- Source inspection of the renderer from #43 revealed `interiorVolume` was calculated from SDF depth but not used; both `glassVolumeScatter` and `ambientInteriorMask` were limited to `glassProfileCoverage`, which describes the rounded edge shoulder and falls off after ~20px. Thus the silver-blue ambient body fill and volume scattering mostly faded before reaching the centre of wider glyph strokes.
- New commit `5ee517eea0cc8c33d9deff2a4356670d7ef862ec` — “Extend Liquid Glass scattering through shared glyph volume”:
  - Combines edge coverage and core depth into one glyph-independent `glassVolumeRegion` using `max(edgeMask, interiorVolume * 0.72)`.
  - Uses that region for both transmitted volume scattering and dark-area silver-blue ambient fill, so the optical body continues into the stroke interior.
  - Widens directional wallpaper-band sampling from 6.8–10.8px to 10–15px (far taps reach about 20.7px) and strengthens high-contrast microfacet blending from 0.25–0.72 to 0.36–0.80. Diffusion is oriented across local wallpaper luminance gradients, and strength remains contrast-adaptive.
  - Raises the Glass volume scatter factor from 0.14 to 0.22 and slightly increases the volume-transmitted lift.
  - No digit identity, glyph location, or per-character logic is added; the same mask and image-gradient model applies to every digit and colon.
  - No typography values changed.
- **Solid safety check:** `solidMode == 1` still selects `center` for transmission, the original 38px blur, the original Solid opacity/lighting/bevel path; ambient scatter remains multiplied by `(1-solidMode)`. The new Glass transmission changes are blended out of the Solid path. Explicit static checks passed.
- **Production build:** #322, run ID `43480428529`, commit `5ee517e`, completed/success; artifact `iOS27-LiquidClock-Android17-debug`, ID `14064324520`, expires 27 Nov 2026: [Actions #322](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43480428529)
- **Digit Lab build:** #44, run ID `43480428502`, commit `5ee517e`, completed/success; artifact `liquid-clock-digit-lab-debug`, ID `14064264490`, expires 13 Dec 2026: [Actions #44](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43480428502)
- These builds are for comparison only. Do not claim iOS parity or a visual improvement until both build successfully and the user compares on-device against the first iOS reference image using the same diagonal wallpaper. Also inspect the beach wallpaper afterwards to ensure the adaptive diffusion has not made it frosted/cloudy.
- Current renderer file: `clock-renderer/src/main/java/com/example/ios27clock/LiquidWallpaperRenderer.kt`.
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

### User comparison and broadened Liquid Glass optics pass (14 Nov 2026, ~00:23 IST)
- User supplied a same-wallpaper, same-time comparison: first image is the Digit Lab rendering, second image is Apple's iOS reference. The comparison is not yet 1:1. The current render has sharper, more separated wallpaper streaks and a more narrow/outline-dominant softbox response; Apple's face reflections read as broader and more continuous.
- The submitted image follows Digit Lab #47's successful run window. #47 includes the same renderer source as #46 (9651e9c); its only relevant intervening code change is the persistent Digit Lab signing-key fix, not a renderer change.
- Follow-up renderer commit f6e61bc4e4e6e24938abc87f76ae0e1fbd6b9efa — “Broaden shared Liquid Glass reflections and soften streaks”:
  - Broadens the shared SDF-normal softbox response from exponent 7.0 / coefficient 0.64 to exponent 5.5 / coefficient 0.52. This increases the mid-angle lobe while reducing its peak; it is intended to make reflected light read as a continuous surface rather than simply making the rim brighter.
  - Expands the contrast-adaptive wallpaper diffusion radius from 14–20px to 16–22px.
  - Rebalances the five-tap diffusion kernel from centre/near/far weights 0.22/0.21/0.18 to 0.16/0.22/0.20 (per near/far tap); total weight remains exactly 1.0. This shifts a little transmission away from the unfiltered centre sample without introducing an opaque/frosted overlay.
  - No digit-specific conditions, no change to accepted typography/kerning, glyph silhouette, 20px digit cap, clock placement, pinch behaviour or wallpaper isolation.
  - Solid/Frosted remains protected: changes are on Liquid Glass contributions before the existing mix(glassTransmitted, center, solidMode) selection or behind (1.0 - solidMode); Solid's 38px blur, centre transmission, opacity, bevel and sheen are unchanged.
- Static source checks passed: all three replacements occurred exactly once; kernel weight sum is 1.0; Solid selection and 38px blur remain present; no digit-index branches were introduced.
- Candidate CI runs from f6e61bc: Production #325, run ID 43487564874; Digit Lab #48, run ID 43487564891. Both were queued at 18:57:21 UTC on 13 Nov 2026; build results and APK artifacts must be checked before sharing install links.
- On-device visual verification is pending. Compare the same diagonal wallpaper and all glyphs; reject any increased streak smearing, cloudy/frosted face, harsh all-around outline or Solid/Frosted regression. This pass is a candidate, not a claimed fix.


### User comparison: wallpaper transmission still too sharp; stronger uniform scattering pass (14 Nov 2026, ~00:33 IST)
- User tested the #48 / #325 candidate from commit f6e61bc and supplied a same-wallpaper comparison. First image is our Digit Lab rendering; second image is Apple's original iOS reference. The user says it is closer, but the wallpaper seen through the digits remains noticeably too sharp.
- Explicitly restated Rule #1: all blending/scattering/material changes must be uniform and glyph-independent. Do not introduce digit ID, glyph-index, coordinate-region, or special-case rules. The design must remain correct for times containing any digits, including ones not present in the screenshot.
- Visual diagnosis: inside the reference glass, high-contrast black/blue/white wallpaper bands merge into broad, soft colour transitions. In our output, the same bands remain legible as separate stripes, especially in vertical/curved stroke regions. The issue is the shared transmissive body/scattering response, not digit geometry, typography or highlight brightness.
- Follow-up renderer commit e3c555f78b6d1098f397610dbdb5293c39cdd8eb — “Increase shared Liquid Glass cross-band scattering”:
  - Expands the contrast-adaptive perpendicular diffusion radius from 16–22px to 24–34px.
  - Rebalances the symmetric five-tap kernel to centre 0.08, each near tap 0.24, each far tap 0.22; total weight remains exactly 1.0. The blur remains image-gradient-oriented across bands, not along them.
  - Raises the high-contrast adaptive diffusion blend from 0.86 to 0.96 while retaining 0.42 in low-contrast areas; preserves the tiny slope modulation (0.96–1.0).
  - Reduces high-contrast transmission saturation from 1.20 to 1.08 (ordinary low-contrast areas remain 1.42) so the diffusion does not re-amplify colour contrast and make the bands look crisp again.
  - Raises restrained volume scattering from 0.22 to 0.28, still using the same wallpaper luminance and white-protection gates.
  - No glyph-specific logic, no geometry, glyph outline, spacing, clock placement, or gesture changes.
  - Solid/Frosted remains protected: Glass scattering feeds the existing mix(glassTransmitted, center, solidMode) selector; Solid retains its 38px blur, centre wallpaper sample, opacity 0.78, bevel and satin sheen.
- Static source checks passed: all edits matched once; kernel sums to 1.0; Solid selector/38px blur/0.78 opacity remain; no digit-index branches exist.
- Candidate CI from e3c555f: Production #326 run ID 43489806556; Digit Lab #49 run ID 43489806575. Both queued at 19:11:17 UTC on 13 Nov 2026. Check completion and artifacts before sharing APKs.
- The user's #48 screenshot is evidence that commit f6e61bc alone did not sufficiently soften the interior transmission. The new pass is not visually verified yet. On device, compare the same diagonal wallpaper; the black/blue/white bands should blend smoothly across all glyphs while preserving the broad reflective glass body. Reject a cloudy/frosted look, loss of wallpaper colour or a uniform blur that ignores local wallpaper direction.

### User comparison and chromatic-edge detection correction (14 Nov 2026, ~00:46 IST)
- User supplied the next comparison after the #49 / #326 build pair. First image is our Digit Lab result; second image is Apple's iOS reference. The user confirms progress, but the same remaining defect persists: wallpaper bands through the glyph body are still too recognisable/sharp, while Apple's image blends black/blue/white into a smooth, continuous internal optical blur.
- The screenshots use the same blue diagonal wallpaper and 10:47. Keep the comparison image order as supplied.
- Source inspection of commit e3c555f identified a missed case in the shared cross-band detector: it measured edge contrast and direction only from RGB-to-luminance dot products. Saturated blue against black can have strong visible RGB contrast but relatively low luminance contrast, causing the shared diffusion to under-detect the blue/black boundary.
- Follow-up renderer commit 7497fdf56eec29dbcb864623ee0f64233227b25f — “Detect chromatic wallpaper edges for Liquid Glass diffusion”:
  - Retains the luminance gradient signs for the 2D edge-normal orientation (symmetric sampling means sign reversal has no impact, but relative component signs preserve diagonal direction).
  - Measures horizontal/vertical edge energy using the full RGB difference magnitude, not only luminance.
  - Drives bandContrast from the RGB edge-energy magnitude with smoothstep thresholds 0.08–0.55. Blue/black and other saturated transitions now activate the same existing 24–34px, high-strength diffusion path as bright white bands.
  - Keeps all existing #49 diffusion kernel weights, 0.96 high-contrast blend, 1.08 high-contrast saturation and 0.28 volume scattering. No extra blur taps or larger radius were added in this pass; the objective is to fix detection before increasing blur magnitude again.
  - No digit-ID, glyph-index, colour-specific or screen-region branch. The same RGB edge detector and optical response apply to every glyph and any time string.
  - Solid/Frosted remains protected; the shared Glass transmission remains selected through mix(glassTransmitted, center, solidMode), and the Solid 38px blur path remains unchanged.
- Static checks passed: RGB gradient magnitudes are used for edge energy; luminance signs remain for direction; existing strong diffusion constants remain; no glyph-specific branches; Solid selector and 38px blur remain.
- CI for 7497fdf was triggered by the renderer change. Record exact Production/Digit Lab run IDs, results and artifacts after checking Actions. Do not call the chromatic detector visually fixed until the user compares the APK.
- Next visual check: inspect all strokes, especially segments where saturated blue meets black as well as white/blue boundaries. The colour bands should be smooth like Apple's reference, but larger wallpaper colours and glass reflections should remain identifiable. Do not let the material become cloudy/opaque like Frosted Glass.


### Dark-glass haze and structure-tensor refinement (14 Nov 2026, ~00:57 IST)
- User tested the #50 / #327 pair from 7497fdf. First screenshot is our Digit Lab rendering; second is Apple's iOS reference. User says the result is very close, but dark glass regions (notably the 7 in this sample) still appear smoky/neutral, and internal band blending remains slightly different.
- Rule #1 reaffirmed: all blending, scattering and material responses must be uniform across all glyphs and all times. No digit IDs, glyph indices, character-specific cases or screen-position branches.
- Diagnosis: ambientScatter at 0.70 mixed dark Liquid Glass interiors toward a fixed neutral slate gradient, which can create a gray veil and suppress blue wallpaper colour. RGB edge magnitudes were also oriented using luminance-gradient signs, which is unstable for saturated chromatic transitions.
- Renderer commit 7e11b7dc2d13dd2bb45909d3ec32565919e6a329 (Refine shared colour-edge diffusion and reduce glass haze): replaces luma-sign orientation with a full-RGB 2D structure tensor and its dominant eigenvector; keeps the 24–34px diffusion radius, up-to-0.96 blend and symmetric kernel; makes the dark-area ambient target 55% locally wallpaper-derived and lowers ambientScatter from 0.70 to 0.42.
- New ambient response is gated by (1.0 - solidMode). Solid/Frosted retains its existing source path, 38px blur, 0.78 opacity, bevel and sheen. No kerning, glyph geometry, layout, gesture or wallpaper-isolation changes.
- Static checks passed: full-RGB tensor/eigenvector, existing diffusion constants retained, ambient environment-colour contribution and 0.42 scatter present, Solid path protections present, no glyph-index branches.
- CI from 7e11b7d: Production #328, run ID 43492951742; Digit Lab #51, run ID 43492951818. Both were in progress when recorded; check final statuses and artifact IDs. Visual verification remains pending until user compares the new Digit Lab APK.
- Next check: confirm dark blue/black regions retain wallpaper hue rather than neutral smoke, while internal black/blue/white bands blend smoothly across every glyph. Reject cloudy/opaque Frosted appearance.


### Digit Lab #51 installation failure — signature/package audit (14 Nov 2026)
- User reports Android displays “App not installed” for Digit Lab #51.
- Compared the actual downloaded APK archives from Digit Lab #50 (artifact 14069862582) and #51 (artifact 14071225562), not just workflow configuration.
- Both APKs have the same V2 signing certificate SHA-256 fingerprint: `05:AE:3C:45:3D:D1:72:8F:82:BD:30:46:08:C3:43:99:08:3E:90:7C:F4:12:74:23:01:5C:1F:B5:58:84:B5:4C`.
- The #51 Actions log confirms a cache hit and restore for `digit-lab-debug-signing-feature/live-wallpaper-big-clock-v1`, says “Restored the existing Digit Lab signing key,” and prints the same SHA-256 fingerprint as #50. Therefore the original per-run signing-key mismatch is not present between these two APKs.
- Decoded both APK binary manifests:
  - Both application IDs are `com.example.ios27clock.digitlab`.
  - #50 versionCode 50, versionName `1.0.50-digit-lab-debug`; #51 versionCode 51, versionName `1.0.51-digit-lab-debug`.
  - Both declare minSdk 37 and targetSdk 37.
- Both workflow jobs completed successfully and uploaded artifacts. This verifies compile/upload, not actual device installation.
- No app source or signing workflow change was made in response to this report because the most likely cause (different #50/#51 certificate) was disproved. Remaining diagnosis requires Android's exact Package Installer failure code. Ask user to run `adb install -r app-debug.apk` from the extracted #51 artifact (or provide the installer details screen) and share the exact `INSTALL_FAILED_...` output. Do not advise uninstalling pre-emptively; it may erase Digit Lab preferences and is not justified by the evidence yet.

### Clarification: target is natural atmospheric haze, not just directional edge blur (14 Nov 2026, ~01:20 IST)
- User clarified the intended iOS reference appearance: the glass should have a consistent, natural fogged-window quality, like bright light seen through a lightly hazy pane. It is not enough to selectively blur individual wallpaper edges or merely soften black/blue/white bands. The whole glass body should have a coherent low-frequency atmospheric softness while remaining transparent, reflective and coloured by the wallpaper.
- Correct interpretation: previous work focused too narrowly on contrast detection and directional cross-band blur. The image should instead read as light scattering through a continuous glass volume: broad local glow, soft low-frequency transmission and gentle, consistent haze across all glyph surfaces.
- The user's 'smoky' comment was not a request to remove all haze; the distinction is unnatural gray/smudgy patches versus Apple's even, luminous atmospheric haze. Avoid neutral gray paint, but do not make the Liquid Glass face crystal-clear/sharp.
- New renderer commit `202f8a84e4ce338a6b8f04e17d9891ef84b37c20` — “Add uniform atmospheric haze to Liquid Glass”:
  - Widens the Liquid Glass isotropic eight-direction environmental blur radius from 22px to 30px. Solid/Frosted retains its original 38px blur exactly.
  - Adds a uniform 16% low-frequency blend from the locally blurred wallpaper into the Liquid Glass transmitted face, gated only by the shared glyph-volume mask. This produces continuous softening throughout the body, not only where local edges are detected.
  - Changes the dark-area ambient environment target from a 55% mix of neutral gray and directional microfacet transmission to a 72% mix of neutral gradient and the same isotropically blurred wallpaper, making the haze naturally pick up local wallpaper colour.
  - Reuses existing eight blur samples; no extra wallpaper texture fetches. No glyph-specific conditions, no position-dependent material, no font/spacing/geometry/gesture/wallpaper isolation changes.
  - Solid/Frosted remains protected: blur radius still resolves to 38px at solidMode=1; ambient and atmospheric scattering remain multiplied/blended out when Solid is selected; original centre wallpaper transmission, 0.78 opacity, bevel and sheen remain unchanged.
- Static checks passed for the new 30/38px blur selection, shared 16% atmosphere blend, wallpaper-colour source, unchanged Solid transmission/opacity, and absence of digit-index branches.
- CI runs from `202f8a8` should be recorded after Actions starts: Production and Digit Lab build numbers, run IDs, results and artifact IDs. On-device verification remains pending.
- Next comparison must judge the whole glass body, not only isolated digits: it should have an even, natural haze like the iOS reference, softened bright wallpaper streaks, retained black/blue colour and continuous silver-blue reflections. Reject a flat opaque/frosted film or patchy gray smoke.

### Atmospheric haze comparison and bright-pass scattering revision (14 Nov 2026, ~01:27 IST)
- User tested Digit Lab #52 / Production #329 from commit `202f8a8` and reports the result still does not show the natural foggy/hazy behaviour visible in Apple's iOS reference. The face continues to read too much like clear refraction plus bright bevels.
- This confirms the 16% isotropic wallpaper blend at a 30px radius was too subtle. The intended material is not merely edge-softened glass: it needs a consistent atmospheric veil across the face, plus gentle spread from bright wallpaper regions, while remaining coloured, translucent and reflective.
- Follow-up renderer commit `7e4670e22b2e6f2687e00bc0fef7aba93c9f4ddd` — “Strengthen natural Liquid Glass haze and bright bloom”:
  - Increases the shared isotropic blend of the existing 30px blurred wallpaper from 16% to 32% across the glyph volume.
  - Derives a bright-pass bloom from the same eight already-fetched blur taps. Each tap is soft-thresholded by luminance (0.22–0.68), then averaged into `glassBrightBloom`; this adds a gentle screen-composited light spread at 18% of the shared glyph-volume coverage.
  - Reuses existing blur samples only; no additional wallpaper texture lookups.
  - No glyph-specific logic, no changes to accepted glyph geometry/kerning, clock layout, or gestures.
  - Solid/Frosted remains protected: the blur radius still resolves to 38px in Solid, atmospheric/bloom contributions only feed the Liquid Glass `glassTransmitted` path, and Solid continues to select `center` through the existing `mix(glassTransmitted, center, solidMode)`; original 0.78 opacity/bevel/sheen retained.
  - Static checks passed for 32% haze, 18% bloom, reuse of existing blur taps, no new texture fetches, unchanged Solid path and no digit-specific branches.
- CI builds from `7e4670e` were triggered automatically. Record Production and Digit Lab run IDs/results/artifacts after Actions reports them. On-device verification is pending; do not claim the new haze looks right until user tests it.
- Next visual check should judge uniform natural fogging and soft glow through the entire glass surface, especially on both bright and dark wallpaper regions. Reject a flat milky/frosted look, loss of wallpaper colour, or glow that appears as isolated white outlines.

### User confirms visible atmospheric-haze progress in Digit Lab #53 (14 Nov 2026, ~01:34 IST)
- User supplied the next same-wallpaper comparison and explicitly said: “YES MAN Finally we are making progress, we aren't there yet but to my human eyes this looks progress.”
- Correct image order: first image is our Digit Lab #53 result; second image is Apple's iOS reference.
- This is the first explicit positive user confirmation that the atmospheric-haze direction is producing visible progress. The 32% isotropic local-wallpaper haze plus 18% bright-pass bloom from commit 7e4670e is materially closer to the intended natural fogged-glass appearance.
- The image comparison still shows remaining difference: Apple's glass has a more continuous and evenly distributed soft luminous haze across the entire glyph body, while our result retains more visible fine wallpaper streak structure and a somewhat more defined/refractive face. Do not interpret the positive feedback as approval or 1:1 parity.
- Builds verified successful for this comparison: Production #330 run 43497528568, artifact 14071328923; Digit Lab #53 run 43497528710, artifact 14071828511; both commit 7e4670e22b2e6f2687e00bc0fef7aba93c9f4ddd.
- Do not immediately make another large scattering increase. Preserve the current #53 state as the positive baseline and continue with a small controlled refinement of continuous atmospheric softness / light spread, preserving wallpaper colour, glass transparency, and reflective contours.
- Non-negotiable: all material/haze/reflection changes are shared and glyph-independent for every numeral/time. No digit-specific, character-specific or screen-coordinate conditions. Solid/Frosted remains protected.

### Pixel-level comparison and controlled atmospheric refinement (14 Nov 2026, ~01:39 IST)
- User supplied a later screenshot and said human eyes could not distinguish it from the prior build, asking for pixel-level analysis. Compared the actual files `Screenshot_20261114_013928.jpg` (Digit Lab) and `RDT_...201000.jpg` (iOS reference), plus the preceding `Screenshot_20261114_013417.jpg` (#53 baseline) and #52 pair.
- Critical capture finding: the clock and wallpaper region of the 01:34 and 01:39 Digit Lab screenshots are pixel-for-pixel identical (MAE 0.0 across x=40..810, y=200..670). Both screenshots are the same #53 renderer output; no renderer commit/build was made between them. This explains the absence of a visible difference between those two captures.
- Comparing #52 (`202f8a8`) to #53 (`7e4670e`) against their identical iOS reference image: raw RGB MAE in the clock crop decreased from 22.775 to 22.577 (0.87%); RMSE changed from 42.095 to 42.147 (slightly higher). Region MAE improvement: 1 0.00%, 0 0.71%, colon 0.57%, 4 0.26%, 7 2.59%. These are raw pixel distances, not pure haze metrics; they include geometry and reflected-highlight differences. They demonstrate that #53's changes were modest and uneven, not a clear global convergence.
- The next pass was chosen as a controlled shared atmospheric refinement rather than digit-specific tuning. Renderer commit `f576082ac7adc3796834cb7b9eb0afabf971cbee` — “Refine uniform Liquid Glass atmospheric softness”:
  - Widens the isotropic wallpaper sample radius used by Liquid Glass from 30px to 36px. Solid remains exactly 38px.
  - Raises the uniform Liquid Glass atmospheric blend from 32% to 38%.
  - Raises bright-pass bloom from 18% to 22% and lowers its soft threshold from 0.22–0.68 to 0.18–0.60 so more blue-white illuminated content contributes softly.
  - Reuses the same eight blur samples; no additional wallpaper texture fetches.
  - No glyph-index or digit-specific logic; no geometry, spacing, clock layout, gesture or wallpaper-isolation changes. Solid/Frosted keeps 38px blur, original centre transmission, opacity 0.78, bevel and sheen.
- Static checks passed: 36/38px blur split, eight threshold updates, 38% haze, 22% bloom, existing Solid path and no per-digit logic.
- Candidate workflows triggered from `f576082`: Production #331 run ID `43499977167`; Digit Lab #54 run ID `43499976917`. Both were queued at time of memory update. Check completion and artifact IDs before sharing download links.
- On-device verification remains pending. Compare #54 against #53 and the exact same iOS reference. Judge uniform haze over all glyphs, especially the dark and blue areas, and ensure the result remains transparent/reflective rather than milky/frosted. Do not claim a visual improvement until tested.

### Clarification on visual evaluation method (14 Nov 2026, after #54 candidate)
- User clarified that “pixel-level difference” was a figure of speech, not a request to produce literal per-pixel metrics or tables. Use visual judgement and image-analysis tools to inspect the actual difference; do not over-focus on MAE/RMSE or region error percentages unless the user explicitly asks for quantified image analysis.
- The key visual delta to target remains: Apple's reference has a broader, more continuous, naturally hazy luminous body and soft light spread inside the numerals. Our rendering still reads more like crisp transparent refraction with sharper wallpaper streaks and more pronounced bright edge/bevel definition.
- The latest screenshot still shows 01:39 in the status bar, which predates the #54 candidate workflow completion; treat it as the #53 visual baseline, not as on-device confirmation of #54.
- #54 / #331 from `f576082` is the next controlled candidate: 36px Liquid Glass environmental blur, 38% atmospheric blend, 22% bright bloom, threshold softened to 0.18–0.60. Both CI builds succeeded (Digit Lab artifact 14072628649; Production artifact 14072479622). Await a genuine post-install #54 comparison before another major shader pass.
- Continue avoiding digit-specific adjustments and preserve approved Solid/Frosted, spacing and gestures.

### Digit Lab #54 comparison: increase was visually too subtle; stronger haze refinement (14 Nov 2026, ~01:50 IST)
- User tested Digit Lab #54 (commit `f576082`) and reports they cannot distinguish a meaningful visual improvement from #53; the rendering still falls short of iOS. The current #54 image confirms that our glass still has sharp internal wallpaper streaks and pronounced bevel/rim highlights, while iOS has a broader, softer luminous haze spread through the glass body.
- Do not answer with another pixel-error table unless explicitly requested. User used “pixel-level” figuratively: they expect visual judgement and practical renderer improvements.
- Follow-up renderer commit `4bbb7ea00c985532b5583b44a20c7d5612cea67b` — “Increase shared Liquid Glass atmospheric softness”:
  - Widens the Liquid Glass isotropic environmental blur from 36px to 44px; Solid remains 38px.
  - Raises the shared atmospheric blend from 38% to 46%.
  - Raises bright-pass bloom from 22% to 30% and softens the threshold from 0.18–0.60 to 0.14–0.52 (all eight existing blur taps).
  - Reuses the current eight wallpaper samples; no added texture fetches.
  - Static checks passed: Liquid Glass/Solid blur split 44/38px; all eight bright thresholds changed; haze 46%; bloom 30%; existing Solid selector and 0.78 opacity remain; no digit-specific branches.
  - This is a deliberate stronger pass after #54 was too subtle, intended to make the material's broad low-frequency light spread more apparent. It is not yet visually verified and must not be described as a proven improvement until the user tests it.
- Candidate runs from `4bbb7ea`: Digit Lab #55 run `43501120779`; Production #332 run `43501120746`. Both were queued when recorded; check completion and artifacts before linking APKs.
- Unchanged hard rule: shared glyph-independent shader for all numbers and times. Do not tune 1/0/4/7 independently. Solid/Frosted protections, geometry, spacing, gestures, wallpaper isolation remain untouched.

### User's Digit Lab #55 assessment — small visible progress over #54 (14 Nov 2026, ~01:57 IST)
- User uploaded the #55 screenshot (`Screenshot_20261114_015725.jpg`) and the same iOS reference, saying: “I think this looks like a tiny bit progress from build 54?”
- I inspected #54 (`Screenshot_20261114_015009.jpg`) and #55 side by side, including aligned clock crops and an amplified difference view. Assessment: yes, there is a small improvement. The #55 face reads slightly softer/more diffused, most visibly in the `0` and some curved interior transmission. It is incremental, not a major step; the `1` and especially `7` still show too much defined internal wallpaper/refraction structure compared with Apple's smoother atmospheric body. The `4` still reads strongly edge-lit, with less broad haze across its face than the reference.
- The #55-to-#54 change pattern is strongest inside the `0` and colon/nearby glass region, with smaller changes to 1/4/7. The user's qualitative impression of tiny progress is consistent with the side-by-side view. Do not overstate the result or claim iOS parity.
- Build verification: Digit Lab #55 run `43501120779` succeeded, artifact `14075135005`; Production #332 run `43501120746` succeeded, artifact `14073852786`; both built commit `4bbb7ea00c985532b5583b44a20c7d5612cea67b`.
- Current recommendation: retain #55 as the latest incremental improvement and do not immediately make another broad blur increase. For the next pass, focus on a more physically coherent volume response—blend low-frequency diffuse transmission with the sharp refractive ray instead of simply increasing blur radius/bloom. Keep highlights integrated with the surface rather than only stronger edge lines.
- Non-negotiable Rule #1: no digit-specific or character-specific behavior. All changes must remain shared across every glyph and any displayed time. Keep Solid/Frosted, typography, spacing, gestures and wallpaper isolation unchanged.

### Shared liquid-volume and reflection-coherence pass (14 Nov 2026, ~02:05 IST)
- User authorised proceeding with the best next implementation toward 1:1 iOS 27 Liquid Glass consistency, using Digit Lab #55 as the last positively assessed baseline.
- Visual reading of #55 versus the iOS reference: our face still reads as crisp refracted wallpaper with separate scattering and bright edge treatment; Apple's face has a more unified volume where broad atmospheric in-scattering and surface reflections feel like one material. Avoid treating this request as a demand for literal numeric pixel comparisons; use visual judgement and only use image analysis as a supporting tool.
- Renderer commit `62b26ddcdecb9c03f317fe698f4d50a39ea6d845` — “Unify Liquid Glass volume scattering and soften reflections”:
  - Consolidates the previous separate 28% microfacet volume-scatter pass and 46% isotropic atmospheric pass into one shared volume-in-scatter source. It blends the directional `volumeScatteredTransmission` with wallpaper-derived isotropic `frostedWallpaper` at 82% isotropic weight.
  - Uses a single atmospheric mix strength of 62% multiplied by the shared glass-volume coverage and a subtle optical-path factor (0.90–1.0) derived from the shared SDF `insideDistance`, so scattering grows smoothly toward the interior of every stroke without per-digit rules.
  - Softens the Fresnel environment reflection by blending 22–30% of the same local blurred wallpaper into the reflection. Crisp directional key/fill glints and bevel highlights are retained afterwards, so edges stay defined while broad face reflections integrate with the haze.
  - Retains the #55 44px Liquid Glass / 38px Solid blur split and the existing 30% bright-pass bloom. No added texture fetches.
  - Static source checks passed: unified volume-in-scatter target, depth weighting, 62% scatter, 22–30% rough reflection blend, crisp glints still present, blur split unchanged, Solid selector unchanged, and no digit-specific branches.
  - Solid/Frosted path remains protected: when Solid is selected, the existing `mix(glassTransmitted, center, solidMode)` still chooses the unmodified centre sample, solid blur remains 38px, and the reflection/glass additions are suppressed by the existing `solidMode` masks. No typography, spacing, glyph geometry, gestures, or wallpaper isolation edits.
- CI runs triggered from this commit: Production #333 run ID `43503417342`; Digit Lab #56 run ID `43503417296`. Both were in progress when recorded; verify workflow results and artifacts before offering APK links.
- This is a new material-model candidate, not a visually confirmed 1:1 fix. The next screenshot must be compared on the same diagonal wallpaper/time. Evaluate whether the whole glyph body gains continuous natural haze, whether sharp streaks blend without becoming flat/opaque, and whether reflections feel integrated rather than separate bevel lines. Preserve #55 as the rollback/reference baseline.

### Digit Lab #56 regression: chroma loss; restore #55 colour-bearing transmission (14 Nov 2026, ~02:13 IST)
- User tested Digit Lab #56 / Production #333 (commit `62b26dd`) and reports the render looks artificial: orange scattering in the `0` is nearly gone; the `4` loses its bluish hue; the `1` loses the earlier hazy feel; the `7` shows haze mostly on its upper arm and looks artificial. This is a real regression versus the #55 baseline.
- Side-by-side visual diagnosis: #56 replaced the separate colour-bearing microfacet scatter + 46% wallpaper haze with a 62% mix whose target was 82% isotropic blurred wallpaper. That makes the transmitted face dominated by spatially averaged wallpaper and suppresses local chromatic energy. It also independently blurred the Fresnel environment reflection by 22–30%, washing out the orange and blue reflection cues a second time.
- Corrective renderer commit `8c80a942aaa2b62b35543e7ffcdabb3ccdf71c37` — “Restore colour-preserving Liquid Glass scattering”:
  - Restores #55's gated 28% directional `volumeScatteredTransmission` contribution, preserving chromatic microfacet energy from the local refracted wallpaper.
  - Restores #55's separate 46% isotropic atmospheric wallpaper mix, rather than replacing it with the #56 62% / 82%-blurred composite.
  - Removes the extra `glassOpticalPath` weighting introduced in #56, which was not needed and contributed to uneven-looking haze as different strokes have different interior-distance distributions.
  - Removes the 22–30% blurred environment-reflection convolution; restores the original colour-bearing `chromaticEnvironmentReflection` so warm orange and cool blue reflection cues survive. Existing crisp key/fill glints remain.
  - Retains #55's 44px Liquid Glass / 38px Solid blur split and 30% bright-pass bloom.
  - Static checks passed: 28% directional volume scatter restored, 46% isotropic haze restored, no 82% blurred replacement, no reflection smearing, chromatic environment reflection restored, 44/38px blur and 30% bloom retained, Solid selector preserved, no digit-specific branches.
  - No change to typography, spacing, glyph geometry, clock layout, gestures, wallpaper isolation, or Solid/Frosted material.
- #56 and Production #333 both compiled successfully before this visual regression was reported (Digit Lab run `43503417296`; Production run `43503417342`).
- CI after corrective commit `8c80a94`: Digit Lab #57 run `43505174420`; Production #334 run `43505174252`. Both were queued at record time; check results/artifacts before sharing installation links.
- Treat #55 as the approved visual baseline until #57 is tested. The aim is to regain its warm/cool colour transmission while preserving the broad atmospheric haze; do not add more global blur or neutral scatter without a screenshot proving benefit.
- Rule #1 remains absolute: material response must be shared/glyph-independent for all digits and any time. No special cases for 0/1/4/7. Solid/Frosted is protected.

### Restore #55 colour character after #56 artificial haze; Digit Lab workflow made manual-only (14 Nov 2026, ~02:13 IST)
- User's #56 screenshot assessment: the orange scattering almost disappears from the 0, the 4 loses its blue hue, the earlier haze on the 1 is gone, and the 7's upper-arm haze looks artificial. #56 is a material regression despite successful compile.
- Root cause in commit 62b26dd: the old colour-bearing directional volume scatter (28%) was removed and replaced with a much stronger 62% mix toward a target that was itself 82% isotropic blurred wallpaper. A second 22–30% convolution of the environment reflection blurred its chromatic colour cues. This over-averaged the face and washed out local orange/blue transmission.
- Renderer rollback/correction commit `8c80a942aaa2b62b35543e7ffcdabb3ccdf71c37` restores the known-better #55 response: separate 28% gated directional microfacet scatter plus 46% isotropic atmospheric wallpaper blend; removes #56's optical-path scalar and reflection convolution; restores original chromatic environment reflection. It retains 44px Glass / 38px Solid blur and 30% bright-pass bloom. Static checks pass; no glyph-specific branches; Solid selector unchanged.
- User instruction from earlier project context: do not automatically trigger Digit Lab builds for routine renderer changes; shared renderer updates flow into Production, and only build Digit Lab when explicitly requested. The Digit Lab workflow had a push trigger on clock-renderer changes, which caused #57 to queue automatically with the rollback commit.
- To prevent future violations, workflow commit `fbe2b89f116b9f0a5ee87cdeef5f0e3143ebeff5` changes `.github/workflows/digit-lab.yml` to `workflow_dispatch` only. Digit Lab will no longer auto-build on renderer pushes; a user-requested/manual trigger is required. The already queued #57 (run `43505174420`) was started before this workflow change and is still running; no cancellation action is available in the current GitHub tool set. Be transparent if relevant.
- Production #334 (run `43505174252`, commit `8c80a94`) was still in progress at last check. Digit Lab #57 run `43505174420` was already in progress due the old trigger. Check Production completion before claiming its APK is ready.
- Visual baseline/rollback target is Digit Lab #55, not #56. Wait for user confirmation of restored warm orange in 0 and blue transmission in 4 before making another material pass. Avoid more global blur or neutral haze; preserve local chroma and build haze as a restrained shared in-scattering term.
- Rule #1: one shared, glyph-independent material response for every numeral and time. No digit-specific adjustments. Solid/Frosted untouched.

### Revert accidental manual-only Digit Lab workflow change (14 Nov 2026, ~02:22 IST)
- User asked why Digit Lab builds had suddenly been made manual. The manual-only change was an incorrect interpretation of an earlier 'don't run builds automatically' preference; it should not have been applied to the Digit Lab test bench.
- User workflow expectation: Digit Lab should automatically build on shared renderer changes so the user can quickly test the updated renderer. Restore the push trigger for `feature/**` changes touching `clock-renderer/**` and Digit Lab/workflow supporting files, while retaining `workflow_dispatch`.
- Workflow correction commit `2940ecbacd8cadd2202c58b8c156b21c16552598` restores the previous automatic push paths plus manual dispatch. Do not make Digit Lab manual-only again unless user explicitly requests it.
- This correction changes only `.github/workflows/digit-lab.yml`; no renderer change. It may itself trigger a Digit Lab run because the workflow file is in the push path filter.

### Digit Lab #57 comparison: continuous haze coverage and polished edge correction (14 Nov 2026, ~02:26 IST)
- User supplied Digit Lab #57 screenshot plus full/cropped iOS reference images. Key visual findings: haze appears unevenly present across glyphs; `1` and `0` have lost/weak atmospheric light wrap, `4` has a blue tint but less polished edge reflection than iOS, orange/white bending around `1` is weak/artificial, and `7` lacks continuous full-body haze (some effect is isolated to its upper arm). The `0` should softly blend surrounding orange/white light, not show only separate sharp streaks.
- Important: the user is not requesting digit-specific fixes. Every material response must stay shared across all glyphs and times.
- Source diagnosis: `glassVolumeRegion` combined `glassEdgeVolume` and `glassCoreVolume` with `max()`. Where both masks are partially active, this creates a coverage trough. Since the volume masks are derived from SDF/edge profile, stroke widths and local shapes expose the trough at different places, producing patchy haze even with one shared shader.
- Renderer commit `463fd43de8c2a0409978eb1d130f62676cba4c34` — “Unify glass volume coverage and broaden softbox edge reflection”:
  - Replaces `max(edgeVolume, coreVolume)` with the smooth union `1 - (1-edgeVolume)*(1-coreVolume)`, removing the valley where both masks are partially active. This should make atmospheric scattering reach all stroke bodies more consistently without character/coordinate-specific conditions.
  - Broadens the shared area/softbox reflection by lowering its power exponent from 5.5 to 4.5 and increasing coefficient from 0.52 to 0.58. This targets a more polished continuous reflection edge rather than a tighter isolated glint. Existing sharp key/fill glints remain.
  - Retains #57 colour-preserving directional volume scatter (28%), shared 46% wallpaper-derived haze, 30% bright-pass bloom, 44px Liquid Glass / 38px Solid blur split and original chromatic environment reflection.
  - Static checks passed: smooth union present; broadened area reflection present; shared haze/bloom and directional colour retained; Solid selection and 0.78 opacity retained; no digit-specific branches.
  - No glyph geometry, typography, kerning/spacing, positioning, pinch/drag or wallpaper-isolation changes.
- Candidate CI from this commit: Digit Lab #59 run `43508138603`; Production #335 run `43508138426`. Both were in progress when recorded. Verify build outcomes and artifacts before linking.
- This pass is intended to fix mask continuity rather than blindly increase blur. User testing is required to verify that `1`, `0`, `4`, and `7` gain a consistent natural haze, the orange/white and blue colours remain rich, the `4` edge looks more polished, and the result does not become frosted/opaque.

### Digit Lab #59 review: `4` edge improved; remaining light wrap needs chromatic-energy detection (14 Nov 2026, ~02:47 IST)
- User reports #59: `4` has improved, though it still lacks the soft hazy quality; the `1` and `0` still lack natural orange/white light wrap, and `7` remains insufficiently hazy. The screenshot confirms the edge refinement is visible mainly on `4`; other glyphs retain a more sharply refracted/outlined look.
- Diagnosis: several shared scattering/bloom gates use luminance (`dot(rgb, 0.2126/0.7152/0.0722)`) to decide whether environmental light is present. This under-detects saturated blue (blue has low luma weight) and can under-trigger warm saturated colour compared with neutral white. The result is uneven atmospheric response even though the shader is shared.
- New renderer commit `d403a82b9e2d5663f666f67eefdc96fd7876f57d` — “Use chromatic energy for shared glass light scattering”:
  - Changes all eight bright-pass soft thresholds from luma to peak RGB channel energy (`max(r,g,b)`), so orange, blue and white sources can all contribute to the same bloom response while retaining their original RGB colour.
  - Changes the nearby-wallpaper light gate for bounce from blurred wallpaper luminance to peak RGB energy.
  - Changes the directional microfacet in-scatter activation from microfacet luminance to peak RGB energy, while retaining the luminance-based near-white protection so highlights do not turn into a milky wash.
  - Keeps smooth-union volume coverage and the #59 4-edge softbox improvement (exponent 4.5 / coefficient 0.58), #55 colour-bearing directional scatter, 46% atmospheric blend, 30% bloom, 44px Liquid Glass / 38px Solid blur split.
  - Static checks passed for all eight RGB bright-pass gates, chromatic bounce/volume gates, retained white guard, smooth union, 4-edge reflection, unchanged Solid path, and no digit-specific branches.
  - No glyph-specific tuning, geometry, typography, spacing, layout, gestures or wallpaper-isolation changes.
- Candidate CI: Production #336 run `43509973595`; Digit Lab #60 run `43509976236`; both built from `d403a82b9e2d5663f666f67eefdc96fd7876f57d` and were in progress at record time.
- User-facing target: make the `1` and `0` regain soft orange/white light wrapping, preserve the polished `4` edge and blue hue, and give the `7` a consistent blue atmospheric volume. Do not increase global blur blindly. After testing #60, check that color response is richer without clipping/oversaturation or milky haze.
- Rule #1 remains absolute: one shared material response for every glyph/time. No digit-specific conditionals. Solid/Frosted remains protected.

### Digit Lab #60 visual review — no meaningful improvement over #59 (14 Nov 2026, ~02:55 IST)
- User supplied Digit Lab #60 screenshot `Screenshot_20261114_025558.jpg` (keyboard open; clock area remains visible) and asked whether there was any improvement over #59. Compared against #59 `Screenshot_20261114_024713.jpg` at the same 945×2048 resolution and aligned wallpaper strip; no global translation was needed.
- Visual judgement: the two builds are nearly indistinguishable at normal viewing size. The RGB-energy change causes small local pixel changes mostly in the `0`, colon and `7`, but there is no convincing improvement to the material overall. The `1` is essentially unchanged, and the `4` has no meaningful new change. This matches the user's inability to spot progress.
- The iOS reference crop compared with #60 still shows a clear gap: Apple's `0` has much broader warm orange/white light wrap and smooth internal glow; the `4` has a more integrated, polished cyan/silver surface; the `7` has a softly illuminated translucent body instead of primarily sharp blue refraction; the `1` has more natural orange/white light bending and spread. Our render still reads more like a sharply refracted glass outline with narrow highlight bands.
- Quantitative supporting check (not the user's requested deliverable, only diagnostic): in fixed glyph bounding boxes, mean absolute RGB change from #59 to #60 is small: `1` MAE 0.455; `0` 1.455; colon 1.353; `4` 0.199; `7` 0.816 on 8-bit RGB. More than 8 intensity levels changed in only 0.62% of the `1`, 5.73% of the `0`, 6.68% of the colon, 0.07% of the `4`, and 4.13% of the `7` pixels. This confirms #60's change barely affected the main clock and was unevenly localized. Treat these numbers as internal diagnostics, not user-facing quality scores.
- Build verification: Digit Lab #60 run `43509976236` succeeded; artifact `14078301333`. Production #336 run `43509973595` succeeded. Both use renderer commit `d403a82b9e2d5663f666f67eefdc96fd7876f57d`.
- Conclusion: user is correct; there is no meaningful perceptual improvement #59→#60. Do not claim otherwise or increase thresholds/blur blindly.
- Next renderer direction: investigate why bright-pass/volume gates only perturb small regions. The current shader still has multiple sequential mixes and luma-gated factors (`glassDarkness`, `scatterWhiteProtection`, `ambientDarkness`) after switching some detectors to RGB energy. Need a shared colour-preserving light transport model that smoothly mixes direct refracted colour with low-frequency diffuse/bloom response, preserving warm orange and cool blue energy. Inspect where colour energy is clipped/suppressed and ensure haze coverage across entire stroke bodies; do not add digit/coordinate cases.
- Rule #1 remains absolute: one shared glyph-independent material for every digit and time. No special treatment of 1/0/4/7. Solid/Frosted, geometry, typography, gestures and wallpaper isolation remain protected.

### Structural Liquid Glass light-wrap pass (14 Nov 2026, ~03:05 IST)
- User authorised making whatever changes are necessary to reach the iOS 27 reference. Treat this as permission for substantive renderer improvements, but preserve existing branch/build workflow and the established shared-glyph rule.
- Reviewed #59/#60 and the full AGSL composition. Confirmed a structural limitation: `main()` immediately returned transparent when `uGlyphMask` coverage was below 0.002, and the renderer only drew `glyphBaseBounds`. Therefore no true light wrap or bloom could ever extend outside the glyph silhouette, regardless of how much in-mask scattering/bloom was tuned. This helps explain why the clock continued to look like sharp refracted strokes rather than glass with surrounding luminous spill.
- The current pipeline also derives `glassProfileCoverage` from `insideDistance=max(signedDistance,0)` without a sign gate. Outside pixels (once evaluated for halo) would falsely appear to have full profile coverage. This would pollute volume-scatter calculations unless corrected.
- Renderer commit `c38445b2d05d70026e8c9b97bfa142712bf25f5f` — “Add chromatic light wrap beyond the Liquid Glass silhouette”:
  - Removes the immediate outside-mask return for a bounded 18px SDF exterior zone; Solid/Frosted still returns transparent outside the glyph and receives no halo.
  - Uses the existing padded SDF to derive outside distance. The existing SDF has 24px range and 28px padding, sufficient for the 18px wrap.
  - Expands the shader draw rectangle by 18px and the saved clock layer by 18px multiplied by active preview scale, so the new external glow is not clipped. No glyph mask/SDF bitmap dimensions or layout geometry are changed.
  - Adds a low-alpha (max 0.14) halo outside the glyph. It uses the existing bright-pass blur colour (72%) and cross-band microfacet transmission (28%), is activated only by nearby bright RGB energy, falls off exponentially (7.5px) and reaches zero by 18px. Thus warm orange/white and cool blue colours can wrap the contour instead of a generic neutral/white drop shadow.
  - Gates the existing profile/edge volume mask with `smoothstep(-0.65, 0.75, signedDistance)` so outside SDF pixels do not masquerade as fully covered glass volume while the halo is evaluated.
  - Reuses existing bright-pass and microfacet samples; no additional wallpaper texture lookups. No digit-specific rules, no special coordinates, no changes to typography/spacing, glyph geometry, gestures, wallpaper selection/occlusion or tint controls.
  - Solid/Frosted protections remain: outside SDF is rejected when solidMode > 0.5; solid blur/opacity/bevel/sheen and centre wallpaper path remain unchanged; halo alpha is multiplied by (1-solidMode).
  - Static validation passed for outside SDF activation, Solid halo gating, signed profile gate, chromatic halo source, expanded drawing/layer bounds, retained Solid selector, and absence of glyph-specific logic.
- CI from `c38445b`: Production #337 run `43512025413` (in progress); Digit Lab #61 run `43512025439` (queued) at note time. Verify actual completion/results/artifacts before sharing APK links.
- This is a structural, first-principles attempt to address the missing luminous spill, not a visually verified iOS-parity fix. Next screenshot should be checked for natural light wrap just outside bright edges, retained interior orange/blue hues, no white outline, no large drop shadow, no glyph-to-glyph inconsistency, and no Solid/Frosted regression. Keep #59 as previous approved visual reference until #61 is tested.

### Digit Lab #61 visual review and two-scale colour-preserving transport (14 Nov 2026, ~03:10 IST)
- User supplied Digit Lab #61 screenshot `Screenshot_20261114_031007.jpg` and original iOS comparison `RDT_20261112_1627083837676783770026508(20261113-214225).jpg`. Both are mounted/current-turn images; screenshot first, iOS reference second.
- User explicitly sees a natural hazy effect beginning on the `4`, but says the other glyphs still need work. Visual inspection agrees: #61 has a softer, more atmospheric cyan/blue face and edge around `4`; the `1` and `0` still show more isolated narrow wallpaper bands and lack the warm/white light spread visible in Apple's `1` and `0`; `7` remains more like a clean blue refractive outline than a continuously hazy glass body.
- #61 was built from renderer commit `c38445b2d05d70026e8c9b97bfa142712bf25f5f`; Digit Lab run `43512025439` succeeded, artifact `14079356070`; Production #337 run `43512025413` succeeded, artifact `14078737994`.
- Structural reason from source: #61 added a real outside-silhouette halo, but the interior transmission still mixed toward a single broad `frostedWallpaper` sample at a fixed 46%. The directional microfacet transport and low-frequency haze were being treated as sequential overlays, and the same amount did not adapt to high-contrast wallpaper bands. This can make blue-white `4` look hazy while the red/orange/black band structure in `0` and `1` remains too defined.
- Follow-up renderer commit `809568cc6575f10781e26cb1aa04365b5f0f2d46` — “Balance colour-preserving diffusion with shared atmospheric haze”:
  - Creates a two-scale diffuse transport target by blending the broad isotropic wallpaper haze (68%) with the existing cross-band microfacet transmission (32%). This keeps the local orange/blue light transport in the haze instead of sending the face toward blur alone.
  - Makes the common atmospheric scattering amount image-adaptive: 46% in low-contrast regions, rising smoothly to 58% in high-contrast band regions using `bandContrast`. This targets the remaining sharp wallpaper streaks without digit-specific tuning.
  - Raises the bright-pass screen/bloom contribution smoothly from 30% to 38% only in high-contrast regions, so bright colour energy can spread more naturally around bands while keeping direct chromatic transmission.
  - Retains #61's real external light-wrap, signed-distance gating, 18px radius, 14% maximum alpha, colour source, smooth-union volume mask, 4-edge softbox improvement, original chromatic environment reflection, and Solid/Frosted selector.
  - Static validation passed: two-scale diffuse target; adaptive 46–58% haze; adaptive 30–38% bloom; shared volume coverage; external halo retained; chromatic reflection retained; Solid selector preserved; no glyph-index branches.
  - No changes to glyph geometry, typography, spacing, clock placement, gestures, wallpaper isolation or preference schema. No new texture reads.
- CI from `809568c`: Digit Lab #62 run `43513422665`; Production #338 run `43513422623`; both picked up the same commit and were in progress at note time. Check final status and artifacts before sharing.
- Next visual test should focus on whether `1` and `0` regain orange/white wrap and have their black/blue/white bands softly blended, whether `7` develops a hazy body from top to bottom, and whether `4` retains its improved atmospheric cyan edge without becoming washed out. User visual verification pending.

### Digit Lab #62 comparison: progress on 0/4/7; flat-face reflection gap on 1 and upper 0 (14 Nov 2026, ~03:21 IST)
- User supplied Digit Lab #62 screenshot `Screenshot_20261114_032147.jpg` and an updated iOS reference screenshot `RDT_20261112_1627083837676783770026508(20261113-215524).jpg`. Compared with #61 `Screenshot_20261114_031007.jpg` using aligned clock/glyph crops.
- Visual assessment agrees with user: #62 is a genuine improvement over #61. `0` now shows more warm orange transmission (most visibly in the lower/interior region); `4` has visibly softer, more natural cyan haze; `7` has a somewhat more continuous hazy body. `1` is nearly unchanged. The upper half of `0`, where the closely spaced white bands cross, still preserves too much of their crisp structure.
- Compared with iOS: the `1` needs broader chromatic/orange-white light bending across its flatter face; the upper `0` needs those paired white bands to merge into a smoother internal glow; `4` still has some difference in softbox/reflection polish; `7` is improved but still sharper and less evenly hazed than the reference.
- Both #62 builds succeeded from commit `809568cc6575f10781e26cb1aa04365b5f0f2d46`: Digit Lab #62 run `43513422665`, artifact `14079356070`; Production #338 run `43513422623`, artifact `14078737994`.
- Follow-up renderer commit `ec1313ba51675a49a80ee2555fdd524bd13d88e6` — “Improve broad chromatic fill and close-band haze response”:
  - Raises the shared low-slope chromatic environmental reflection floor from 0.035 to 0.065 and maximum from 0.20 to 0.22, so flatter glass faces can pick up broad warm/cool environmental reflections instead of relying almost entirely on curvature/surface slope.
  - Makes chromatic reflection pickup activate earlier (threshold 0.018–0.12 from 0.025–0.16), retaining channel hue while improving subdued orange/blue environmental reflections.
  - Widens the shared local colour-edge probe from 4px to 6px to improve detection of closely spaced high-contrast wallpaper bands that can alias at the shorter probe distance.
  - Moves adaptive haze response threshold from smoothstep(0.18, 0.72) to smoothstep(0.10, 0.55), allowing medium-strength paired bands to receive the stronger end of the existing 46–58% atmospheric transport and 30–38% bloom range.
  - No increase to global blur radius, no extra texture reads. Retains #62 two-scale diffuse transport, external 18px chromatic light wrap, smooth-union volume, #59 softbox improvement, and the full colour-preserving transmission path.
  - Static checks passed for all changes, unchanged Solid/Frosted blur/centre selection/opacity, and no digit-specific logic.
- Candidate builds from `ec1313b`: Digit Lab #63 run `43515211106`; Production #339 run `43515211091`. Both were in progress when recorded. Check actual status/artifacts before linking APKs.
- This next pass is intended to improve the shared light response on flatter strokes and paired-band transitions. User verification remains pending; check whether `1` picks up more natural orange/white reflection and the upper `0` softens without washing the `4` or making the `7` cloudy.

### Digit Lab #63 regression and recovery commit (14 Nov 2026)
- User supplied #63 screenshot `283960.jpg`. Compared its glyph crop with the previous #62 screenshot `Screenshot_20261114_032147.jpg` and iOS reference `RDT_20261112_1627083837676783770026508(20261113-215524).jpg`.
- User's assessment is correct: #63 does not deliver a significant improvement. The upper half of `0`, where the paired white bands cross, looks more fragmented/irregular rather than softly merged. `1` still shows little broad orange/white environmental reflection. `4` retains some haze but its edge polish is still behind iOS; `7` has blue transmission but not a continuous atmospheric body.
- Root-cause hypothesis: widening the local RGB structure-tensor probe from 4px to 6px changes the local principal-axis estimate over closely spaced diagonal wallpaper bands. The resulting direction/contrast response can vary across adjacent pixels, producing visibly irregular cross-band sampling. The lower haze threshold also pushes more regions into maximum directional diffusion, exaggerating the unstable axis. The reflection-floor changes were multiplied by the existing horizontal highlight and did not produce a visible flat-face improvement.
- Recovery commit `1cbbc9013ce11f39c6677a9eba36f712adb3df1f` — “Restore stable band sampling after Digit Lab 63 regression”: restored 4px structure-tensor probe, restored haze threshold smoothstep(0.18, 0.72), restored chromatic reflection floor 0.035–0.20 and chroma pickup threshold 0.025–0.16. Retained the #62 two-scale transmission, 18px external light wrap, #59/#61 optical work, and common glyph-independent material path.
- Static checks passed: baseline probe/haze/reflection values restored, two-scale transport and light wrap retained, no digit-specific logic.
- #63 builds completed successfully but are visually rejected: Digit Lab #63 run `43515211106`, artifact `14080017985`; Production #339 run `43515211091`, artifact `14079658603`. Do not present them as recommended builds.
- Recovery commit was pushed to `feature/live-wallpaper-big-clock`; branch head verified as `1cbbc9013ce11f39c6677a9eba36f712adb3df1f`. No new workflow run appeared in the latest Actions listing at time of note; verify CI before sharing a recovery APK.
- Next actual improvement should not merely increase blur or probe radius. Stabilize the structure-tensor direction over paired diagonal bands (e.g. confidence-gate the anisotropic axis and fall back toward the isotropic two-scale transport when the tensor eigenvalue separation is weak), then add broad environment-colour pickup to flat faces independently of narrow specular highlight gating. Test with the same wallpaper and compare glyph crops before calling it progress.

### Digit Lab #63 rejection, #64 recovery and coherence-based diffusion pass (14 Nov 2026, ~03:54 IST)
- User tested #63 and reported no meaningful improvement; the paired white bands through the upper `0` became fragmented/messy. Recovery commit `1cbbc9013ce11f39c6677a9eba36f712adb3df1f` restored the #62 shader values: 4px structure-tensor probe, original low-slope reflection settings and original adaptive haze threshold. Digit Lab #64 run `43519439883` succeeded (artifact `14081868028`); Production #340 run `43519440096` succeeded. Both used `1cbbc90`.
- User authorised continued work toward the iOS 27 reference. Preserve existing build workflows/branch, and use #62/#64 recovery as the baseline. Do not repeat the #63 6px probe or its reflection/haze threshold changes.
- Follow-up renderer commit `f8a480e340f1d506106b5d9fbd75ced29bb0a242` — “Stabilize shared directional diffusion with coherence fallback”:
  - Derives local orientation confidence from the full-RGB structure tensor coherence, `(lambdaMax-lambdaMin)/(lambdaMax+lambdaMin)` represented as `tensorDiscriminant / tensorTrace`.
  - Uses a smooth confidence transition (`smoothstep(0.14, 0.58, coherence)`) to blend the directional cross-band microfacet sample with an isotropic fallback derived from `mix(center, frostedWallpaper, 0.46)`.
  - Keeps a 34% directional contribution even at low confidence, preserving chromatic structure while allowing ambiguous/crossing/multi-edge areas to use more stable isotropic transmission. This is intended to reduce unstable direction flips and broken-looking paired bands without simply widening the probe or adding blur.
  - The same adjusted `microfacetTransmission` is consumed by subsequent volume scattering, atmospheric transport and light-wrap colour; no glyph-specific routing.
  - Keeps the #62 4px probe, 24–34px directional diffusion radius, 46–58% adaptive atmosphere, 30–38% bloom, #61 external 18px colour light wrap, #59 smooth-union volume/softbox edge, and original orange/blue chromatic reflection behaviour.
  - No additional wallpaper texture fetches; no geometry, typography, spacing, clock placement, gesture or wallpaper source changes.
  - Solid/Frosted remains protected: 38px blur, centre wallpaper transmission selector, 0.78 opacity, bevel/sheen and existing solid-mode gates unchanged.
  - Static checks passed for tensor-confidence derivation, isotropic fallback, 34% directional floor, unchanged 4px probe, retained #62 haze, smooth-union/halo, Solid selector/opacity, and absence of digit-specific branches.
- Candidate workflows from `f8a480e`: Digit Lab #65 run `43519827879`; Production #341 run `43519827881`. Both started on the same commit and were in progress when this note was written. Verify final conclusions and artifacts before sharing APK links.
- Visual hypothesis, not yet proven: coherent single-direction bands retain the directional transmission; ambiguous double edges/crossings blend toward stable isotropic colour transmission. Check especially the upper `0` paired white bands, the orange/white wrap on `1`, and preservation of the #62 improvements in `0`, `4` and `7`. Keep #64 as recovery point unless device comparison confirms improvement.
- Absolute Rule #1 remains: one shared, image/SDF-driven optical material for every glyph and time. No digit-specific or screen-coordinate exceptions.

### Digit Lab #65 visual feedback and high-contrast paired-band correction (14 Nov 2026)
- User supplied the Digit Lab #65 screenshot and reported: haze is present, but the black/white transitions in `0` and `1` do not blend softly like the iOS reference and look messy; asked to compare remaining glyphs with #64.
- Visual assessment: `4` retains the soft haze and appears improved; `7` has blue transmission but haze is not uniform. The paired white/black rails through `0` and orange/white transition in `1` remain the main mismatch. Do not claim #65 solves these.
- Digit Lab #65 run `43519827879` succeeded from `f8a480e`; Production #341 run `43519827881` also succeeded. Digit Lab artifact `14081938146` is available. #65 adds coherence-based isotropic fallback, but the screenshot shows that a high-coherence, high-contrast pair can still receive too much directional transmission.
- Follow-up commit `8977b7fcc6d85dd6df6813969d9ff1970d170055` — “Soften high-contrast paired bands in shared glass transport”: multiplies directional confidence weight by a smooth high-contrast suppression (`mix(1.0, 0.62, bandContrast)`). This selectively lets more colour-preserving isotropic environment transmission participate where high-contrast paired rails are present, without changing probe radius, diffusion radius, atmospheric haze, bloom, light-wrap, typography, geometry or glyph-specific behaviour.
- Static validation passed: coherence confidence and isotropic fallback retained; same 4px probe and 24–34px directional radius; #62 haze and #61 light-wrap retained; Solid/Frosted selectors, blur and opacity unchanged; no digit-specific branches.
- Candidate build expected from `8977b7f`: Digit Lab #66 and Production #342. Check Actions status and artifact before sharing. Compare especially the paired bands through `0`, orange/white blend on `1`, and preserve the soft `4`/blue `7` response. If high-contrast softening washes out colour or weakens edge definition, revert only this multiplier to `1.0`.
- #64 remains the visual recovery baseline; #65 is a useful comparison candidate, not a confirmed overall improvement. Device screenshots—not CI success—determine acceptance.

### Digit Lab #66 visual review and stronger paired-band fallback (14 Nov 2026, ~10:06 IST)
- User supplied `283972.jpg` as Digit Lab #66 and `283969.jpg` as Digit Lab #65. I aligned their clock crops and inspected them side by side, then compared #66 with the iOS reference `RDT_20261112_1627083837676783770026508(20261113-215524).jpg`.
- User confirms a small but visible improvement: more blending of orange, blue and black in the `0`. Visual comparison agrees that the change is subtle and localised. The `0` shows slightly more mixing, but the paired white/black rails in its upper half still look more distinct/messy than Apple's smoother internal transition.
- `1` is essentially unchanged from #65 and remains too dark/sharp, lacking the broad warm orange/white internal light spread in Apple's reference. `4` and `7` are largely unchanged by #66; they retain prior improvements but still differ from Apple: `4` is less softly polished across its face, and `7` has less continuous interior haze.
- Compared with iOS: Apple's `1` carries broader silver/orange bending inside its face; Apple's `0` blends the paired black/white/orange bands more naturally while retaining colour; Apple's `4` has a softer cyan luminous body and polished edge; Apple's `7` has a more uniform deep-blue atmospheric body. Our #66 remains comparatively sharper/outlined outside the modest `0` improvement.
- Digit Lab #66 run `43556974531` succeeded, artifact `14098757729`; Production #342 run `43556974406` succeeded. Both used commit `8977b7fcc6d85dd6df6813969d9ff1970d170055`.
- New follow-up renderer commit `7d98ec2005abf576c87198521fca3b7b7b51ade9` — “Deepen paired-band diffusion while preserving wallpaper colour”:
  - Increases the isotropic fallback's blend toward the existing broad wallpaper sample from 46% to 52%, preserving 48% of the local centre colour in that fallback.
  - Reduces the directional ray-bundle weight in high-contrast band areas from 62% to 46%, with a smooth ramp from 100% to 46% as `bandContrast` rises. This gives paired black/white rails more broad colour-preserving diffusion without changing the sample probe, adding blur radius, or applying coordinate/digit-specific logic.
  - Keeps the 4px structure-tensor probe and 24–34px cross-band sampling radius unchanged; no added wallpaper texture lookups.
  - Retains the #62 adaptive haze and bloom, #61 external 18px colour light wrap, smooth-union volume coverage, original chromatic reflections and Solid/Frosted isolation.
  - Static checks passed for 52% fallback, 46% high-contrast directional weight, unchanged 4px probe/radius, existing haze/halo, smooth union, protected Solid selector/opacity, and no digit-specific branches.
- Candidate CI from `7d98ec2`: Digit Lab #67 run `43557969174`; Production #343 run `43557969049`; both started on the same commit and were in progress at note time.
- Visual hypothesis pending: stronger broad fallback should further merge upper-`0` paired bands while retaining orange/blue hue. Check whether `1` gets any better response where high-contrast wallpaper crosses its stroke; `4`/`7` must not become washed out. If `1` stays unchanged, next inspect shared low-slope chromatic reflection and interior light pickup rather than adding more general blur.

### Digit Lab #67 review and stronger #68 candidate (14 Nov 2026)
- User supplied `/mnt/data/283979.jpg` as Digit Lab #67. Compared the clock crop against `/mnt/data/283972.jpg` (#66) at identical dimensions. Side-by-side crop shows no meaningful perceptual improvement: the clock is effectively visually identical; any difference is too subtle to count as a useful build-to-build gain. #67 did not visibly resolve the upper paired white rails in `0` or add the missing warm light transport in `1`.
- #67 build succeeded: Digit Lab run `43557969174`, artifact `14098739656`; Production #343 run `43557969049`; both use commit `7d98ec2005abf576c87198521fca3b7b7b51ade9`.
- #67 shader's stronger fallback did not create visible impact. Likely the previous 52% isotropic fallback / 46% directional cap is still too conservative once combined with existing transport and final haze. Avoid claiming a visible gain without a real side-by-side result.
- New #68 renderer candidate commit `764af2b3a301608ae8491a82a3a7fc0c5e2c82aa` — “Strengthen paired-band haze for clearer liquid-glass blending”: increase isotropic fallback's broad wallpaper blend from 52% to 62%; reduce high-contrast directional contribution cap from 46% to 32%. This is intended to produce a clearly testable difference in cross-band blending, not another imperceptible micro-adjustment.
- #68 preserves 4px tensor probe, 24–34px sampling radius, existing haze/bloom, external light wrap, Solid/Frosted separation, and glyph-independent image-driven behavior. Static validation passed. This stronger move carries some risk of over-softening / colour wash; assess orange/blue saturation and `4`/`7` sharpness carefully. If it washes out, roll back rather than stacking more blur.
- Candidate #68 CI should be started automatically by the push; record actual Digit Lab/Production run IDs and completion status before sharing artifacts.

### Digit Lab #68 visual review and #69 candidate (14 Nov 2026)
- User supplied `/mnt/data/283982.jpg` for #68. Compared the clock crop directly against #67 `/mnt/data/283979.jpg` at identical dimensions. #68 is still a small rather than decisive visual gain: user correctly noticed a little more yellow/orange energy entering the `0`; the paired white rails in `0` remain too distinct, `1` still does not blend orange/white light naturally, and `4`/`7` do not yet show the broad soft atmospheric haze of the iOS reference. Do not oversell #68.
- #68 Digit Lab run `43558588630` and Production run `43558588619` both completed successfully on commit `764af2b3a301608ae8491a82a3a7fc0c5e2c82aa`. Artifact `14099081651` (`liquid-clock-digit-lab-debug`, 9.6 MB) was downloaded and confirmed to contain `app-debug.apk`.
- #69 candidate commit `b6b95e89a133923292e3fb34f781f98de27a74f4` — “Increase colour-bearing atmospheric glass transport”. The #68 final glass transport path mixed only 32% of cross-band microfacet transmission into broad diffuse transport and atmospheric scatter ranged 0.46–0.58. #69 raises those to 40% and 0.52–0.66 respectively, while keeping bloom unchanged. This is a shared image-driven shader response, not digit-specific tuning. Aim: move more warm/cool colour through the full digit body while softening paired rails; watch for colour wash and loss of edge definition.
- #69 CI: Digit Lab run `43559669827`; Production run `43559669792`; both were `in_progress` at the time of this note. Check their final status and artifact before sharing.

### Digit Lab #69 assessment and #70 atmospheric diffusion pass (14 Nov 2026)
- User tested #69 (commit `b6b95e89a133923292e3fb34f781f98de27a74f4`). They observed a little more white/orange/black blending in the upper part of `0`, but not much elsewhere. This matches the intended modest change; #69 is not a major visual milestone. Digit Lab run `43559669827` and Production run `43559669792` both succeeded.
- #70 commit `2767e7643e75a25571ce11777d5632ae015f198a` — “Increase broad atmospheric diffusion in liquid glass”. The shader's broad wallpaper diffusion sample is already 44px for Glass, but final diffuse transport was still 40% microfacet transmission. #70 lowers that contribution to 22% (thus increasing the blurred, colour-bearing wallpaper share) and raises image-driven atmospheric scatter from 52–66% to 58–70%. This is intended to soften the interior across all glyphs while preserving colour and the outer bevel/reflections; bloom is unchanged.
- Explicit checks confirmed the `solidWarp`, `solidMode` material selection, Frosted/Solid path, and existing bloom expression remain unchanged. Only the Liquid Glass final transport expressions were modified.
- #70 CI runs: Digit Lab `43560936211`; Production `43560936188`; both queued when this note was written. Wait for successful completion and inspect the resulting APK artifact before sharing.

### Production comparison #70 and wider-scattering pass #71 (14 Nov 2026)
- User tested the production app and Digit Lab with the same shared renderer. The production Font & Color screen shows 10:56 while Digit Lab shows 10:47; the material response itself is essentially the same. Production colour tint/scattering can mask the material slightly but does not fix the underlying optical response.
- User's production screenshot confirms the shared renderer issue: `0` and `1` still show conspicuous distorted black/white rails rather than soft continuous in-volume blending; `4` remains the most convincing digit, with some haze but not the soft natural body of the iOS reference. The same material response is visible on additional glyphs (`5`, `6`), confirming it is not a per-digit mask issue.
- Digit Lab #70 and Production #347 from `2767e7643e75a25571ce11777d5632ae015f198a` both succeeded (runs `43560936211` and `43560936188`). #70 lowered the microfacet share in final diffuse transport from 40% to 22% and increased atmospheric scatter to 58–70%; this was a controlled but insufficient step.
- #71 commit `f2b0de555a6cd1292ad6918f90f2c4e217dc93ce` expands the spatial footprint of Liquid Glass diffusion: broad blur radius 44→62px and directional cross-band radius 24–34→30–42px. This targets the remaining paired black/white rails by averaging across a wider neighbourhood, not by adding arbitrary brightness or glyph-specific tint.
- Solid/Frosted Glass protection checked before commit: the blur radius still resolves to exactly 38px at `solidMode == 1`; Solid warp, face selection and opacity/material selection remain intact. No Solid/Frosted shader contribution was intentionally changed.
- #71 CI runs: Digit Lab `43561850509`; Production `43561850484`; queued when recorded. Await successful completion and on-device visual validation before judging the wider diffusion.

## Status snapshot

- **Active branch:** feature/live-wallpaper-big-clock
- **Latest renderer commit:** `8977b7fcc6d85dd6df6813969d9ff1970d170055` — “Soften high-contrast paired bands in shared glass transport”
- **Latest accepted typography:** 1–0 0.053em; 4–7 0.019em; 0–: 0.038em; :–4 0.034em. Freeze spacing. All values are shared across both materials.
- **Latest user-tested builds:** Production #321 and Digit Lab #43 succeeded from `a6e3825`; user accepts shared spacing and varied-time consistency.
- **Latest submitted comparison:** #66 vs #65 shows a small but real orange/blue/black blend improvement in `0`; `1` essentially unchanged, `4`/`7` retain previous results. Compare next candidate against #66 and iOS reference. #63's wider probe remains rejected.
- **Latest comparison image order:** first supplied screenshot is our Digit Lab rendering; second supplied screenshot is Apple's original iOS reference. Use the second as the visual target.
- **Current task:** improve only Production Glass / Digit Lab Liquid Glass blending, scattering, internal illumination and refraction toward the iOS image.
- **Latest tested candidate:** Production #341 and Digit Lab #65 from `f8a480e` succeeded; #65 screenshot confirms residual paired-band problem. New candidate from `8977b7f` is queued/running. User requires one unified, geometry/image-driven optical model for every glyph; no digit-specific or screen-region exceptions. Do not touch accepted typography or Solid/Frosted.
- **Latest shader changes:** commit `6a3a20a` improves shared Liquid Glass volume/scattering/diffusion; `9651e9c` adds a subtle global directional-glint/rim increase; `f6e61bc` broadens the SDF-normal softbox lobe and slightly expands contrast-adaptive diffusion.
- **Solid protection:** at `solidMode == 1`, Solid still selects original centre wallpaper transmission, 38px blur, opacity, bevel, sheen and lighting; new ambient fill is explicitly multiplied by `1-solidMode`. Static checks passed. Device screenshot confirmation is still required.
- **Next visual checks:** compare build from `6a3a20a` using the same diagonal wallpaper against iOS. Judge the overall material across all digits, not only 1/0/4/7. Check for continuous silver-blue body, softer merged bands, no muddy/frosted result; then test the beach wallpaper.
- **Do not claim visual parity from CI alone.** Build #44/#322 from `5ee517e` succeeded, but user comparison confirmed optical work remains. Commit `6a3a20a` is the new candidate; builds #45/#323 are in progress and on-device appearance remains unverified.

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


### Digit Lab #72 candidate — two-scale colour transport (14 Nov 2026)
- After the user tested #71, the visible issue remained: Liquid Glass still transmits narrow paired wallpaper rails too sharply, especially through `0` and `1`; the `4` and `7` show more haze but still lack the reference's naturally blended optical volume.
- Renderer commit `9eac57580b6519a9c9bebbab87ea97dcfbd7be86` — “Add two-scale colour transport for Liquid Glass”.
- Replaced the single-radius-only Glass atmospheric target with a colour-preserving two-scale kernel: centre sample 14%, four 28px cross samples 36%, and existing 62px eight-direction ring 50%. This should merge close rails while carrying orange/blue/white colour into the body instead of applying a white veil.
- Liquid Glass isotropic fallback now blends 72% toward this two-scale atmosphere; its broad diffuse transport uses 18% microfacet contribution; atmospheric scatter increases from 58–70% to 68–80%, still gated by image contrast and the shared glass-volume mask.
- **Solid/Frosted is protected:** the original `frostedWallpaper` ring, 38px blur selection, `solidWarp`, face path, opacity and all Solid-only lighting remain unchanged. The new four-sample kernel is consumed only by Liquid Glass expressions.
- No glyph-specific corrections, digit geometry, font, weight/width, tracking, layout, colour picker, UI, wallpaper/depth or gesture behaviour was changed.
- This commit is a test candidate, not a verified visual improvement. The feature-branch push should trigger the existing Digit Lab and Production workflows. Wait for both CI runs to finish and inspect the Digit Lab artifact before calling the build ready; then compare #72 with #71 using the same diagonal wallpaper and the original iOS reference. Watch especially for improved blending in `0` and `1`, retained warm/cool saturation, and no washed-out/frosted appearance in `4` and `7`.

## Build #73 candidate — refracted-ray volume integration (2026-11-14)
- Branch remains `feature/live-wallpaper-big-clock`; no branch switch.
- Renderer commit: `0e1c6ea80082abee37cb7f9cc0fe9d1c86fc5af6` — “Integrate refracted light through glass volume”.
- Builds on #72's existing SDF-derived convex surface, Snell-style refraction, Fresnel response, environment sampling and two-scale wallpaper transport.
- Added three interior wallpaper samples along the actual refracted ray (quarter, midpoint, three-quarter), combined with Simpson-style weights, then a Beer-Lambert transmittance term based on ray length. Integrated ray colour now contributes to the Liquid Glass atmospheric transport.
- This is an experimental image-space volume approximation, not literal global illumination or a physically complete 3D renderer. It adds three wallpaper texture samples per fragment; profile/performance must be tested on the Galaxy S25+.
- Solid/Frosted path, typography, tracking, glyph geometry, Digit Lab UI, wallpaper controls, gestures and package/signing configuration were not changed.
- CI runs triggered by push: Production run `43563704313` (#350) and Digit Lab run `43563704334` (#73). At the time of this note both were in progress; do not claim APK availability until artifacts are verified.
- Visual status is unknown until user tests the APK and provides a screenshot. Compare against #72 using the same wallpaper, crop, time and settings; specifically inspect 0/1 paired rails, interior volume on 4/7, colour fidelity and sustained performance.

## GPU rendering architecture and linear-light transport pass (2026-11-14)
- Architecture audit confirmed the shared renderer already uses Android `RuntimeShader(AGSL)` assigned to a `Paint` and drawn through the Android Canvas. AGSL contributes a per-pixel function to Android's GPU fragment-shader pipeline; this is not a CPU-only bitmap effect. Official Android references: AGSL theory of operation and RuntimeShader API docs.
- Do not assume a custom Vulkan renderer is automatically the next/best step. Android's AGSL path already provides GPU fragment shading. A native Vulkan/compute path would require a separate rendering/compositing architecture and should be justified by measured capability or performance limits, not introduced just for its name.
- Existing shader already includes an SDF-derived convex height surface, finite-difference normals, Snell-style refraction, Schlick Fresnel, image-based environment reflection, local structure-tensor-guided wallpaper diffusion and #73's three interior refracted-ray samples. The next work should improve physical correctness and light transport, not duplicate these systems.
- Renderer commit `e53045d521e9a4adc355f4c144ba7d19ffb32d47` — “Integrate Liquid Glass light transport in linear colour”.
- The #73 refracted-ray integration now converts the central and three interior wallpaper samples to linear sRGB using AGSL `toLinearSrgb`, performs weighted integration and Beer-Lambert attenuation in linear light, then converts the result back with `fromLinearSrgb` before it enters the existing colour-managed material pipeline. This avoids averaging display-encoded sRGB values for this light-transport calculation.
- Scope is deliberately limited to the refracted-ray integration; the rest of the material pipeline is unchanged. Solid/Frosted, typography, layout, UI, gestures, package IDs and signing configuration were not changed.
- Production run #351 (`43564951548`) and Digit Lab run #74 (`43564951495`) were triggered by this commit and were in progress when recorded. Verify both CI outcomes and artifacts before distributing. The visual impact and S25+ performance are unverified until on-device comparison.
- Next investigation: profile the shader's per-fragment texture-sample cost and actual frame pacing on the S25+. Compare #73 and this build on the same wallpaper/settings. If performance headroom exists, explore a controlled multi-step volume integration; if not, optimise/reuse samples before increasing ray-march count. Preserve the approved Solid/Frosted path.

## Build #76 candidate — linear-light directional microfacet scattering (2026-11-14)
- User tested #75 (renderer commit `e53045d`) and judged it an improvement. The #75 screenshot retains stronger glass-like colour and coherent reflections, but the diagonal wallpaper rails remain distinct inside 0/1.
- #75 CI verification: Production run #351 (`43564951548`) and Digit Lab #74 (`43564951495`) both completed successfully from `e53045d`.
- Renderer commit `682a0e8deb7a81d1f76b4aea47ea0c80b8672e20` — “Integrate directional glass scattering in linear light”.
- The structure-tensor-derived cross-band microfacet transport now converts its already-existing centre/near/far wallpaper samples to linear sRGB, applies the existing weighted directional integration in linear light, then converts back. This targets the dark/saturated edge bias that can preserve paired rails when averaging display-encoded values.
- No extra wallpaper texture samples were added; this is a colour-space correction to samples the shader already evaluates. It should not increase the shader's texture-sampling count.
- No new volumetric fog, literal global illumination or Vulkan renderer is claimed. This remains an AGSL GPU fragment-shader optical approximation.
- Solid/Frosted, typography, glyph geometry, layout, UI, wallpaper controls, gestures, package IDs and signing configuration remain unchanged.
- CI for the new candidate should be tracked from commit `682a0e8`; do not report artifact availability until both Production and Digit Lab runs are located, completed successfully and their artifacts are verified.
- Visual test should compare #76 against #75 on the same diagonal wallpaper and settings. Check whether 0/1 paired rails blend more naturally, while preserving blue/orange chroma and avoiding a cloudy/frosted face. Also check 4/7 highlights and S25+ frame pacing/heat; no additional texture taps were added.

## Build #77 candidate — warm environmental light transport (2026-11-14)
- User reviewed #76 and explicitly confirmed improvement: soft environmental light is now visible inside the 1 for the first time, while the 4 and 7 retained their prior effects. Remaining requested refinement: orange/warm light should scatter more.
- #76 CI verification: Production run #352 (`43566298293`) and Digit Lab #75 (`43566298286`) both completed successfully from renderer commit `682a0e8`.
- Renderer commit `fb7f928e452d509b968a1140dd1e1ad0a40958e0` — “Preserve warm environmental light in glass scattering”.
- Added a warm-chroma detector using the existing linear-light directional microfacet transport samples. It identifies red/orange energy relative to blue and gently boosts only the warm component in the volumetric scatter target. It does not add a fixed orange light, screen overlay, glyph-specific rule, or new wallpaper texture samples.
- Scope is limited to the Liquid Glass volume-scatter target. Existing blue/neutral paths should remain largely unaffected; actual appearance must be confirmed on-device.
- Solid/Frosted, typography, glyph geometry, layout, UI, wallpaper controls, gestures, package IDs and signing configuration remain unchanged.
- Production run #353 (`43566878464`) was queued after the push. Locate the matching Digit Lab run and verify both CI results/artifacts before distributing; do not claim APK availability yet.
- Visual test: same wallpaper and settings as #76. Check whether orange light spreads more softly through 0/1 without orange tint contaminating blue areas; confirm 4/7 highlights and transparency remain intact.

## Build #78 candidate — warm light in broad diffuse transport (2026-11-14)
- User reviewed #77 and confirmed improvement: orange, black and white light now blend more naturally inside the 0, while other digits/effects remain intact.
- #77 CI verification: Production run #353 (`43566878464`) and Digit Lab #76 (`43566878482`) both completed successfully from renderer commit `fb7f928`.
- Renderer commit `10654d183fc2ad384ae3d8a441a46dd6c6d6f538` — “Carry warm light into diffuse glass transport”.
- Extends the existing wallpaper-derived warm-chroma signal into the broad diffuse transport target, in addition to its existing local volume-scatter contribution. Warm atmospheric transport is blended toward the warm-preserving sample with a restrained `warmLightPresence * 0.30` factor; blue/neutral areas remain governed by their own sampled chroma.
- No additional wallpaper texture samples; this only reuses existing linear-light microfacet samples and warm transport.
- This is a narrow incremental candidate intended to spread orange/amber light more softly through the glass body. It is not yet visually validated.
- Solid/Frosted, typography, glyph geometry, layout, UI, wallpaper controls, gestures, package IDs and signing configuration remain unchanged.
- CI for commit `10654d1` must be located and verified for both Production and Digit Lab before sharing APKs. Compare with #77 on the same wallpaper/settings; reject if orange bleeds into blue regions, highlights flatten, or transparency is lost.

## Build #79 candidate — slightly broader warm diffuse contribution (2026-11-14)
- User tested #78 and confirmed a clear improvement: warm light spreads more naturally while the other colour effects remain intact.
- #78 CI verification: Production run #354 (`43567837259`) and Digit Lab #77 (`43567837263`) both completed successfully from renderer commit `10654d1`.
- Renderer commit `3216654bca11fc9da82ab6570c23a51f74b6b164` — “Broaden warm light diffusion through glass body”.
- This is a deliberately small follow-up: increased the wallpaper-derived warm contribution in the broad diffuse target from `warmLightPresence * 0.30` to `warmLightPresence * 0.42`. It reuses existing samples and the same warm-chroma detector; no extra texture reads or fixed tint.
- No changes to ray integration, refraction, Fresnel, geometry, glyph-specific behaviour, Solid/Frosted, typography, layout, UI, gestures, package IDs or signing.
- Production run #355 (`43568505067`) and Digit Lab #78 (`43568505093`) were queued when checked. Verify both runs and artifacts before distribution.
- Compare with #78 on the same wallpaper/settings. Accept only if warm scattering becomes more legible without contaminating blue regions, flattening the 4/7 highlights or making the glass cloudy. If this is too warm, revert to #78; it is the proven baseline.

## Build #80 candidate — wallpaper-structure-aligned atmospheric kernel (2026-11-14)
- User explicitly reiterated the invariant: no digit-specific, time-specific or screen-region-specific rendering. All optical behaviour must be shared and driven by the local wallpaper/material response.
- User's #78 screenshot review: retained blue-light blending and approved #78 as the visual baseline; warm/neutral blending was improved. #79's stronger broad warm mix was not the screenshot the user reviewed, so #80 restores the #78 broad diffuse warm contribution (0.30) while retaining the #78 local volume-scatter warm contribution (0.42).
- Renderer commits: `f7c7dd8ed8dfe693e8f406e164b644d7772601b3` (adaptive kernel) followed by `3441e315313fdfca9616b4335b388e2cc01ba2db` (restore exact #78 warm-scatter balance).
- Replaced the short-range axis-aligned atmospheric cross kernel with a cross kernel rotated by the local wallpaper RGB structure tensor: one axis follows the dominant colour-edge normal and the perpendicular follows the local band direction. This lets the same transport kernel adapt to diagonal, horizontal, vertical and irregular wallpaper features.
- Reuses the same four short-range texture samples; no extra texture reads. No glyph identity, clock-time, fixed screen-coordinate or wallpaper-specific rules.
- Solid/Frosted original blur path, typography, geometry, layout, UI, controls, gestures, package IDs and signing config remain unchanged.
- CI runs for adaptive-kernel commit `f7c7dd8`: Production #356 (`43569048518`) and Digit Lab #79 (`43569048564`) were in progress at check time. Verify artifacts and successful completion before distribution.
- Visual test: compare #80 with #78 on the same diagonal wallpaper, then test at least one wallpaper with vertical/horizontal or irregular features and a different clock string. Check that the diffusion direction follows wallpaper structure everywhere, colours stay balanced, no glyph-specific artefacts appear, and 4/7 highlights remain.

## Build #81 candidate — anisotropic wallpaper-aligned diffusion (2026-11-14)
- User reviewed #80 and confirmed the black/white wallpaper bands are beginning to blend, subtly but visibly. #80 is now the validated baseline; blue and warm colour effects remain intact.
- Renderer commit `b000fb73b7d68e69d8a22e5f4e7248298833067d` — “Use anisotropic wallpaper-aligned glass diffusion”.
- Refined the four existing short-range tensor-oriented samples into an anisotropic kernel: 68% weight across the dominant local wallpaper bands (structure-tensor edge normal), 32% along the bands (perpendicular tangent). This should blend black/white band boundaries more effectively while preserving colour continuity along the bands.
- No additional texture reads; same four samples and same tensor-derived orientation. No digit/time/screen-region/wallpaper-specific rules; one shared material response for every glyph and wallpaper.
- No changes to refracted-ray integration, linear-light microfacet scattering, warm/cool transport weights, surface geometry, reflections, typography, Solid/Frosted, UI, gestures, package IDs or signing.
- #80 successful CI was confirmed: Digit Lab #80 (`43569076184`) and Production #357 (`43569076233`) from final #80 commit `3441e31`.
- #81 CI runs need to be located and checked against exact head SHA `b000fb73b7d68e69d8a22e5f4e7248298833067d`; do not reuse #80 links or report artifact availability until verified.
- Compare #81 to #80 on the same diagonal wallpaper. Also test a different wallpaper orientation and different clock string. Reject if colour detail along bands is smeared, blue/orange balance shifts, or 4/7 highlights degrade.

## Build #82 candidate — coherence-adaptive anisotropic diffusion (2026-11-14)
- User tested #81 on a second, very different wallpaper (pink flowering tree, open sky, sunset and water) with the same clock string. User confirmed the desired glass response and colour behaviour carried over to this image. This is important cross-wallpaper validation of the universal material.
- #81 CI verified successful: Digit Lab #81 (`43569954038`) and Production #358 (`43569953915`) both completed successfully from exact renderer SHA `b000fb73b7d68e69d8a22e5f4e7248298833067d`.
- Renderer commit `7bb77ae1fd52b8a43506b138ab1aa3397f9a940f` — “Adapt glass diffusion to local wallpaper coherence”.
- Refined the #81 anisotropic near-field kernel so its cross-band weighting adapts to the local RGB structure-tensor coherence. The cross-band weight smoothly ranges from 0.50 (isotropic where local orientation is ambiguous) to 0.72 (anisotropic where the wallpaper has a strong coherent edge direction); along-band weight is the complement.
- This avoids imposing a fixed 68/32 directional split on every image region. Strongly oriented structures receive more cross-band mixing; open sky, water and irregular textures naturally approach balanced diffusion. This is a shared per-pixel wallpaper-derived response, not a digit/time/region preset.
- Reuses the exact same four short-range texture samples and the existing tensor; no additional texture reads or new render passes.
- Solid/Frosted, refracted ray integration, warm/cool transport, surface geometry, typography, layout, UI, gestures, package IDs and signing config unchanged.
- CI for #82 must be checked against exact SHA `7bb77ae1fd52b8a43506b138ab1aa3397f9a940f`; do not reuse #81 run links or report artifacts until verified.
- Test #82 on both the diagonal blue/orange wallpaper and the flowering-tree sunset/water wallpaper, with at least one different clock string. Check for coherent band blending on the first, preserved foliage detail and natural sky/water response on the second, and unchanged colour/reflection balance.

## Build #82 regression and rollback (2026-11-14)
- User reported #82 produced completely flat, opaque white clock glyphs in both Liquid Glass and Frosted Glass. Uploaded screenshots `284069.jpg` and `284071.jpg` show the same failure in both material modes: wallpaper visible behind the shapes, but all glass transmission/reflection/bevel response absent.
- #82 workflow builds both completed successfully, but CI success did not catch the device-side rendering regression. Treat these screenshots as authoritative visual evidence; do not claim the new renderer worked.
- Rolled the shared renderer file back exactly to validated #81 content from SHA `b000fb73b7d68e69d8a22e5f4e7248298833067d`.
- Rollback commit: `05ab4c099139a701e02daa064d0d3b56351f6003` — “Restore validated build 81 renderer after build 82 regression”.
- This rollback restores the shared LiquidWallpaperRenderer.kt; no app IDs, signing config, UI, typography, gestures or Solid/Frosted implementation were intentionally modified.
- Need verify new rollback Production and Digit Lab workflows against exact rollback SHA `05ab4c099139a701e02daa064d0d3b56351f6003`. Do not reuse #81/#82 run links; only provide new artifacts after checking their SHA, success, and APK availability.
- Root cause is not confirmed. The #82 code change only altered the anisotropic weights based on tensor coherence, but the device screenshots show both material modes failing. Investigate runtime AGSL shader initialization/compilation and device logs before another renderer experiment. Gradle/CI success is not proof of AGSL runtime shader success.

## Build #83 candidate — conservative band-aware diffusion (2026-11-14)
- User confirmed rollback build restored Liquid Glass and Frosted Glass; user always updates APKs in place over the installed app. Never instruct uninstall/reinstall; preserve package ID and signing identity.
- Rollback CI verified: Digit Lab #83 (`43571338222`) and Production #360 (`43571338132`) both completed successfully from rollback commit `05ab4c099139a701e02daa064d0d3b56351f6003`.
- #82 device regression remains unexplained. The #82 change was limited to deriving the cross-band mix from `tensorDiscriminant / max(tensorXX + tensorYY, 0.0001)`, but user's device rendered both materials as flat opaque white. CI success did not detect this. Do not claim root cause; AGSL runtime failure is only a hypothesis.
- New candidate commit `dff6fd745d93c11ef3f992aafac8bc3011aaacc1` — “Add conservative band-aware diffusion from validated renderer”.
- This starts from the exact restored #81 renderer and changes only the four-sample near-field blend: cross-band weight now uses `mix(0.50, 0.68, bandContrast)`; along-band weight is its complement. `bandContrast` was already computed and used in #81. No new tensor expressions, uniforms, texture samples, render passes or dependencies.
- This is deliberately more conservative than #82 and preserves #81's maximum 68/32 directional split while easing toward 50/50 in low-contrast areas. Shared per-pixel wallpaper-driven behaviour only; no digit/time/screen-region/wallpaper presets.
- No changes to Solid/Frosted source path, refracted ray integration, linear-light transport, warm/cool balance, geometry, typography, UI, gestures, app IDs or signing.
- CI run IDs: Digit Lab #84 (`43571964236`) and Production #361 (`43571964259`), queued at time of commit inspection. Verify completion and exact SHA before providing artifact links.
- Test both materials immediately on-device before evaluating subtle visual changes. If either material loses its shader response, stop and revert to the exact #81 rollback renderer. Compare against #81 on both known wallpapers, plus a different clock string.

## Build #85 candidate — restrained anisotropic weighting refinement (2026-11-14)
- User confirmed #84 preserves the optical work and both materials behave naturally across the blue diagonal and orange-liquid wallpapers; a different clock composition was also tested. #84 is the current validated baseline.
- #84 CI verified: Digit Lab run #84 (`43571964236`) and Production run #361 (`43571964259`) both completed successfully from exact SHA `dff6fd745d93c11ef3f992aafac8bc3011aaacc1`.
- Renderer commit `561abefe4a8c1a7c2cbeb86e1bb6b11c7b4b281d` — “Refine adaptive cross-band glass diffusion”.
- One scalar-only refinement to the validated #84 kernel: cross-band weight changes from `mix(0.50, 0.68, bandContrast)` to `mix(0.52, 0.70, bandContrast)`; along-band remains the complement. This is a small 2-percentage-point increase at both ends, preserving the same adaptive relationship and existing scalar.
- No new AGSL expressions beyond the existing simple mix, no new uniforms, texture reads, render passes, or dependencies. No digit/time/screen-region/wallpaper-specific rules.
- Solid/Frosted source path, all ray integration and colour transport, geometry, typography, UI, gestures, app IDs and signing unchanged.
- Digit Lab run #85 (`43574127155`) is queued and matches exact renderer SHA `561abefe4a8c1a7c2cbeb86e1bb6b11c7b4b281d`. Production workflow not yet visible at time of note. Verify both before providing APKs; no build artifact claimed yet.
- On-device validation must begin with checking Liquid Glass and Frosted Glass still render, then compare the blue diagonal and orange-liquid wallpapers with the same clock string/settings as #84. Look for slightly improved band blending without detail loss, colour shift or altered Frosted appearance. If there is no visible benefit or any regression, revert to #84.


## Build #85 — user-validated adaptive diffusion refinement (14 Nov 2026)
- Renderer commit: `561abefe4a8c1a7c2cbeb86e1bb6b11c7b4b281d` — “Refine adaptive cross-band glass diffusion”.
- The only renderer change from #84 is a conservative 2-percentage-point increase in the adaptive cross-band diffusion range: `mix(0.50, 0.68, bandContrast)` became `mix(0.52, 0.70, bandContrast)`; along-band weight remains the complement. No extra texture reads, passes, uniforms, or dependencies.
- User has now tested Build #85 and explicitly confirmed: “Definitely improvement.”
- Their screenshots validate the improvement across orange/liquid wallpaper, blue diagonal streaks, and a pink beach/sunset wallpaper; they also tested Liquid Glass and Frosted Glass, and varied font width (regular/condensed/compressed). The user-visible gain is stronger/more coherent wallpaper-colour and directional-structure transport through Liquid Glass, while Frosted Glass remains visibly distinct.
- Both CI workflows succeeded on the exact same commit: Production build #362, run [43574127173](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43574127173); Digit Lab build #85, run [43574127155](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43574127155).
- #85 is now a visually validated checkpoint. Preserve it as the baseline. Do not make another diffusion-strength increase by default; the next meaningful refinement should target the volumetric/convex optical body and believable surface reflections, not simply more blur or brighter outlines. Keep the universal-rendering rule and Solid/Frosted protection intact.


## Build #86 candidate — height-derived optical depth (14 Nov 2026)
- Starts from visually validated Build #85 (`561abefe4a8c1a7c2cbeb86e1bb6b11c7b4b281d`).
- Renderer commit `a4624ba716e52d35b21900293b8f47f1837a192c` — “Derive Liquid Glass refraction depth from convex profile”.
- Reuses the already-computed convex height field `glassHeight` and profile radius to derive a normalized local body depth. The refraction path thickness now transitions smoothly from 38px at the thin shoulder to 46px at the crown, rather than applying one constant 46px slab thickness across the entire surface.
- This ties the existing Snell refraction displacement to the same convex geometry that supplies the surface normal. It is a universal per-pixel optical-depth response: no digit/time/screen/wallpaper presets, no new texture reads, no new passes or uniforms.
- Solid/Frosted path remains unchanged: the updated thickness is used only by `glassRefractionOffset`, while `solidWarp` remains selected at `solidMode == 1`; Frosted's existing warp, blur, face, opacity, bevel and sheen are untouched. Typography, layout, controls, gestures, package IDs and signing are untouched.
- This is an experimental candidate, not yet visually validated. Compare against #85 on the same orange, blue diagonal and beach wallpapers; watch for a more coherent crown-to-shoulder refraction transition without excessive distortion, darkening or loss of colour. If it is not clearly better, revert this single renderer change to #85.

- On-device screenshots received for #86 on blue diagonal, pink beach/sunset and green organic wallpapers. User observes the most noticeable improvement on digit `1`. Visual review agrees the `1` shows the clearest crown-to-shoulder depth transition; changes on `0`, `4` and `7` are more subtle. Treat this as preliminary feedback, not a blanket approval of #86; preserve #85 until the user explicitly accepts the overall result.
- Both #86 CI workflows completed successfully on exact SHA `a4624ba716e52d35b21900293b8f47f1837a192c`: Production #363 (`43574708137`) and Digit Lab #86 (`43574708158`).


## Build #86 — user acceptance (14 Nov 2026)
- User reviewed #86 against #85 and clarified that the improvement on glyph `1` is meaningful to them, while `0` shows less visible change.
- Crucially, they report that the change does not distort or damage the other existing effects. They regard the result as an overall improvement and accept retaining the height-derived optical-depth experiment.
- Keep #86 as the current accepted baseline; do not roll it back merely because the improvement is more visible on `1` than `0`. This is still one universal profile-driven material, not a digit-specific treatment.
- Future experiments should preserve the #86 benefit on `1` while seeking more convincing convex volume/reflection across other glyph geometries, without introducing per-digit rules or degrading colour transport/Frosted Glass.


## Build #87 candidate — stronger convex depth modulation (14 Nov 2026)
- Starts from user-accepted Build #86 (`a4624ba716e52d35b21900293b8f47f1837a192c`).
- Renderer commit `0bec405fa9337bbf9df9a72a4b120c7938e8a370` — “Strengthen convex glass depth modulation”.
- Changes only the geometry-derived refraction thickness range from 38–46px to 36–48px. The same `glassBodyDepth` derived from the convex height map controls the interpolation. This modestly increases crown-to-shoulder thickness contrast (8px span to 12px span) to test whether the benefit seen on `1` can become more apparent across other glyph geometries.
- No new texture reads, uniforms, passes, dependencies, digit-specific conditions, wallpaper presets or screen-region rules. Surface normals, convex profile, Snell IOR, diffusion, reflections, colour transport and glyph layout remain unchanged.
- Solid/Frosted mode remains on its existing separate `solidWarp` and source path; this thickness is only consumed by `glassRefractionOffset` in Liquid Glass.
- Experimental only, not visually validated. Compare against #86 using identical wallpaper, clock string, font width/weight and placement. Prioritize whether `0`, `4`, `7` gain a more coherent volume while the improved `1` remains intact; reject if distortion, colour displacement, darkening or edge artifacts increase. #86 remains rollback baseline.

## Build #88 candidate — curvature-derived Liquid Glass ray correction (2026-11-14)
- Starts from the #87 candidate on `feature/live-wallpaper-big-clock`, renderer commit `0bec405fa9337bbf9df9a72a4b120c7938e8a370`.
- Renderer commit `8d5f8df73c4987c5ed918f9ecf10250fb6d7c216` adds a small curvature correction to the existing Snell-refraction offset.
- Reuses the existing convex height field and its four neighbouring height samples to estimate the local second derivatives (height-field curvature). The resulting correction is capped at ±1.6px per axis and added to the Liquid Glass refraction offset.
- This is a geometry-derived, per-pixel optical correction shared by every glyph and wallpaper. It adds no wallpaper/SDF texture reads, uniforms, render passes, digit-specific logic, clock-time rules or screen-region presets.
- The correction feeds the existing refracted sample point and ray integration, so the black/white band bending and colour transport remain part of the same optical path.
- Solid/Frosted continues to select its independent `solidWarp` and is not intentionally modified. Typography, layout, controls, gestures, package IDs and signing are unchanged.
- This is an experimental candidate, not visually validated. Compare against #87 on the same blue diagonal wallpaper first, then the beach/sunset wallpaper. Reject if the zero develops wavy/creased bands, if other glyphs distort, or if existing colour transport/reflections regress. #86 remains the accepted depth baseline and #87 remains the immediate comparison candidate.
- No GitHub Actions build was triggered automatically; wait for the user's explicit build instruction or test workflow preference.


## Build #89 candidate — restrained Liquid Glass microfacet sparkle (14 Nov 2026)
- Starts from the user-validated Build #88 result, renderer commit `e3be79c2f5bbeea75027718983da351867242fc2`.
- Renderer commit `700e65a0de1bcee3a2d3a0bb5feb0308fc22f3c0` adds one low-energy, high-frequency specular lobe using the existing convex surface normal and key-light half vector.
- The added lobe uses a 68-power highlight, is gated by the existing surface-slope and normal-confidence values, and is attenuated on horizontal shoulders. It is added at 0.14 strength in a cool-neutral highlight tint.
- The goal is a more precise liquid-glass sparkle on curved shoulders, complementing (not replacing) the existing broad softbox reflection and key/fill glints. The low strength and geometry gates are intended to avoid a continuous white outline or an embossed/painted appearance.
- No new uniforms, texture reads, passes, dependencies, digit-specific rules, wallpaper presets, or screen-position logic. The correction is universal across glyphs.
- Solid/Frosted remains protected by the existing `(1.0 - solidMode)` gate; its specular path is unchanged.
- Experimental and not yet visually validated. Compare against #88 using identical settings and wallpapers. Check that the new glint reads as a crisp, restrained surface reflection, not a white stroke; reject if it creates sparkle noise, edge tracing, loss of wallpaper colour, or any Solid/Frosted change.
- No build was triggered automatically. Build #88 remains the accepted visual baseline until the user reviews #89.


## Build #92 candidate — CryEngine-inspired environment area-light bounce (14 Nov 2026)
- Experimental branch `experiment/cryengine-area-light` was created directly from the user-selected #90 baseline commit `13f8718f922966209891e292c02081c053821b55`. The accepted #90 branch/history was not modified.
- Renderer commit `3dbcf81011e139b057b3341f07fccb4a1f43c216` adds a low-energy colour-bearing environment contribution to the existing broad convex-surface area reflection.
- The contribution reuses the existing `glassBrightBloom` low-frequency bright wallpaper samples and `directionalEnvironmentReflection`; a luma gate activates it only when nearby wallpaper contains sufficient bright energy. It is multiplied by the existing broad `glassAreaReflection` lobe and gated out entirely for Solid/Frosted.
- Concept inspired by the CRYENGINE 3 trailer's real-time area lights: the glass surface should reflect a broad local light source with colour borrowed from its environment, rather than relying exclusively on a fixed white softbox.
- No new texture reads, uniforms, passes, digit-specific rules, or screen-position logic. No changes to refraction, curvature correction, #90 microglint, existing transmission/scattering, typography or layout.
- Experimental and not visually validated. Compare against #90 with identical wallpaper, weight 539, width, digits, size and placement. Check whether reflections inherit local blue/orange/white light naturally. Reject if the face becomes washed out, too bright, milky, metallic, or loses wallpaper transparency. Also compare Solid/Frosted to ensure the path remains unchanged.
- This is the first of separate CryEngine-inspired experiments; do not stack volumetric scattering changes into this build. Both Android and Digit Lab workflows should build automatically and must be verified against this commit SHA.

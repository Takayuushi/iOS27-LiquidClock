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

## Status snapshot

- **Active branch:** feature/live-wallpaper-big-clock
- **Latest renderer commit:** `5ee517eea0cc8c33d9deff2a4356670d7ef862ec` — “Extend Liquid Glass scattering through shared glyph volume”
- **Latest accepted typography:** 1–0 0.053em; 4–7 0.019em; 0–: 0.038em; :–4 0.034em. Freeze spacing. All values are shared across both materials.
- **Latest user-tested builds:** Production #321 and Digit Lab #43 succeeded from `a6e3825`; user accepts shared spacing and varied-time consistency.
- **New build pair from optical pass:** Production #322, run ID `43480428529`, completed/success from `5ee517e`; artifact ID `14064324520`: [Actions #322](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43480428529). Digit Lab #44, run ID `43480428502`, completed/success from the same SHA; artifact ID `14064264490`: [Actions #44](https://github.com/Takayuushi/iOS27-LiquidClock/actions/runs/43480428502).
- **Image order rule for the latest comparison:** first supplied screenshot is Apple's iOS reference; second supplied screenshot is our #43 Digit Lab. Use the first as the visual target.
- **Current task:** improve only Production Glass / Digit Lab Liquid Glass blending, scattering, internal illumination and refraction toward the iOS image. Do not touch accepted typography or Solid/Frosted.
- **Latest shader change:** shared glyph-independent `glassVolumeRegion` combines the rounded edge profile with SDF-derived `interiorVolume`; it drives core volume scattering and dark-area silver-blue ambient fill. Directional wallpaper diffusion widened from 6.8–10.8px to 10–15px and high-contrast blend strengthened from 0.25–0.72 to 0.36–0.80; volume scatter factor 0.14→0.22. Commit `5ee517e`.
- **Solid protection:** at `solidMode == 1`, Solid still selects original centre wallpaper transmission, 38px blur, opacity, bevel, sheen and lighting; new ambient fill is explicitly multiplied by `1-solidMode`. Static checks passed. Device screenshot confirmation is still required.
- **Next visual checks:** compare same diagonal wallpaper against the iOS reference. Inspect dark portions of 1 and 0 for silver-blue body, 0's horizontal bands for softer merging, and 4/7 for coherent refraction. Then test the beach wallpaper to catch accidental frosted/cloudy output.
- **Do not claim visual parity from CI alone.** Both APKs compile and artifacts are uploaded; optical improvement and Solid preservation remain pending user's on-device comparison.

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

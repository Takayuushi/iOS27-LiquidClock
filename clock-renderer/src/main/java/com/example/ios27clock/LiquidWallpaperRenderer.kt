package com.example.ios27clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Full-screen wallpaper clock compositor.
 *
 * Composition order:
 * 1. Original selected wallpaper
 * 2. Liquid-glass clock, clipped by the real glyph alpha mask
 * 3. The selected-object mask erases only clock pixels beneath the object
 */
class LiquidWallpaperRenderer(
    private val context: Context,
    private val wallpaperContent: RendererWallpaperContent = RendererWallpaperContent.PREVIEW,
    private val diagnostics: RendererDiagnostics = LogRendererDiagnostics,
    private val clockTextSource: RendererClockTextSource = SystemRendererClockTextSource
) {

    companion object {
        private const val TAG = "LiquidClockRenderer"
        private const val FONT_ASSET_PATH = "fonts/sf_pro_display_thin.otf"
        private const val VARIABLE_FONT_ASSET_PATH = "fonts/SF-Pro.ttf"

        private const val DEFAULT_CLOCK_X = 0.50f
        private const val DEFAULT_CLOCK_Y = 0.27f
        private const val LEGACY_DEFAULT_CLOCK_Y = 0.36f
        private const val PREVIOUS_DEFAULT_CLOCK_Y = 0.31f
        private const val MAX_GLYPH_HEIGHT_FRACTION = 0.38f
        private const val MAX_COMMITTED_HEIGHT_FRACTION = 0.74f
        private const val MAX_GLYPH_WIDTH_FRACTION = 0.94f
        private const val SF_PRO_WIDTH_COMPRESSED = 50
        private const val SF_PRO_WIDTH_CONDENSED = 60
        private const val SF_PRO_WIDTH_REGULAR = 100
        private const val SF_PRO_WIDTH_EXPANDED = 132
        private const val CLOCK_GLYPH_WIDTH_SCALE = 1.00f
        private const val CLOCK_GLYPH_HEIGHT_SCALE = 1.04f

        // Keep the natural horizontal layout while the adaptive clock grows
        // vertically. Individual glyph profiles handle optical corrections.
        private const val MIN_GLYPH_WIDTH_SCALE = 1.00f
        private const val MAX_GLYPH_WIDTH_SCALE = 1.00f
        private const val MIN_GLYPH_HEIGHT_SCALE = 1.00f
        private const val MAX_GLYPH_HEIGHT_SCALE = 1.04f
        private const val TRACKING_EM = 0.035f
        private const val BASE_WEIGHT_MIN = 520
        private const val BASE_WEIGHT_MAX = 820
        private const val COLON_ADVANCE_EM = 0.105f
        private const val COLON_DOT_RADIUS_EM = 0.040f
        private const val GLYPH_STROKE_FRACTION = 0.0f
        private const val COLON_DOT_OFFSET_EM = 0.16f
        private const val MIN_CLOCK_SCALE = 0.72f
        private const val MAX_CLOCK_SCALE = 2.55f
        private const val MIN_CLOCK_WIDTH_SCALE = 0.70f
        private const val MAX_CLOCK_WIDTH_SCALE = 1.65f
        private const val MAX_COMMITTED_WIDTH_FRACTION = 0.94f
        private const val SDF_RANGE_PX = 24f
        private const val SDF_PADDING_PX = 28
        private const val GLASS_LIGHT_WRAP_RADIUS_PX = 18f
        private const val SDF_CARDINAL_COST = 5
        private const val SDF_DIAGONAL_COST = 7

        private val AGSL = """
        uniform shader uWallpaper;
        uniform shader uGlyphMask;
        uniform shader uColonMask;
        uniform shader uGlyphSdf;
        uniform float2 uResolution;
        uniform float2 uClockCenter;
        uniform float2 uSdfOrigin;
        uniform float2 uColonOrigin;
        uniform float uSdfScale;
        uniform float2 uTilt;
        uniform float uSdfRange;
        uniform float uFrosted;
        uniform float uTintStrength;
        uniform float uTime;
        uniform float3 uAccentColor;
        uniform float3 uClockTint;

        half4 main(float2 p) {
            float coverage = uGlyphMask.eval(p).a;

            // The padded signed-distance field extends beyond the glyph mask.
            // Keep a small outside region active so bright wallpaper light can
            // wrap softly around the glass edge instead of being hard-clipped.
            float2 sdfPoint = (p - uSdfOrigin) * uSdfScale;
            float sdf = uGlyphSdf.eval(sdfPoint).a;
            float signedDistance = (sdf - 0.5) * 2.0 * uSdfRange;
            float solidMode = clamp(uFrosted, 0.0, 1.0);
            float outsideDistance = max(-signedDistance, 0.0);
            if (coverage < 0.002 &&
                (solidMode > 0.5 || outsideDistance > 18.0)) {
                return half4(0.0);
            }
            float colonCoverage = uColonMask.eval(p - uColonOrigin).a;
            float colonFlag = smoothstep(0.18, 0.82, colonCoverage);

            // The liquid-glass surface is a lens, not a raised text bevel.
            // A wider distance profile lets refraction travel through the
            // translucent face while its narrow Fresnel rim catches light.
            // A slightly wider finite-difference baseline filters single-pixel
            // SDF stair-steps before they become visible as fractured refraction.
            float2 sampleStep = float2(4.25, 4.25) * uSdfScale;
            float sdfLeft = uGlyphSdf.eval(sdfPoint - float2(sampleStep.x, 0.0)).a;
            float sdfRight = uGlyphSdf.eval(sdfPoint + float2(sampleStep.x, 0.0)).a;
            float sdfTop = uGlyphSdf.eval(sdfPoint - float2(0.0, sampleStep.y)).a;
            float sdfBottom = uGlyphSdf.eval(sdfPoint + float2(0.0, sampleStep.y)).a;
            float2 gradient = float2(sdfRight - sdfLeft, sdfBottom - sdfTop);
            float gradientLength = length(gradient);
            float normalConfidence = smoothstep(0.006, 0.055, gradientLength);
            float2 normal = -gradient / max(gradientLength, 0.0005);
            float2 refractionNormal = normal * normalConfidence;

            float insideDistance = max(signedDistance, 0.0);

            // Glass has a gently curved optical face and a broader, softer
            // Fresnel transition. Solid keeps its already-approved tighter edge.
            float glassEdge = exp(-abs(signedDistance) / 8.0);
            float solidEdge = exp(-abs(signedDistance) / 3.4);
            float edgeBand = mix(glassEdge, solidEdge, solidMode);
            float glassLens = smoothstep(-2.0, 6.0, signedDistance) *
                (1.0 - smoothstep(54.0, 118.0, signedDistance));
            float glassRim = exp(-abs(signedDistance - 1.15) / 6.2);
            float solidBevel = 1.0 -
                smoothstep(0.8, 13.0, insideDistance);
            // Model each digit stroke as a rounded glass rod, not a flat
            // plate with an embossed perimeter. The SDF measures distance
            // inward from either side of the stroke; an elliptical cap turns
            // that distance into a continuous optical surface normal.
            // Explicit rounded height map derived from the signed-distance
            // field. The glyph stroke becomes a convex optical cap:
            // h(d) = sqrt(2*R*d - d*d), with a flat centre after one radius.
            // Its gradient is sampled from neighbouring SDF texels so the
            // normal, refraction and lighting all describe the same surface.
            // The colon uses a shallower lens cap in Liquid Glass only.
            // Solid/Frosted always retains the original 20px profile.
            // Keep the Glass digit silhouette at the original cap width.
            // The previous 24px cap made the bright shoulder look too broad,
            // especially around the SF Pro 4 and 7.
            float glassDigitRadius = 20.0;
            float glassProfileRadius = mix(
                glassDigitRadius,
                3.2,
                colonFlag * (1.0 - solidMode)
            );
            float profileDistance = clamp(
                insideDistance,
                0.0,
                glassProfileRadius
            );
            float glassHeight = sqrt(max(
                2.0 * glassProfileRadius * profileDistance -
                    profileDistance * profileDistance,
                0.0
            ));

            float distanceLeft = max(
                (sdfLeft - 0.5) * 2.0 * uSdfRange,
                0.0
            );
            float distanceRight = max(
                (sdfRight - 0.5) * 2.0 * uSdfRange,
                0.0
            );
            float distanceTop = max(
                (sdfTop - 0.5) * 2.0 * uSdfRange,
                0.0
            );
            float distanceBottom = max(
                (sdfBottom - 0.5) * 2.0 * uSdfRange,
                0.0
            );
            float heightLeftDistance = min(distanceLeft, glassProfileRadius);
            float heightRightDistance = min(distanceRight, glassProfileRadius);
            float heightTopDistance = min(distanceTop, glassProfileRadius);
            float heightBottomDistance = min(distanceBottom, glassProfileRadius);
            float heightLeft = sqrt(max(
                2.0 * glassProfileRadius * heightLeftDistance -
                    heightLeftDistance * heightLeftDistance,
                0.0
            ));
            float heightRight = sqrt(max(
                2.0 * glassProfileRadius * heightRightDistance -
                    heightRightDistance * heightRightDistance,
                0.0
            ));
            float heightTop = sqrt(max(
                2.0 * glassProfileRadius * heightTopDistance -
                    heightTopDistance * heightTopDistance,
                0.0
            ));
            float heightBottom = sqrt(max(
                2.0 * glassProfileRadius * heightBottomDistance -
                    heightBottomDistance * heightBottomDistance,
                0.0
            ));
            float heightGradientX = (heightRight - heightLeft) / 8.5;
            float heightGradientY = (heightBottom - heightTop) / 8.5;
            float glassProfileSlope = length(
                float2(heightGradientX, heightGradientY)
            );
            float glassInteriorGate = smoothstep(
                -0.65,
                0.75,
                signedDistance
            );
            float glassProfileCoverage = (
                1.0 - smoothstep(
                    glassProfileRadius * 0.78,
                    glassProfileRadius,
                    insideDistance
                )
            ) * glassInteriorGate;
            float3 glassSurfaceNormal = normalize(float3(
                -heightGradientX,
                -heightGradientY,
                1.0
            ));
            float3 keyLight3D = normalize(float3(-0.48, -0.66, 0.58));
            float3 fillLight3D = normalize(float3(0.62, 0.46, 0.64));
            float3 viewDirection3D = float3(0.0, 0.0, 1.0);
            float3 keyHalfVector = normalize(keyLight3D + viewDirection3D);
            float3 fillHalfVector = normalize(fillLight3D + viewDirection3D);
            float keyDiffuse3D = max(dot(glassSurfaceNormal, keyLight3D), 0.0);
            float fillDiffuse3D = max(dot(glassSurfaceNormal, fillLight3D), 0.0);
            // Schlick Fresnel from the actual height-map normal. The face
            // stays transmissive at normal incidence and reflects strongly
            // only as the curved surface turns toward grazing angles.
            float viewCosine = clamp(glassSurfaceNormal.z, 0.0, 1.0);
            float glassVolumeFresnel = 0.04 +
                0.96 * pow(1.0 - viewCosine, 5.0);
            float horizontalShoulder = smoothstep(0.52, 0.88, abs(glassSurfaceNormal.y));
            float glassHorizontalHighlight = mix(1.0, 0.58, horizontalShoulder);
            // Keep the broad softbox reflection on the rounded shoulder.
            // A front-facing normal has little Fresnel curvature and must not
            // receive a white fill across the whole face.
            float glassAreaReflection = pow(
                max(dot(glassSurfaceNormal, keyHalfVector), 0.0),
                4.5
            ) * 0.58 * smoothstep(0.08, 0.48, glassProfileSlope) * glassHorizontalHighlight;
            float glassKeyGlint = pow(
                max(dot(glassSurfaceNormal, keyHalfVector), 0.0),
                42.0
            ) * 0.88 * glassHorizontalHighlight;
            float glassFillGlint = pow(
                max(dot(glassSurfaceNormal, fillHalfVector), 0.0),
                30.0
            ) * 0.56 * glassHorizontalHighlight;
            float glassSideShadow = glassProfileCoverage *
                max(-dot(glassSurfaceNormal.xy, keyLight3D.xy), 0.0);
            float outerLip = 1.0 -
                smoothstep(0.4, 5.5, max(-signedDistance, 0.0));
            float interiorVolume = smoothstep(
                1.0,
                mix(26.0, 13.0, solidMode),
                signedDistance
            );
            // The edge profile describes the curved shoulder, while
            // interiorVolume describes the glass thickness behind it.
            // Combine them so transmitted scattering reaches the centre of
            // wide strokes instead of fading out after the first 20 pixels.
            // This mask is glyph-independent and is used only by Glass passes.
            float glassEdgeVolume = smoothstep(
                0.12,
                0.66,
                glassProfileCoverage
            );
            float glassCoreVolume = interiorVolume * 0.90;
            // Combine edge and interior volume as a smooth union.
            // max() leaves a coverage trough where both masks are partially
            // active; that trough makes atmospheric scattering look patchy
            // across strokes of different widths. The union keeps one
            // continuous glass medium without knowing which glyph is rendered.
            float glassVolumeRegion = 1.0 -
                (1.0 - glassEdgeVolume) *
                (1.0 - glassCoreVolume);

            float2 tilt = clamp(uTilt, float2(-1.0), float2(1.0));

            // Snell refraction through the convex height-map surface.
            // Incoming view ray travels toward the glyph; eta is air/glass.
            const float GLASS_IOR = 1.52;
            // The same convex height field that defines the surface normal
            // also defines local optical depth: the rounded shoulder is thinner
            // than the crown of the glass rod. This gives the refracted path a
            // continuous centre-to-edge thickness transition instead of using
            // one arbitrary slab thickness everywhere.
            float glassBodyDepth = clamp(
                glassHeight / max(glassProfileRadius, 1.0),
                0.0,
                1.0
            );
            // Increase the crown-to-shoulder thickness contrast modestly.
            // The #86 range (38–46px) was accepted on-device; this candidate
            // widens it to 36–48px so curved shoulders bend the transmitted
            // ray a little more while the crown retains a deeper optical path.
            float glassOpticalThickness = mix(
                36.0,
                48.0,
                smoothstep(0.0, 1.0, glassBodyDepth)
            );
            float eta = 1.0 / GLASS_IOR;
            float cosIncident = clamp(glassSurfaceNormal.z, 0.0, 1.0);
            float refractK = max(
                1.0 - eta * eta * (1.0 - cosIncident * cosIncident),
                0.0
            );
            float refractFactor = eta * cosIncident - sqrt(refractK);
            float3 refractedRay = float3(0.0, 0.0, -eta) +
                glassSurfaceNormal * refractFactor;
            float glassEdgePull = mix(
                1.0,
                1.34,
                smoothstep(0.10, 0.55, glassProfileSlope)
            );
            // Estimate local curvature from the second derivative of the
            // existing convex height field. The height map already supplies
            // the surface normal; its Hessian adds a small, geometry-driven
            // correction to the transmitted ray so neighbouring pixels do
            // not all follow a perfectly uniform slab displacement.
            // This is deliberately capped to avoid wavy overshoot on tight
            // glyph turns and uses no extra wallpaper/SDF texture reads.
            float curvatureStepSquared = 4.25 * 4.25;
            float glassCurvatureX = (
                heightRight - 2.0 * glassHeight + heightLeft
            ) / curvatureStepSquared;
            float glassCurvatureY = (
                heightBottom - 2.0 * glassHeight + heightTop
            ) / curvatureStepSquared;
            float2 glassCurvatureWarp = clamp(
                float2(glassCurvatureX, glassCurvatureY) * 14.0,
                float2(-1.6),
                float2(1.6)
            );
            float2 glassRefractionOffset =
                refractedRay.xy * glassOpticalThickness * glassEdgePull +
                glassCurvatureWarp;
            float2 solidWarp =
                refractionNormal *
                    (1.5 + solidEdge * 6.0 + solidBevel * 1.1) +
                tilt * 0.68;
            float2 glassWarp = glassRefractionOffset + tilt * 1.8;
            float2 warp = mix(glassWarp, solidWarp, solidMode);
            float2 samplePoint = clamp(
                p + warp,
                float2(0.0),
                uResolution
            );

            float3 center = uWallpaper.eval(samplePoint).rgb;

            // Integrate the refracted path in linear light. Wallpaper texture
            // samples arrive in the shader's working colour space; averaging
            // them directly in display-encoded sRGB underestimates bright
            // energy and makes translucent media look like tinted overlays.
            // Convert only this optical integration to linear sRGB, then
            // return to the working space before combining with the existing
            // colour-managed AGSL material pipeline.
            float3 centerLinear = toLinearSrgb(center);

            // Integrate colour along the refracted ray instead of treating
            // the glass as a single displaced wallpaper lookup. Three interior
            // samples approximate a short Beer-Lambert light path through the
            // convex volume. The path length follows the actual surface slope,
            // so flat regions stay clear while curved shoulders gain depth.
            float2 glassRay = glassRefractionOffset;
            float3 rayQuarter = toLinearSrgb(uWallpaper.eval(clamp(
                p + glassRay * 0.25,
                float2(0.0),
                uResolution
            )).rgb);
            float3 rayMiddle = toLinearSrgb(uWallpaper.eval(clamp(
                p + glassRay * 0.50,
                float2(0.0),
                uResolution
            )).rgb);
            float3 rayThreeQuarter = toLinearSrgb(uWallpaper.eval(clamp(
                p + glassRay * 0.75,
                float2(0.0),
                uResolution
            )).rgb);
            float3 glassRayIntegralLinear = (
                rayQuarter + rayMiddle * 2.0 + rayThreeQuarter
            ) * 0.25;
            float glassRayLength = length(glassRay);
            float glassOpticalDepth = glassRayLength * 0.0065;
            float glassRayTransmittance = exp(-glassOpticalDepth);
            float3 glassRayTransmissionLinear = clamp(
                centerLinear * glassRayTransmittance +
                    glassRayIntegralLinear * (1.0 - glassRayTransmittance),
                0.0,
                1.0
            );
            float3 glassRayTransmission = clamp(
                fromLinearSrgb(glassRayTransmissionLinear),
                0.0,
                1.0
            );

            // Reflect the view vector about the same height-map normal.
            // This is a local image-based environment lookup, not an arbitrary
            // mirrored clock-coordinate sample.
            float3 incidentView = float3(0.0, 0.0, -1.0);
            float3 reflectedView = incidentView -
                2.0 * dot(incidentView, glassSurfaceNormal) *
                    glassSurfaceNormal;
            float2 reflectionPoint = clamp(
                p + reflectedView.xy * 30.0 + tilt * 2.0,
                float2(0.0),
                uResolution
            );
            float3 environmentReflection = uWallpaper.eval(reflectionPoint).rgb;
            float2 keyEnvironmentPoint = clamp(
                p - keyLight3D.xy * 42.0,
                float2(0.0),
                uResolution
            );
            float2 fillEnvironmentPoint = clamp(
                p + fillLight3D.xy * 34.0,
                float2(0.0),
                uResolution
            );
            float3 keyEnvironmentReflection =
                uWallpaper.eval(keyEnvironmentPoint).rgb;
            float3 fillEnvironmentReflection =
                uWallpaper.eval(fillEnvironmentPoint).rgb;
            float3 directionalEnvironmentReflection = mix(
                fillEnvironmentReflection,
                keyEnvironmentReflection,
                0.64
            );

            // Reuse the eight-direction blur below for both materials:
            // Glass gets a tighter optical diffusion, while Solid retains its
            // wider frosted response. This avoids a separate anisotropic blur.
            // Preserve wallpaper detail through Liquid Glass; only Frosted Glass gets wide diffusion.
            float blurRadius = mix(62.0, 38.0, solidMode);
            float2 blurX = float2(blurRadius, 0.0);
            float2 blurY = float2(0.0, blurRadius);
            float2 blurDiagonal = float2(
                blurRadius * 0.7071,
                blurRadius * 0.7071
            );
            float3 blurA = uWallpaper.eval(
                clamp(samplePoint + blurX, float2(0.0), uResolution)
            ).rgb;
            float3 blurB = uWallpaper.eval(
                clamp(samplePoint - blurX, float2(0.0), uResolution)
            ).rgb;
            float3 blurC = uWallpaper.eval(
                clamp(samplePoint + blurY, float2(0.0), uResolution)
            ).rgb;
            float3 blurD = uWallpaper.eval(
                clamp(samplePoint - blurY, float2(0.0), uResolution)
            ).rgb;
            float3 blurE = uWallpaper.eval(
                clamp(samplePoint + blurDiagonal, float2(0.0), uResolution)
            ).rgb;
            float3 blurF = uWallpaper.eval(
                clamp(samplePoint - blurDiagonal, float2(0.0), uResolution)
            ).rgb;
            float3 blurG = uWallpaper.eval(
                clamp(
                    samplePoint + float2(blurDiagonal.x, -blurDiagonal.y),
                    float2(0.0),
                    uResolution
                )
            ).rgb;
            float3 blurH = uWallpaper.eval(
                clamp(
                    samplePoint + float2(-blurDiagonal.x, blurDiagonal.y),
                    float2(0.0),
                    uResolution
                )
            ).rgb;
            float3 frostedWallpaper = (
                blurA + blurB + blurC + blurD +
                blurE + blurF + blurG + blurH
            ) * 0.125;

            // Extract bright energy before averaging so bright wallpaper bands
            // create a soft, low-frequency glow through the glass instead of
            // remaining only as sharp refracted lines.
            float brightA = smoothstep(
                0.14, 0.52,
                max(blurA.r, max(blurA.g, blurA.b))
            );
            float brightB = smoothstep(
                0.14, 0.52,
                max(blurB.r, max(blurB.g, blurB.b))
            );
            float brightC = smoothstep(
                0.14, 0.52,                max(blurC.r, max(blurC.g, blurC.b))
            );
            float brightD = smoothstep(
                0.14, 0.52,
                max(blurD.r, max(blurD.g, blurD.b))
            );
            float brightE = smoothstep(
                0.14, 0.52,
                max(blurE.r, max(blurE.g, blurE.b))
            );
            float brightF = smoothstep(
                0.14, 0.52,
                max(blurF.r, max(blurF.g, blurF.b))
            );
            float brightG = smoothstep(
                0.14, 0.52,
                max(blurG.r, max(blurG.g, blurG.b))
            );
            float brightH = smoothstep(
                0.14, 0.52,
                max(blurH.r, max(blurH.g, blurH.b))
            );
            float3 glassBrightBloom = (
                blurA * brightA + blurB * brightB +
                blurC * brightC + blurD * brightD +
                blurE * brightE + blurF * brightF +
                blurG * brightG + blurH * brightH
            ) * 0.125;
            // Keep the green channel on the central refracted ray. Red and
            // blue take tiny opposite offsets along the same optical path,
            // producing restrained chromatic dispersion rather than a broad
            // painted rainbow fringe.
            float dispersionAmount = mix(
                3.2,
                5.8,
                smoothstep(0.10, 0.48, glassProfileSlope)
            );
            float2 dispersionOffset = refractedRay.xy * dispersionAmount;
            float3 glassRedSample = uWallpaper.eval(clamp(
                samplePoint + dispersionOffset,
                float2(0.0),
                uResolution
            )).rgb;
            float3 glassBlueSample = uWallpaper.eval(clamp(
                samplePoint - dispersionOffset,
                float2(0.0),
                uResolution
            )).rgb;
            float3 refractedGlass = float3(
                glassRedSample.r,
                center.g,
                glassBlueSample.b
            );

            // Directional microfacet transmission: estimate the local wallpaper
            // edge normal, then diffuse ACROSS high-contrast bands rather than
            // along them. This merges paired white streaks into a coherent
            // optical body while retaining their direction and larger detail.
            // It is one image-driven material response shared by every glyph.
            float2 diffusionProbe = float2(4.0, 4.0);
            float3 probeLeft = uWallpaper.eval(clamp(
                samplePoint - float2(diffusionProbe.x, 0.0),
                float2(0.0),
                uResolution
            )).rgb;
            float3 probeRight = uWallpaper.eval(clamp(
                samplePoint + float2(diffusionProbe.x, 0.0),
                float2(0.0),
                uResolution
            )).rgb;
            float3 probeTop = uWallpaper.eval(clamp(
                samplePoint - float2(0.0, diffusionProbe.y),
                float2(0.0),
                uResolution
            )).rgb;
            float3 probeBottom = uWallpaper.eval(clamp(
                samplePoint + float2(0.0, diffusionProbe.y),
                float2(0.0),
                uResolution
            )).rgb;
            // A full-RGB structure tensor finds the dominant local colour-edge
            // normal. Unlike luminance-gradient signs, its eigenvector remains
            // stable on saturated blue/black transitions and mixed-colour bands.
            // The principal direction is the cross-band sampling axis.
            float3 colourGradientX = probeRight - probeLeft;
            float3 colourGradientY = probeBottom - probeTop;
            float tensorXX = dot(colourGradientX, colourGradientX);
            float tensorYY = dot(colourGradientY, colourGradientY);
            float tensorXY = dot(colourGradientX, colourGradientY);
            float tensorDiscriminant = sqrt(max(
                (tensorXX - tensorYY) * (tensorXX - tensorYY) +
                    4.0 * tensorXY * tensorXY,
                0.0
            ));
            float tensorLambda = 0.5 *
                (tensorXX + tensorYY + tensorDiscriminant);
            float2 tensorVectorX = float2(
                tensorXY,
                tensorLambda - tensorXX
            );
            float2 tensorVectorY = float2(
                tensorLambda - tensorYY,
                tensorXY
            );
            float2 diffusionDirection = mix(
                tensorVectorX,
                tensorVectorY,
                step(tensorYY, tensorXX)
            );
            float diffusionGradientLength = length(diffusionDirection);
            float2 diffusionAxis = diffusionDirection /
                max(diffusionGradientLength, 0.0001);
            float wallpaperContrast = sqrt(max(tensorLambda, 0.0));
            float bandContrast = smoothstep(0.08, 0.55, wallpaperContrast);

            // Rotate the short-range atmospheric kernel to the local wallpaper
            // structure. The principal tensor axis follows the strongest
            // colour-edge normal; its perpendicular follows the band itself.
            // This keeps the same material response coherent on diagonal,
            // vertical, horizontal and irregular wallpapers without any
            // digit-, clock-time- or screen-region-specific rules.
            float2 diffusionTangent = float2(-diffusionAxis.y, diffusionAxis.x);
            float2 glassNearX = diffusionAxis * 28.0;
            float2 glassNearY = diffusionTangent * 28.0;
            float3 glassNearA = uWallpaper.eval(clamp(
                samplePoint + glassNearX, float2(0.0), uResolution
            )).rgb;
            float3 glassNearB = uWallpaper.eval(clamp(
                samplePoint - glassNearX, float2(0.0), uResolution
            )).rgb;
            float3 glassNearC = uWallpaper.eval(clamp(
                samplePoint + glassNearY, float2(0.0), uResolution
            )).rgb;
            float3 glassNearD = uWallpaper.eval(clamp(
                samplePoint - glassNearY, float2(0.0), uResolution
            )).rgb;
            // Use an anisotropic kernel: most of the short-range energy
            // crosses the dominant wallpaper bands, while a smaller portion
            // travels along them. This softens black/white band boundaries
            // without smearing the colour structure into an isotropic blur.
            // The same tensor-derived orientation and weights apply everywhere.
            float3 glassCrossBandTransport =
                (glassNearA + glassNearB) * 0.5;
            float3 glassAlongBandTransport =
                (glassNearC + glassNearD) * 0.5;
            // Keep the #81 anisotropic kernel as the stable fallback,
            // and adapt its directional strength using bandContrast, an
            // already-computed and already-used scalar. This avoids adding
            // another tensor-derived expression to the AGSL program while
            // still easing toward isotropic diffusion in low-structure areas.
            float crossBandWeight = mix(0.52, 0.70, bandContrast);
            float alongBandWeight = 1.0 - crossBandWeight;
            float3 glassNearTransport =
                glassCrossBandTransport * crossBandWeight +
                glassAlongBandTransport * alongBandWeight;
            // Blend direct refracted light, adaptive near-field diffusion and
            // broad atmosphere into one shared optical transport target.
            float3 glassAtmosphere = clamp(
                glassRayTransmission * 0.34 +
                    center * 0.10 +
                    glassNearTransport * 0.26 +
                    frostedWallpaper * 0.30,
                0.0,
                1.0
            );
            // Diffuse across (not along) high-contrast wallpaper bands.
            // The larger footprint merges paired streaks into a continuous
            // glass volume, while low-contrast areas retain sharper detail.
            float diffusionRadius = mix(30.0, 42.0, bandContrast);
            float2 nearDiffusionOffset = diffusionAxis * diffusionRadius * 0.62;
            float2 farDiffusionOffset = diffusionAxis * diffusionRadius * 1.38;
            float3 softNearA = uWallpaper.eval(clamp(
                samplePoint - nearDiffusionOffset,
                float2(0.0),
                uResolution
            )).rgb;
            float3 softNearB = uWallpaper.eval(clamp(
                samplePoint + nearDiffusionOffset,
                float2(0.0),
                uResolution
            )).rgb;
            float3 softFarA = uWallpaper.eval(clamp(
                samplePoint - farDiffusionOffset,
                float2(0.0),
                uResolution
            )).rgb;
            float3 softFarB = uWallpaper.eval(clamp(
                samplePoint + farDiffusionOffset,
                float2(0.0),
                uResolution
            )).rgb;
            // Integrate the directional microfacet bundle in linear light
            // as well. These four samples are the main cross-band transport
            // that turns narrow wallpaper rails into a continuous glass body.
            // Averaging display-encoded RGB here biases the result toward dark,
            // saturated edges and can leave the rails visually separated.
            float3 microfacetTransmissionLinear =
                centerLinear * 0.08 +
                (
                    toLinearSrgb(softNearA) +
                    toLinearSrgb(softNearB)
                ) * 0.24 +
                (
                    toLinearSrgb(softFarA) +
                    toLinearSrgb(softFarB)
                ) * 0.22;
            float3 microfacetTransmission = clamp(
                fromLinearSrgb(microfacetTransmissionLinear),
                0.0,
                1.0
            );

            // Warm environmental light (orange/amber) is easily lost when
            // blue and white bands dominate the local transport average.
            // Detect warm chroma from the linear-light bundle and gently
            // increase its diffuse contribution without tinting neutral or
            // blue regions. This is a material response to sampled wallpaper
            // colour, not a fixed orange light or a glyph-specific effect.
            float warmLightSignal = max(
                microfacetTransmissionLinear.r -
                    microfacetTransmissionLinear.b * 0.82,
                0.0
            );
            float warmLightPresence = smoothstep(
                0.025,
                0.16,
                warmLightSignal
            ) * smoothstep(
                0.045,
                0.22,
                microfacetTransmissionLinear.r
            );
            float3 warmLightTransportLinear =
                microfacetTransmissionLinear *
                    (1.0 + warmLightPresence * 0.16) +
                float3(0.010, 0.004, 0.0) * warmLightPresence;
            float3 warmLightTransport = clamp(
                fromLinearSrgb(warmLightTransportLinear),
                0.0,
                1.0
            );

            // The structure-tensor eigenvector is reliable for a single,
            // coherent band direction, but becomes unstable at crossings,
            // paired edges and locally isotropic texture. Measure tensor
            // coherence and fall back smoothly toward a colour-preserving
            // isotropic transport sample when no dominant direction exists.
            // This avoids streaky direction flips without widening the probe
            // or applying a glyph/coordinate-specific correction.
            float tensorTrace = max(tensorXX + tensorYY, 0.0001);
            float tensorCoherence = clamp(
                tensorDiscriminant / tensorTrace,
                0.0,
                1.0
            );
            float directionalConfidence = smoothstep(
                0.14,
                0.58,
                tensorCoherence
            );
            float3 isotropicFallbackTransmission = mix(
                center,
                glassAtmosphere,
                0.72
            );
            // A strong, coherent edge can still be too narrow to read as
            // a single glass transmission feature: the diagonal wallpaper has
            // closely spaced black/white rails. In those high-contrast areas,
            // temper the directional ray bundle and let more of the broad,
            // colour-preserving environment sample participate. This is an
            // optical edge response, not a digit-specific correction.
            float pairedBandSoftening = mix(
                1.0,
                0.32,
                bandContrast
            );
            float stableDirectionWeight = mix(
                0.34,
                1.0,
                directionalConfidence
            ) * pairedBandSoftening;
            microfacetTransmission = mix(
                isotropicFallbackTransmission,
                microfacetTransmission,
                stableDirectionWeight
            );

            float microfacetDiffusion = mix(
                0.42,
                0.96,
                bandContrast
            ) * mix(
                0.96,
                1.0,
                smoothstep(0.04, 0.32, glassProfileSlope)
            );
            refractedGlass = mix(
                refractedGlass,
                microfacetTransmission,
                microfacetDiffusion
            );

            float refractedLuma = dot(
                refractedGlass,
                float3(0.2126, 0.7152, 0.0722)
            );
            // Retain saturation in ordinary areas, but do not re-amplify
            // high-contrast wallpaper bands after the common diffusion pass.
            // The adjustment is image-driven and identical for every glyph.
            float glassTransmissionSaturation = mix(
                1.42,
                1.08,
                bandContrast
            );
            float3 saturatedRefractedGlass = clamp(
                mix(
                    float3(refractedLuma),
                    refractedGlass,
                    glassTransmissionSaturation
                ),
                0.0,
                1.0
            );
            // Rich internal light pickup: dark regions gather nearby colour
            // from the wallpaper, while bright beach/sky regions remain clear.
            // Requiring local blurred illumination avoids a milky veil where
            // the surrounding wallpaper is already uniformly bright.
            float blurredWallpaperLuma = dot(
                frostedWallpaper,
                float3(0.2126, 0.7152, 0.0722)
            );
            // Detect illumination by RGB channel energy, not luminance alone.
            // Saturated blue and red/orange light can be visually strong while
            // contributing less to luma than white at the same channel level.
            float blurredWallpaperEnergy = max(
                frostedWallpaper.r,
                max(frostedWallpaper.g, frostedWallpaper.b)
            );
            float glassDarkness = 1.0 - smoothstep(0.08, 0.24, refractedLuma);
            float glassNearbyLight = smoothstep(0.10, 0.32, blurredWallpaperEnergy);
            float glassBounceStrength =
                0.24 * glassDarkness * glassNearbyLight;
            float3 brightGlassBounce = clamp(
                glassAtmosphere * 1.34 + float3(0.012, 0.014, 0.022),
                0.0,
                1.0
            );
            float3 screenedGlassFace = 1.0 -
                (1.0 - saturatedRefractedGlass) *
                (1.0 - brightGlassBounce);
            float3 glassTransmitted = mix(
                saturatedRefractedGlass,
                screenedGlassFace,
                glassBounceStrength
            );
            // Preserve the directional, colour-bearing microfacet scatter:
            // it carries the wallpaper's local orange/blue light through the
            // glass instead of replacing it with an over-averaged grey/blue wash.
            float microfacetLuma = dot(
                microfacetTransmission,
                float3(0.2126, 0.7152, 0.0722)
            );
            // Use peak channel energy to activate colour-bearing volume scatter
            // consistently for saturated blue, warm orange and neutral white.
            // Keep the luminance-based white guard to prevent a milky result.
            float microfacetEnergy = max(
                microfacetTransmission.r,
                max(microfacetTransmission.g, microfacetTransmission.b)
            );
            float scatterInLitGlass = smoothstep(0.10, 0.34, microfacetEnergy);
            float scatterWhiteProtection = 1.0 -
                0.42 * smoothstep(0.72, 0.94, microfacetLuma);
            float glassVolumeScatter = 0.28 *
                scatterInLitGlass *
                scatterWhiteProtection *
                smoothstep(0.12, 0.60, glassVolumeRegion);
            float3 volumeScatteredTransmission = clamp(
                mix(
                    microfacetTransmission,
                    warmLightTransport,
                    warmLightPresence * 0.42
                ) * 1.10 +
                    float3(0.016, 0.020, 0.028),
                0.0,
                1.0
            );
            glassTransmitted = mix(
                glassTransmitted,
                volumeScatteredTransmission,
                glassVolumeScatter
            );
            // Use a two-scale in-scattering target: broad isotropic wallpaper
            // haze supplies atmospheric softness, while the cross-band
            // microfacet sample preserves the local orange/blue light path.
            // This avoids the colour wash from a blur-only target and avoids
            // the overly sharp direct-only result.
            // Carry the detected warm environmental energy into the
            // broad diffuse pass as well as the local volume-scatter pass.
            // This gives orange/amber light a softer spatial contribution
            // through the body instead of concentrating it at the microfacet
            // sampling locations. The gate is derived from sampled wallpaper
            // chroma, so blue and neutral environments retain their balance.
            float3 warmAtmosphericTransport = mix(
                glassAtmosphere,
                warmLightTransport,
                warmLightPresence * 0.30
            );
            float3 glassDiffuseTransport = mix(
                warmAtmosphericTransport,
                microfacetTransmission,
                0.18
            );
            float glassAtmosphericCoverage = smoothstep(
                0.10,
                0.60,
                glassVolumeRegion
            );
            // Increase the common diffuse transport only where the wallpaper
            // contains strong local bands. The same image-driven response is
            // used by every stroke, independent of glyph identity or width.
            float glassBandHaze = smoothstep(0.18, 0.72, bandContrast);
            float glassAtmosphericScatter =
                mix(0.68, 0.80, glassBandHaze) *
                glassAtmosphericCoverage;
            glassTransmitted = mix(
                glassTransmitted,
                glassDiffuseTransport,
                glassAtmosphericScatter
            );
            // Let bright colour energy spread slightly farther through
            // high-contrast regions, while retaining the direct chromatic
            // transmission and the external light-wrap added in #61.
            float glassAtmosphericBloom =
                mix(0.30, 0.38, glassBandHaze) *
                glassAtmosphericCoverage;
            glassTransmitted = 1.0 -
                (1.0 - glassTransmitted) *
                (1.0 - glassBrightBloom * glassAtmosphericBloom);
            // Solid keeps its prior diffuse/frosted source path.
            float3 refracted = mix(glassTransmitted, center, solidMode);
            float3 milkyFrost = mix(
                frostedWallpaper,
                float3(0.94, 0.95, 0.98),
                0.20
            );
            milkyFrost = mix(milkyFrost, uAccentColor, 0.07);

            // Clear glass still reflects its environment. Mix a restrained
            // wallpaper-coloured reflection into the face, then increase it
            // toward the curved shoulder using Fresnel. This gives the front
            // surface a visible optical body without a white/frosted overlay.
            float environmentReflectionLuma = dot(
                environmentReflection,
                float3(0.2126, 0.7152, 0.0722)
            );
            float3 environmentReflectionChroma =
                environmentReflection - float3(environmentReflectionLuma);
            float reflectionHighlightProtection = 1.0 -
                0.58 * smoothstep(0.42, 0.78, environmentReflectionLuma);
            float3 chromaticEnvironmentReflection = clamp(
                float3(environmentReflectionLuma) +
                    environmentReflectionChroma * 1.28,
                0.0,
                1.0
            );
            float glassFaceReflectivity =
                glassVolumeFresnel * 0.64 * reflectionHighlightProtection;
            // Retain the colour-bearing environment reflection. The broad
            // transmitted haze is handled by the glass volume below/above;
            // blurring this reflection separately was washing out the warm
            // orange and cool blue cues that make the surface feel liquid.
            float3 glassFace = mix(
                refracted,
                chromaticEnvironmentReflection,
                glassFaceReflectivity
            );
            float innerGlassTint = smoothstep(0.0, 12.0, insideDistance) *
                (1.0 - smoothstep(26.0, 64.0, insideDistance));
            glassFace = mix(
                glassFace,
                mix(uAccentColor, glassFace, 0.78),
                innerGlassTint * 0.08
            );
            glassFace = mix(glassFace, uClockTint, uTintStrength);
            float3 frostedFace = mix(
                milkyFrost,
                uClockTint,
                uTintStrength * 0.78
            );
            float3 body = mix(glassFace, frostedFace, solidMode);

            float2 keyLight = normalize(float2(-0.58, -0.78) + tilt * 0.62);
            float2 fillLight = normalize(float2(0.70, 0.64) - tilt * 0.24);
            float facing = max(dot(normal, keyLight), 0.0);
            float oppositeFacing = max(dot(normal, fillLight), 0.0);

            float legacyFresnel = pow(
                1.0 - clamp(abs(dot(normal, keyLight)), 0.0, 1.0),
                2.6
            ) * edgeBand;
            float fresnel = mix(glassVolumeFresnel, legacyFresnel, solidMode);
            float solidSpecular = pow(facing, 3.5) * solidEdge;
            float solidFillSpecular = pow(oppositeFacing, 3.2) * solidEdge;
            float bevelShade = solidBevel * (1.0 - facing) *
                0.11 * solidMode;

            float3 colour = body;
            // Lift the transmissive face so wallpaper colour remains vivid
            // through the glyph instead of reading as a dark cut-out. This
            // multiplier is blended out completely for Solid/Frosted.
            float glassFaceLighting = 0.82 +
                keyDiffuse3D * 0.30 +
                fillDiffuse3D * 0.16;
            colour *= mix(glassFaceLighting, 1.0, solidMode);
            float directionalReflectionLuma = dot(
                directionalEnvironmentReflection,
                float3(0.2126, 0.7152, 0.0722)
            );
            float3 directionalReflectionChroma =
                directionalEnvironmentReflection -
                    float3(directionalReflectionLuma);
            float glassFaceLuma = dot(
                colour,
                float3(0.2126, 0.7152, 0.0722)
            );
            float glassFaceDarkness =
                1.0 - smoothstep(0.18, 0.52, glassFaceLuma);
            float glassFaceReflectionAmount = mix(
                0.035,
                0.20,
                smoothstep(0.08, 0.48, glassProfileSlope)
            ) * glassHorizontalHighlight;
            float glassChromaPickup = smoothstep(
                0.025,
                0.16,
                length(directionalReflectionChroma)
            );
            float glassReflectionLumaLift =
                max(directionalReflectionLuma - glassFaceLuma, 0.0) *
                glassFaceDarkness * glassFaceReflectionAmount * 0.18;
            colour = clamp(
                colour +
                    directionalReflectionChroma *
                        glassFaceReflectionAmount * glassChromaPickup * 1.34 +
                    float3(glassReflectionLumaLift),
                0.0,
                1.0
            );
            colour -= float3(0.12, 0.15, 0.22) *
                glassSideShadow * (1.0 - solidMode) * 0.22;
            colour *= mix(0.985, 1.025, glassLens);
            colour += uAccentColor * fresnel * mix(0.68, 0.15, solidMode);

            // Preserve the separate Frosted Glass treatment. Liquid Glass
            // does not use an independent white bevel or dark perimeter.
            colour += float3(0.98, 0.99, 1.0) *
                solidBevel * 0.14 * solidMode;
            colour += float3(1.0, 1.0, 1.0) *
                solidSpecular * 0.62 * solidMode;
            colour += uAccentColor *
                solidFillSpecular * 0.15 * solidMode;

            // Fresnel adds the stronger grazing-angle reflection on top of
            // the subtler face reflection. The broad specular remains visible
            // on the front cap; tight glints stay confined to its shoulder.
            float finalFresnelAmount =
                glassVolumeFresnel * 0.15 * (1.0 - solidMode);
            colour = clamp(
                colour +
                    environmentReflectionChroma * finalFresnelAmount * 0.72,
                0.0,
                1.0
            );
            float glassRimLighting = glassRim *
                pow(max(dot(glassSurfaceNormal, keyLight3D), 0.0), 1.35) * glassHorizontalHighlight;
            colour += float3(0.92, 0.97, 1.0) *
                glassAreaReflection * (1.0 - solidMode) * 0.96;
            colour += float3(0.98, 0.99, 1.0) *
                glassKeyGlint * (1.0 - solidMode) * 1.28;
            colour += float3(0.68, 0.82, 1.0) *
                glassFillGlint * (1.0 - solidMode) * 0.88;
            colour += float3(0.96, 0.98, 1.0) *
                glassRimLighting * (1.0 - solidMode) * 0.20;

            // Unified secondary bevel reflection: every glyph uses the same
            // height-map normal, curvature gate and light/view half-vector.
            // This naturally catches stroke ends and curved shoulders without
            // hard-coded digit identities or position-specific highlight axes.
            float glassBevelSpecular = pow(
                max(dot(glassSurfaceNormal, keyHalfVector), 0.0),
                18.0
            ) * smoothstep(0.08, 0.42, glassProfileSlope) *
                normalConfidence *
                mix(1.0, 0.72, horizontalShoulder);
            colour += environmentReflectionChroma *
                glassBevelSpecular * 0.42 * (1.0 - solidMode);
            colour += float3(0.80, 0.91, 1.0) *
                glassBevelSpecular * 0.12 * (1.0 - solidMode);

            // Ambient glass scatter keeps digits legible over pure-black
            // wallpaper regions without laying a gray veil over bright images.
            // The vertical silver-blue gradient is strongest near the upper
            // half, and is confined to the inner face rather than its rim.
            float ambientClockY = p.y - uClockCenter.y * uResolution.y;
            float ambientVertical = clamp(
                0.5 + ambientClockY / max(uResolution.y * 0.17, 1.0),
                0.0,
                1.0
            );
            float3 ambientGradient = mix(
                float3(0.34, 0.37, 0.43),
                float3(0.21, 0.24, 0.30),
                ambientVertical
            );
            // Keep the dark-area lift chromatic instead of laying a neutral
            // gray veil over dark blue wallpaper visible through the glass.
            float3 ambientEnvironment = mix(
                ambientGradient,
                frostedWallpaper,
                0.72
            );
            float ambientFaceLuma = dot(
                colour,
                float3(0.2126, 0.7152, 0.0722)
            );
            float ambientDarkness = 1.0 -
                smoothstep(0.025, 0.20, ambientFaceLuma);
            float ambientInteriorMask = smoothstep(
                0.10,
                0.62,
                glassVolumeRegion
            );
            float ambientScatter = 0.42 *
                ambientDarkness *
                ambientInteriorMask *
                (1.0 - solidMode);
            colour = mix(
                colour,
                ambientEnvironment,
                ambientScatter
            );

            colour -= float3(0.13, 0.15, 0.21) * bevelShade;
            // These perimeter strokes belong only to Frosted Glass; applying
            // them to Liquid Glass made it look like embossed text.
            colour -= float3(0.08, 0.09, 0.14) *
                edgeBand * (1.0 - facing) * 0.055 * solidMode;
            colour += float3(1.0, 1.0, 1.0) *
                outerLip * 0.07 * solidMode;

            // Keep a restrained rim only for the Frosted Glass option.
            float solidRim = exp(-abs(signedDistance - 2.5) / 4.6) *
                pow(max(dot(normal, keyLight), 0.0), 1.45);
            colour += float3(1.0, 1.0, 1.0) *
                solidRim * 0.25 * solidMode;

            // Frosted Glass keeps its separate, subdued satin highlight.
            // This coordinate ribbon is retained only for that material.
            float2 clockRelative = p - uClockCenter * uResolution;
            float reflectionAxis = clockRelative.x * -0.42 +
                clockRelative.y * 0.91 + sin(uTime * 0.16) * 18.0;
            float solidSheen = exp(
                -pow((reflectionAxis - 18.0) / 66.0, 2.0)
            ) * glassLens;
            colour += float3(1.0, 1.0, 1.0) *
                solidSheen * solidMode * 0.16;

            // The shader replaces the backdrop pixels inside the glyph with
            // their refracted samples. Full mask alpha is required; the glass
            // remains optically clear because its RGB is the warped wallpaper,
            // not a white translucent paint layer.
            float glassOpacity = 1.0;
            float solidOpacity = 0.78;
            float bodyOpacity = mix(glassOpacity, solidOpacity, solidMode);
            float bodyAlpha = clamp(
                coverage * (
                    bodyOpacity +
                    edgeBand * mix(0.31, 0.10, solidMode) +
                    outerLip * mix(0.035, 0.07, solidMode)
                ),
                0.0,
                1.0
            );

            // A restrained chromatic light-wrap sits OUTSIDE the glyph mask.
            // It is driven by nearby bright wallpaper colour, not a neutral
            // white outline or a cast shadow, and fades over a short distance.
            // It is intentionally Liquid-Glass-only; Frosted remains unchanged.
            float glassWrapDistance = exp(-outsideDistance / 7.5) *
                (1.0 - smoothstep(12.0, 18.0, outsideDistance));
            float glassWrapEnergy = max(
                glassBrightBloom.r,
                max(glassBrightBloom.g, glassBrightBloom.b)
            );
            float glassWrapActivation = smoothstep(
                0.06,
                0.24,
                glassWrapEnergy
            );
            float glassWrapOutsideMask = 1.0 -
                smoothstep(0.0, 1.0, coverage);
            float glassLightWrapAlpha =
                0.14 *
                glassWrapDistance *
                glassWrapActivation *
                glassWrapOutsideMask *
                (1.0 - solidMode);
            float3 glassLightWrapColour = clamp(
                glassBrightBloom * 0.72 +
                    microfacetTransmission * 0.28,
                0.0,
                1.0
            );
            float alpha = clamp(
                bodyAlpha + glassLightWrapAlpha * (1.0 - bodyAlpha),
                0.0,
                1.0
            );

            // RuntimeShader outputs use premultiplied alpha. The halo is
            // composited as a separate low-alpha light contribution so it
            // blends with the original wallpaper rather than replacing it.
            float3 premultipliedColour =
                clamp(colour, 0.0, 1.0) * bodyAlpha +
                glassLightWrapColour *
                    glassLightWrapAlpha * (1.0 - bodyAlpha);
            return half4(premultipliedColour, alpha);
        }
    """.trimIndent()
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var backdrop: Bitmap? = null
    private var wallpaperSample: Bitmap? = null
    private var foregroundOcclusionMask: Bitmap? = null
    private var glyphMask: Bitmap? = null
    private var colonMask: Bitmap? = null
    private var colonMaskOriginX = 0f
    private var colonMaskOriginY = 0f
    private var glyphSdf: Bitmap? = null
    private val retiredGlyphMasks = mutableListOf<Bitmap>()
    private val retiredColonMasks = mutableListOf<Bitmap>()
    private val retiredGlyphSdfs = mutableListOf<Bitmap>()
    private var glyphSdfOriginX = 0f
    private var glyphSdfOriginY = 0f
    private var glyphSdfScale = 1f
    private var glyphSdfStale = false
    private var deviceTiltX = 0f
    private var deviceTiltY = 0f    private var adaptiveAccentColor: Int = Color.rgb(222, 130, 177)
    private var clockFontStyle = 0
    private var clockFontWeight = 640
    private var clockColor = Color.WHITE
    private var glassMode = true
    private var glassShader: RuntimeShader? = null
    private var shaderFailureReported = false
    private var cachedTimeText: String? = null
    private var clockCenterX = DEFAULT_CLOCK_X
    private var clockCenterY = DEFAULT_CLOCK_Y
    private var clockScale = 1f
    private var maskClockScale = 1f
    private var pinchStartClockScale = 1f
    private var clockWidthScale = 1f
    private var maskClockWidthScale = 1f
    private var pinchStartClockWidthScale = 1f
    private var gestureOffsetX = 0f
    private var gestureOffsetY = 0f
    private var previewScaleX = 1f
    private var previewScaleY = 1f

    private val glyphBaseBounds = RectF()
    private val glyphBounds = RectF()
    private val glyphBoundsSource = Rect()
    private val fallbackTypeface: Typeface = loadFallbackTypeface()
    private val typefaceCache = LinkedHashMap<Int, Typeface>()

    private val bitmapPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val shaderPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val shadowPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        colorFilter = PorterDuffColorFilter(
            Color.argb(38, 3, 9, 22),
            PorterDuff.Mode.SRC_IN
        )
    }

    private val clockOcclusionPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private val fallbackGlyphPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        color = Color.WHITE
        alpha = 218
    }

    private val clockLayerBounds = RectF()

    private val clockPreferences = context.getSharedPreferences(
        wallpaperContent.compositionPreferencesName,
        Context.MODE_PRIVATE
    )

    init {
        clockCenterX = clockPreferences.getFloat("center_x", DEFAULT_CLOCK_X)
            .coerceIn(0.10f, 0.90f)
        val savedCenterY = clockPreferences.getFloat("center_y", LEGACY_DEFAULT_CLOCK_Y)
        val positionMigrationDone = clockPreferences.getBoolean("reference_position_v1", false)
        val studioPositionMigrationDone =
            clockPreferences.getBoolean("clock_studio_safe_position_v2", false)
        // Reposition only the old untouched defaults so the entire live clock
        // remains visible above the appearance sheet. Preserve custom positions.
        clockCenterY = if (
            !studioPositionMigrationDone &&
            (savedCenterY == PREVIOUS_DEFAULT_CLOCK_Y ||
                (!positionMigrationDone && savedCenterY == LEGACY_DEFAULT_CLOCK_Y))
        ) {
            DEFAULT_CLOCK_Y
        } else {
            savedCenterY.coerceIn(0.10f, 0.78f)
        }
        clockScale = clockPreferences.getFloat("clock_scale", 1f)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
        clockWidthScale = clockPreferences.getFloat("clock_width_scale", 1f)
            .coerceIn(MIN_CLOCK_WIDTH_SCALE, MAX_CLOCK_WIDTH_SCALE)
        readAppearancePreferences()
        if (!positionMigrationDone || !studioPositionMigrationDone) {
            clockPreferences.edit()
                .putBoolean("reference_position_v1", true)
                .putBoolean("clock_studio_safe_position_v2", true)
                .putFloat("center_y", clockCenterY)
                .apply()
        }
    }

    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight && backdrop != null) return

        surfaceWidth = width
        surfaceHeight = height
        rebuildBackdrop()
    }

    fun reloadWallpaper() {
        rebuildBackdrop()
    }

    fun reloadAppliedState() {
        clockCenterX = clockPreferences.getFloat("center_x", DEFAULT_CLOCK_X)
            .coerceIn(0.10f, 0.90f)
        clockCenterY = clockPreferences.getFloat("center_y", DEFAULT_CLOCK_Y)
            .coerceIn(0.10f, 0.78f)
        clockScale = clockPreferences.getFloat("clock_scale", 1f)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
        clockWidthScale = clockPreferences.getFloat("clock_width_scale", 1f)
            .coerceIn(MIN_CLOCK_WIDTH_SCALE, MAX_CLOCK_WIDTH_SCALE)
        readAppearancePreferences()
        rebuildBackdrop()
    }

    /**
     * Apply the Font & Color sheet without restarting the wallpaper service.
     * Typeface and colour settings are stored alongside the clock composition.
     */
    fun reloadAppearance(rebuildDistanceField: Boolean = true) {
        diagnostics.record("Renderer reload start: sdf=$rebuildDistanceField surface=${surfaceWidth}x${surfaceHeight}")
        val previousStyle = clockFontStyle
        val previousWeight = clockFontWeight
        val previousColor = clockColor
        val previousGlassMode = glassMode

        readAppearancePreferences()
        val glyphAppearanceChanged =
            previousStyle != clockFontStyle || previousWeight != clockFontWeight

        if (glyphAppearanceChanged) {
            typefaceCache.clear()
            cachedTimeText = null
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                try {
                    rebuildGlyphMask(
                        force = true,
                        rebuildDistanceField = rebuildDistanceField
                    )
                } catch (memoryError: OutOfMemoryError) {
                    diagnostics.report("renderer-glyph-mask-out-of-memory", memoryError)
                    Log.e(TAG, "Clock weight preview ran out of memory; keeping the last rendered mask.", memoryError)
                } catch (exception: RuntimeException) {
                    diagnostics.report("renderer-glyph-mask", exception)
                    Log.e(TAG, "Clock weight preview could not rebuild its glyph mask.", exception)
                }
            }
        } else if (rebuildDistanceField && glyphSdfStale && surfaceWidth > 0) {
            rebuildGlyphSdf()
        }

        if (glassShader == null && surfaceWidth > 0 && surfaceHeight > 0) {
            rebuildGlassShader()
        } else if (
            glyphAppearanceChanged ||
            previousColor != clockColor ||
            previousGlassMode != glassMode
        ) {
            updateGlassShaderAppearance()
        }
    }

    private fun updateGlassShaderAppearance() {
        val shader = glassShader ?: return
        try {
            shader.setFloatUniform(
                "uAccentColor",
                Color.red(adaptiveAccentColor) / 255f,
                Color.green(adaptiveAccentColor) / 255f,
                Color.blue(adaptiveAccentColor) / 255f
            )
            shader.setFloatUniform(
                "uClockTint",
                Color.red(clockColor) / 255f,
                Color.green(clockColor) / 255f,
                Color.blue(clockColor) / 255f
            )
            shader.setFloatUniform(
                "uTintStrength",
                if (clockColor == Color.WHITE) 0f else 0.16f
            )
            shader.setFloatUniform("uFrosted", if (glassMode) 0f else 1f)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not update clock material uniforms.", exception)
            rebuildGlassShader()
        }
    }

    private fun readAppearancePreferences() {
        clockFontStyle = clockPreferences.getInt("clock_font_style", 0).coerceIn(0, 3)
        clockFontWeight = clockPreferences.getInt("clock_font_weight", 400)
            .coerceIn(100, 900)
        clockColor = clockPreferences.getInt("clock_color", Color.WHITE)
        glassMode = clockPreferences.getBoolean("clock_glass_mode", true)
    }

    fun isClockHit(x: Float, y: Float): Boolean {
        if (glyphBounds.isEmpty) return false
        val hit = RectF(glyphBounds)
        val margin = (context.resources.displayMetrics.density * 44f)
        hit.inset(-margin, -margin)
        return hit.contains(x, y)
    }

    fun moveClockBy(deltaX: Float, deltaY: Float) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        val previousX = clockCenterX
        val previousY = clockCenterY
        clockCenterX = (clockCenterX + deltaX / surfaceWidth)
            .coerceIn(0.10f, 0.90f)
        clockCenterY = (clockCenterY + deltaY / surfaceHeight)
            .coerceIn(0.10f, 0.78f)

        // Keep the cached glyph/SDF textures. Moving a canvas transform is
        // effectively free compared with rebuilding two full distance fields.
        gestureOffsetX += (clockCenterX - previousX) * surfaceWidth
        gestureOffsetY += (clockCenterY - previousY) * surfaceHeight
        updatePreviewBounds()
    }

    fun beginScaleGesture() {
        pinchStartClockScale = clockScale
    }

    fun scaleClock(distanceRatio: Float) {
        if (!distanceRatio.isFinite() || distanceRatio <= 0f) return
        // Use the original scale at pointer-down, not accumulated MOVE deltas.
        // That prevents event-rate jitter and lets every new pinch start cleanly.
        val requestedScale = (pinchStartClockScale * distanceRatio)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
        // Derive the pinch ceiling from the actual rendered glyph bounds,
        // not a guessed font-size fraction. This keeps the preview and final
        // committed mask on exactly the same vertical limit.
        val maxPreviewRatio = if (!glyphBaseBounds.isEmpty && surfaceHeight > 0) {
            (surfaceHeight * MAX_COMMITTED_HEIGHT_FRACTION /
                glyphBaseBounds.height().coerceAtLeast(1f)).coerceAtLeast(1f)
        } else {
            MAX_CLOCK_SCALE / maskClockScale.coerceAtLeast(0.01f)
        }
        previewScaleY = (requestedScale / maskClockScale.coerceAtLeast(0.01f))
            .coerceIn(0.25f, maxPreviewRatio)
        clockScale = (maskClockScale * previewScaleY)            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
        updatePreviewBounds()
    }

    fun beginWidthScaleGesture() {
        pinchStartClockWidthScale = clockWidthScale
    }

    fun scaleClockWidth(distanceRatio: Float) {
        if (!distanceRatio.isFinite() || distanceRatio <= 0f) return

        val maxAllowedScale = if (!glyphBaseBounds.isEmpty && surfaceWidth > 0) {
            (
                surfaceWidth * MAX_COMMITTED_WIDTH_FRACTION *
                    maskClockWidthScale /
                    glyphBaseBounds.width().coerceAtLeast(1f)
                ).coerceIn(MIN_CLOCK_WIDTH_SCALE, MAX_CLOCK_WIDTH_SCALE)
        } else {
            MAX_CLOCK_WIDTH_SCALE
        }

        clockWidthScale = (pinchStartClockWidthScale * distanceRatio)
            .coerceIn(MIN_CLOCK_WIDTH_SCALE, maxAllowedScale)
        previewScaleX = clockWidthScale / maskClockWidthScale.coerceAtLeast(0.01f)
        updatePreviewBounds()
    }

    fun finishGesture() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        rebuildGlyphMask(force = true)
    }

    private fun updatePreviewBounds() {
        if (glyphBaseBounds.isEmpty) {
            glyphBounds.setEmpty()
            return
        }
        glyphBounds.set(glyphBaseBounds)
        val transform = Matrix().apply {
            setScale(
                previewScaleX,
                previewScaleY,
                glyphBaseBounds.centerX(),
                glyphBaseBounds.centerY()
            )
        }
        transform.mapRect(glyphBounds)
        glyphBounds.offset(gestureOffsetX, gestureOffsetY)
    }

    fun setDeviceTilt(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        deviceTiltX += (x.coerceIn(-1f, 1f) - deviceTiltX) * 0.22f
        deviceTiltY += (y.coerceIn(-1f, 1f) - deviceTiltY) * 0.22f
    }

    fun persistComposition() {
        clockPreferences.edit()
            .putFloat("center_x", clockCenterX)
            .putFloat("center_y", clockCenterY)
            .putFloat("clock_scale", clockScale)
            .putFloat("clock_width_scale", clockWidthScale)
            .apply()
    }

    fun draw(canvas: Canvas, timeSeconds: Float) {
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        rebuildGlyphMask(force = false)

        // Keep the original selected wallpaper as the only background image.
        // The user selection is an alpha mask applied to the clock layer only.
        val background = backdrop ?: return
        canvas.drawBitmap(background, 0f, 0f, bitmapPaint)

        val mask = glyphMask ?: return
        if (glyphBounds.isEmpty) return

        val shadowOffset = if (glassMode) {
            max(4f, height * 0.0022f)
        } else {
            max(2f, height * 0.0015f)
        }
        shadowPaint.colorFilter = PorterDuffColorFilter(
            Color.argb(if (glassMode) 58 else 38, 3, 9, 22),
            PorterDuff.Mode.SRC_IN
        )
        clockLayerBounds.set(glyphBounds)
        val glassWrapPadding = if (glassMode) {
            GLASS_LIGHT_WRAP_RADIUS_PX * max(previewScaleX, previewScaleY)
        } else {
            0f
        }
        val layerPadding = max(shadowOffset * 2f, glassWrapPadding)
        clockLayerBounds.inset(-layerPadding, -layerPadding)
        clockLayerBounds.intersect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat()
        )

        // Draw the clock in isolation. DST_OUT then removes only the clock
        // pixels beneath the user-selected object. The untouched wallpaper
        // remains visible below, so the selected object never replaces it.
        val layerSave = canvas.saveLayer(clockLayerBounds, null)
        try {
            val transformSave = canvas.save()
            canvas.translate(gestureOffsetX, gestureOffsetY)
            canvas.scale(
                previewScaleX,
                previewScaleY,
                glyphBaseBounds.centerX(),
                glyphBaseBounds.centerY()
            )

            val shadowSave = canvas.save()
            canvas.translate(0f, shadowOffset)
            canvas.drawBitmap(mask, 0f, 0f, shadowPaint)
            canvas.restoreToCount(shadowSave)

            var shaderDrawn = false
            val shader = glassShader
            if (canvas.isHardwareAccelerated && shader != null) {
                try {
                    shader.setFloatUniform(
                        "uResolution",
                        width.toFloat(),
                        height.toFloat()
                    )
                    shader.setFloatUniform("uTime", timeSeconds)
                    shader.setFloatUniform("uTilt", deviceTiltX, deviceTiltY)
                    shader.setFloatUniform(
                        "uClockCenter",
                        glyphBaseBounds.centerX() / width.toFloat(),
                        glyphBaseBounds.centerY() / height.toFloat()
                    )
                    shaderPaint.shader = shader
                    val shaderDrawBounds = RectF(glyphBaseBounds)
                    if (glassMode) {
                        shaderDrawBounds.inset(
                            -GLASS_LIGHT_WRAP_RADIUS_PX,
                            -GLASS_LIGHT_WRAP_RADIUS_PX
                        )
                    }
                    canvas.drawRect(shaderDrawBounds, shaderPaint)
                    shaderPaint.shader = null
                    shaderDrawn = true
                } catch (exception: RuntimeException) {
                    shaderPaint.shader = null
                    if (!shaderFailureReported) {
                        shaderFailureReported = true
                        diagnostics.report("agsl-render-frame", exception)
                    }
                    Log.w(TAG, "AGSL rendering failed; showing a visible clock fallback.", exception)
                }
            }

            // During a pinch/drag, transform the cached mask and shader rather
            // than regenerating their pixel data on the UI thread.
            if (!shaderDrawn) {
                fallbackGlyphPaint.color = clockColor
                fallbackGlyphPaint.alpha = if (glassMode) 218 else 232
                canvas.drawBitmap(mask, 0f, 0f, fallbackGlyphPaint)
            }
            canvas.restoreToCount(transformSave)

            // Keep the selected foreground object fixed in wallpaper space;
            // only the clock layer is transformed beneath its depth mask.
            foregroundOcclusionMask?.let { occlusionMask ->
                canvas.drawBitmap(occlusionMask, 0f, 0f, clockOcclusionPaint)
            }
        } finally {
            canvas.restoreToCount(layerSave)
            shaderPaint.shader = null
        }
    }

    fun release() {
        glassShader = null
        glyphMask?.takeUnless { it.isRecycled }?.recycle()
        glyphMask = null
        colonMask?.takeUnless { it.isRecycled }?.recycle()
        colonMask = null
        retiredColonMasks.forEach { if (!it.isRecycled) it.recycle() }
        retiredColonMasks.clear()
        glyphSdf?.takeUnless { it.isRecycled }?.recycle()
        glyphSdf = null
        retiredGlyphMasks.forEach { if (!it.isRecycled) it.recycle() }
        retiredGlyphMasks.clear()
        retiredGlyphSdfs.forEach { if (!it.isRecycled) it.recycle() }
        retiredGlyphSdfs.clear()
        backdrop?.recycle()
        if (wallpaperSample !== backdrop) wallpaperSample?.recycle()
        foregroundOcclusionMask?.recycle()
        backdrop = null
        wallpaperSample = null
        foregroundOcclusionMask = null
        cachedTimeText = null
    }

    private fun rebuildBackdrop() {
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        val wallpaperFile = context.filesDir.resolve(wallpaperContent.sourceFileName)
        val manualMaskFile = context.filesDir.resolve(wallpaperContent.manualForegroundFileName)

        val decodedWallpaper = if (
            wallpaperFile.exists() && wallpaperFile.length() > 0L
        ) {
            decodeWallpaper(wallpaperFile.absolutePath, width, height)
        } else {
            null
        }

        val decodedMask = manualMaskFile
            .takeIf { it.exists() && it.length() > 0L }
            ?.let { decodeWallpaper(it.absolutePath, width, height) }

        val wallpaperOutput = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        if (decodedWallpaper != null) {
            drawCoverBitmap(Canvas(wallpaperOutput), decodedWallpaper, width, height)
            decodedWallpaper.recycle()
        } else {
            Log.e(TAG, "Selected wallpaper could not be decoded; using the fallback background.")
            drawDefaultBackdrop(Canvas(wallpaperOutput), width, height)
        }

        // This is only a per-pixel alpha mask. Never draw it as an image.
        // Its alpha erases clock pixels where the selected object overlaps.
        val occlusionOutput = decodedMask?.let { sourceMask ->
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            ).also { output ->
                drawCoverBitmap(Canvas(output), sourceMask, width, height)
                sourceMask.recycle()
            }
        }
        backdrop?.recycle()
        if (wallpaperSample !== backdrop) wallpaperSample?.recycle()
        foregroundOcclusionMask?.recycle()

        adaptiveAccentColor = extractWallpaperAccent(wallpaperOutput)
        backdrop = wallpaperOutput
        wallpaperSample = wallpaperOutput
        foregroundOcclusionMask = occlusionOutput

        cachedTimeText = null
        rebuildGlyphMask(force = true)
        rebuildGlassShader()
    }

    private fun drawCoverBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        width: Int,
        height: Int
    ) {
        val scale = max(
            width.toFloat() / bitmap.width,
            height.toFloat() / bitmap.height
        )
        val dx = (width - bitmap.width * scale) / 2f
        val dy = (height - bitmap.height * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
    }

    private fun decodeWallpaper(
        path: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= targetWidth * 2 ||
                bounds.outHeight / (sampleSize * 2) >= targetHeight * 2
            ) {
                sampleSize *= 2
            }

            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inDither = true
                }
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Could not decode wallpaper image.", exception)
            null
        }
    }

    private fun drawDefaultBackdrop(
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    Color.rgb(3, 8, 20),
                    Color.rgb(14, 18, 48),
                    Color.rgb(47, 8, 67),
                    Color.rgb(6, 5, 18)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.78f,
                height * 0.43f,
                width * 0.78f,
                intArrayOf(
                    Color.argb(105, 72, 102, 181),
                    Color.argb(0, 72, 102, 181)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
    }

    private fun rebuildGlyphMask(
        force: Boolean,
        rebuildDistanceField: Boolean = true
    ) {
        val timeText = currentTimeText()
        if (!force && timeText == cachedTimeText && glyphMask != null) return

        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        val previousMask = glyphMask
        val previousColonMask = colonMask

        val mask = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (memoryError: OutOfMemoryError) {
            Log.e(TAG, "Unable to allocate the clock glyph mask at ${width}x${height}.", memoryError)
            return
        }
        val maskCanvas = Canvas(mask)
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val glyphs = timeText.map { it.toString() }
        // The expansion parameter changes the optical width of each numeral
        // independently. The clock becomes taller as the user enlarges it,
        // while the digits gain carefully controlled width rather than being
        // uniformly stretched into a wider line.
        val expansion = ((clockScale - 1f) / (MAX_CLOCK_SCALE - 1f))
            .coerceIn(0f, 1f)
        // The official Apple SF Pro variable font supplies real wght and wdth
        // axes. User-selected weight and width are applied before the adaptive
        // per-glyph optical shaping below.
        val fontWeight = clockFontWeight
        val fontWidth = clockFontWidthCoordinate()
        val maxWidth = width * MAX_GLYPH_WIDTH_FRACTION
        val maxBaseHeight = min(height * MAX_GLYPH_HEIGHT_FRACTION, width * 0.90f)
        // Kerning is based on actual visible outline bounds, not the font's
        // nominal advances. This removes built-in side-bearing gaps directly.
        val hourPairGapEm = 0.053f
        val minutePairGapEm = 0.019f
        val hourToColonGapEm = 0.038f
        val colonToMinuteGapEm = 0.034f
        val colonRadiusEm = COLON_DOT_RADIUS_EM * 1.08f

        val sizingPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            typeface = typefaceFor(fontWeight, 28, fontWidth)
            isSubpixelText = true
        }

        fun visibleGlyphBounds(size: Float): List<Pair<Float, Float>> {
            sizingPaint.textSize = size
            return glyphs.map { glyph ->
                if (glyph == ":") {
                    val radius = size * colonRadiusEm
                    -radius to radius
                } else {
                    val path = Path()
                    sizingPaint.getTextPath(glyph, 0, glyph.length, 0f, 0f, path)
                    val bounds = RectF()
                    path.computeBounds(bounds, true)
                    if (bounds.isEmpty) {
                        0f to (sizingPaint.measureText(glyph) *
                            glyphWidthProfile(glyph, expansion))
                    } else {
                        val compactWidth = glyphWidthProfile(glyph, expansion)
                        val center = bounds.centerX()
                        (center + (bounds.left - center) * compactWidth) to
                            (center + (bounds.right - center) * compactWidth)
                    }
                }
            }
        }

        fun visualGlyphOrigins(
            bounds: List<Pair<Float, Float>>,
            size: Float
        ): FloatArray {
            val origins = FloatArray(glyphs.size)
            if (glyphs.isEmpty()) return origins
            origins[0] = -bounds[0].first
            for (index in 1 until glyphs.size) {
                val previousGlyph = glyphs[index - 1]
                val currentGlyph = glyphs[index]
                val gapEm = when {
                    previousGlyph == ":" -> colonToMinuteGapEm
                    currentGlyph == ":" -> hourToColonGapEm
                    glyphs.take(index).contains(":") -> minutePairGapEm
                    else -> hourPairGapEm
                }
                val gap = size * gapEm
                val previousRight = origins[index - 1] + bounds[index - 1].second
                origins[index] = previousRight + gap - bounds[index].first
            }
            return origins
        }

        fun measureLine(size: Float): Float {
            val bounds = visibleGlyphBounds(size)
            val origins = visualGlyphOrigins(bounds, size)
            val visualWidth = if (glyphs.isEmpty()) 0f
                else origins.last() + bounds.last().second
            return visualWidth * CLOCK_GLYPH_WIDTH_SCALE
        }

        // Fit the expanded per-digit advances to the usable line width. This
        // keeps the clock centred and prevents minute digits colliding as the
        // optical profiles transition from compact to expanded.
        var low = 1f
        var high = min(maxBaseHeight, width * 0.90f)
        repeat(28) {
            val candidate = (low + high) * 0.5f
            if (measureLine(candidate) <= maxWidth) low = candidate else high = candidate
        }
        val fontSize = low
        sizingPaint.textSize = fontSize

        val metrics = sizingPaint.fontMetrics
        val centerX = clockCenterX * width
        val centerY = clockCenterY * height
        val baseline = centerY - (metrics.ascent + metrics.descent) * 0.5f
        val finalVisibleBounds = visibleGlyphBounds(fontSize)
        val relativeOrigins = visualGlyphOrigins(finalVisibleBounds, fontSize)
        val totalWidth = if (glyphs.isEmpty()) 0f
            else relativeOrigins.last() + finalVisibleBounds.last().second
        val lineOffsetX = centerX - totalWidth * 0.5f
        val glyphOrigins = relativeOrigins.map { it + lineOffsetX }

        val composedPath = Path().apply { fillType = Path.FillType.WINDING }
        val digitTwoPaths = mutableListOf<Path>()
        val composedBounds = RectF()
        val colonCenters = mutableListOf<Pair<Float, Float>>()
        var hasBounds = false

        glyphs.forEachIndexed { index, glyph ->
            if (glyph == ":") {
                val dotOffset = fontSize * COLON_DOT_OFFSET_EM
                val dotX = glyphOrigins[index]
                colonCenters += dotX to (centerY - dotOffset)
                colonCenters += dotX to (centerY + dotOffset)
            } else {                // Generate the character's real SF Pro outline once. A
                // progressively condensed horizontal transform adapts the
                // *shape* of each digit as vertical expansion increases;
                // the vertical expansion below then makes the tall iOS form.
                val glyphPaint = Paint(sizingPaint).apply {
                    typeface = typefaceFor(fontWeight, 28, fontWidth)
                    textSize = fontSize
                    style = Paint.Style.FILL
                    strokeWidth = 0f
                }
                val glyphPath = Path()
                glyphPaint.getTextPath(
                    glyph,
                    0,
                    glyph.length,
                    glyphOrigins[index],
                    baseline,
                    glyphPath
                )

                val pathBounds = RectF()
                glyphPath.computeBounds(pathBounds, true)
                if (!pathBounds.isEmpty) {
                    // Every numeral, including "1", uses the actual outline from
                    // Apple's official SF Pro variable font. Optical transforms
                    // adjust layout only; they do not replace glyph artwork.
                    val compactWidth = glyphWidthProfile(glyph, expansion)
                    val opticalPath = Path()
                    val shapeTransform = Matrix().apply {
                        setScale(
                            compactWidth,
                            glyphHeightFactor(glyph),
                            pathBounds.centerX(),
                            pathBounds.centerY()
                        )
                    }
                    glyphPath.transform(shapeTransform, opticalPath)
                    if (glyph == "2") {
                        digitTwoPaths += Path(opticalPath)
                    } else {
                        composedPath.addPath(opticalPath)
                    }

                    val opticalBounds = RectF()
                    opticalPath.computeBounds(opticalBounds, true)
                    if (!opticalBounds.isEmpty) {
                        if (!hasBounds) {
                            composedBounds.set(opticalBounds)
                            hasBounds = true
                        } else {
                            composedBounds.union(opticalBounds)
                        }
                    }
                }
            }
        }

        if (hasBounds) {
            // Horizontal pinch is independent from the vertical clock scale.
            // Clamp against the actual glyph outline after viewport changes.
            val maxSafeWidthScale = (
                width * MAX_COMMITTED_WIDTH_FRACTION /
                    (composedBounds.width().coerceAtLeast(1f) *
                        CLOCK_GLYPH_WIDTH_SCALE)
                ).coerceIn(MIN_CLOCK_WIDTH_SCALE, MAX_CLOCK_WIDTH_SCALE)
            clockWidthScale = clockWidthScale.coerceAtMost(maxSafeWidthScale)

            // Apply native optical proportions and independent X/Y scales.
            val naturalShapeTransform = Matrix().apply {
                setScale(
                    CLOCK_GLYPH_WIDTH_SCALE * clockWidthScale,
                    CLOCK_GLYPH_HEIGHT_SCALE,
                    centerX,
                    centerY
                )
            }
            val naturalShapePath = Path()
            composedPath.transform(naturalShapeTransform, naturalShapePath)
            naturalShapePath.computeBounds(composedBounds, true)

            val naturalDigitTwoPaths = digitTwoPaths.map { sourcePath ->
                Path().also { sourcePath.transform(naturalShapeTransform, it) }
            }
            naturalDigitTwoPaths.forEach { twoPath ->
                val twoBounds = RectF()
                twoPath.computeBounds(twoBounds, true)
                composedBounds.union(twoBounds)
            }

            val dotRadius = fontSize * COLON_DOT_RADIUS_EM * 1.08f
            val naturalDotOffset = fontSize * COLON_DOT_OFFSET_EM * CLOCK_GLYPH_HEIGHT_SCALE
            colonCenters.forEach { (dotX, originalY) ->
                val naturalX = centerX +
                    (dotX - centerX) * CLOCK_GLYPH_WIDTH_SCALE * clockWidthScale
                val naturalY = centerY +
                    (originalY - centerY) * CLOCK_GLYPH_HEIGHT_SCALE
                val bounds = RectF(
                    naturalX - dotRadius,
                    naturalY - dotRadius,
                    naturalX + dotRadius,
                    naturalY + dotRadius
                )
                composedBounds.union(bounds)
            }

            // Enforce the safe viewport limit against the actual expanded
            // outlines, including the colon, before committing the scale.
            val allowedScaleY = (
                height * MAX_COMMITTED_HEIGHT_FRACTION /
                    composedBounds.height().coerceAtLeast(1f)
                ).coerceAtLeast(1f)
            val committedScaleY = clockScale.coerceAtMost(allowedScaleY)
            if (committedScaleY != clockScale) {
                clockScale = committedScaleY.coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
            }

            val verticalTransform = Matrix().apply {
                setScale(1f, committedScaleY, centerX, centerY)
            }
            val finalPath = Path()
            naturalShapePath.transform(verticalTransform, finalPath)
            val finalDigitTwoPaths = naturalDigitTwoPaths.map { sourcePath ->
                Path().also { sourcePath.transform(verticalTransform, it) }
            }
            val finalColonCenters = mutableListOf<Pair<Float, Float>>()

            // Keep the two colon dots circular as the clock grows. Only their
            // separation follows the vertical expansion.
            colonCenters.forEach { (dotX, originalY) ->
                val naturalX = centerX +
                    (dotX - centerX) * CLOCK_GLYPH_WIDTH_SCALE * clockWidthScale
                val naturalY = centerY + (originalY - centerY) * CLOCK_GLYPH_HEIGHT_SCALE
                val finalY = centerY + (naturalY - centerY) * committedScaleY
                finalPath.addCircle(naturalX, finalY, dotRadius, Path.Direction.CW)
                finalColonCenters += naturalX to finalY
            }
            val colonMaskData = createColonMask(
                finalColonCenters,
                dotRadius,
                width,
                height
            )
            colonMask = colonMaskData.first
            colonMaskOriginX = colonMaskData.second
            colonMaskOriginY = colonMaskData.third

            val finalBounds = RectF()
            finalPath.computeBounds(finalBounds, true)
            finalDigitTwoPaths.forEach { twoPath ->
                val twoBounds = RectF()
                twoPath.computeBounds(twoBounds, true)
                finalBounds.union(twoBounds)
            }
            val inset = max(2f, fontSize * 0.025f)
            glyphBounds.set(
                (finalBounds.left - inset).coerceAtLeast(0f),
                (finalBounds.top - inset).coerceAtLeast(0f),
                (finalBounds.right + inset).coerceAtMost(width.toFloat()),
                (finalBounds.bottom + inset).coerceAtMost(height.toFloat())
            )

            // Preserve Apple's flatter SF Pro terminals. Edge softness is
            // supplied by antialiasing and the optical bevel shader, not by
            // rounding the outline path itself.
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            maskCanvas.drawPath(finalPath, maskPaint)

            // Only the "2" has a subtly softened corner treatment. The small
            // radius rounds its lower foot and terminal transitions without
            // changing the flatter SF Pro ends of 1, 4, 5 or 7.
            val terminalFillet = (fontSize * 0.012f).coerceAtLeast(1f)
            val digitTwoPaint = Paint(maskPaint).apply {
                pathEffect = CornerPathEffect(terminalFillet)
            }
            finalDigitTwoPaths.forEach { twoPath ->
                maskCanvas.drawPath(twoPath, digitTwoPaint)
            }
        } else {
            glyphBounds.setEmpty()
            glyphBaseBounds.setEmpty()
            val emptyColonMask = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            emptyColonMask.eraseColor(Color.TRANSPARENT)
            colonMask = emptyColonMask
            colonMaskOriginX = 0f
            colonMaskOriginY = 0f
        }

        glyphMask = mask
        if (rebuildDistanceField) {
            rebuildGlyphSdf()
        } else {
            // Keep the previous bevel field alive while the new weight mask is
            // previewed. The final accurate SDF is generated once on release.
            glyphSdfStale = true
            updateGlyphMaskInput()
        }
        retireGlyphMask(previousMask)
        retireColonMask(previousColonMask)

        glyphBaseBounds.set(glyphBounds)
        maskClockScale = clockScale
        maskClockWidthScale = clockWidthScale
        gestureOffsetX = 0f
        gestureOffsetY = 0f
        previewScaleX = 1f
        previewScaleY = 1f
        cachedTimeText = timeText
    }

    private fun createColonMask(
        centers: List<Pair<Float, Float>>,
        radius: Float,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): Triple<Bitmap, Float, Float> {
        if (centers.isEmpty()) {
            val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            empty.eraseColor(Color.TRANSPARENT)
            return Triple(empty, 0f, 0f)
        }

        val padding = max(3f, radius * 0.15f)
        val left = (centers.minOf { it.first } - radius - padding)
            .toInt().coerceIn(0, surfaceWidth - 1)
        val top = (centers.minOf { it.second } - radius - padding)
            .toInt().coerceIn(0, surfaceHeight - 1)
        val right = (centers.maxOf { it.first } + radius + padding)
            .toInt().coerceIn(left + 1, surfaceWidth)
        val bottom = (centers.maxOf { it.second } + radius + padding)
            .toInt().coerceIn(top + 1, surfaceHeight)

        val mask = Bitmap.createBitmap(
            right - left,
            bottom - top,
            Bitmap.Config.ARGB_8888
        )
        val maskCanvas = Canvas(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        centers.forEach { (x, y) ->
            maskCanvas.drawCircle(x - left, y - top, radius, paint)
        }
        return Triple(mask, left.toFloat(), top.toFloat())
    }
    private fun rebuildGlyphSdf() {
        val mask = glyphMask
        if (mask == null || mask.isRecycled || glyphBounds.isEmpty) {
            glyphSdfStale = false
            return
        }

        val previousSdf = glyphSdf
        val previousOriginX = glyphSdfOriginX
        val previousOriginY = glyphSdfOriginY
        val previousScale = glyphSdfScale

        val replacement = try {
            createGlyphSdf(mask, glyphBounds)
        } catch (memoryError: OutOfMemoryError) {
            diagnostics.report("glyph-sdf-allocation-out-of-memory", memoryError)
            Log.w(
                TAG,
                "SDF allocation skipped; keeping the previous bevel field.",
                memoryError
            )
            null
        }

        if (replacement != null) {
            glyphSdf = replacement
            glyphSdfStale = false
            updateGlyphMaskInput()
            retireGlyphSdf(previousSdf)
        } else {
            glyphSdf = previousSdf
            glyphSdfOriginX = previousOriginX
            glyphSdfOriginY = previousOriginY
            glyphSdfScale = previousScale
            glyphSdfStale = previousSdf != null
            updateGlyphMaskInput()
        }
    }

    private fun retireGlyphMask(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || bitmap === glyphMask) return
        retiredGlyphMasks += bitmap
        while (retiredGlyphMasks.size > 1) {
            val oldest = retiredGlyphMasks.removeAt(0)
            if (!oldest.isRecycled) oldest.recycle()
        }
    }

    private fun retireColonMask(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || bitmap === colonMask) return
        retiredColonMasks += bitmap
        while (retiredColonMasks.size > 1) {
            val oldest = retiredColonMasks.removeAt(0)
            if (!oldest.isRecycled) oldest.recycle()
        }
    }

    private fun retireGlyphSdf(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || bitmap === glyphSdf) return
        retiredGlyphSdfs += bitmap
        while (retiredGlyphSdfs.size > 1) {
            val oldest = retiredGlyphSdfs.removeAt(0)
            if (!oldest.isRecycled) oldest.recycle()
        }
    }

    /** Widths are optical advances in a single normalized cap-height grid. */
    private fun vectorDigitAdvanceEm(glyph: String): Float = when (glyph) {
        "0" -> 0.43f
        "1" -> 0.38f
        "2" -> 0.39f
        "3" -> 0.39f
        "4" -> 0.42f
        "5" -> 0.39f
        "6" -> 0.41f
        "7" -> 0.39f
        "8" -> 0.43f
        "9" -> 0.41f
        else -> 0.39f
    }

    /**
     * Builds a compact signed-distance field around the current glyph bounds.
     * A two-pass 5/7 chamfer transform closely approximates Euclidean
     * distance and keeps contour normals directionally consistent.
     */
    private fun createGlyphSdf(source: Bitmap, bounds: RectF): Bitmap? {
        if (source.isRecycled || bounds.isEmpty) return null

        val padding = max(
            SDF_PADDING_PX,
            (context.resources.displayMetrics.density * 30f).roundToInt()
        )
        val left = (bounds.left.roundToInt() - padding).coerceIn(0, source.width - 1)
        val top = (bounds.top.roundToInt() - padding).coerceIn(0, source.height - 1)
        val right = (bounds.right.roundToInt() + padding).coerceIn(left + 1, source.width)
        val bottom = (bounds.bottom.roundToInt() + padding).coerceIn(top + 1, source.height)

        val cropWidth = right - left
        val cropHeight = bottom - top
        val cropPixels = cropWidth.toLong() * cropHeight.toLong()
        if (cropPixels <= 0L) return null

        // Keep the preview at native SDF resolution for crisp bevels. Only
        // downsample unusually large full-screen crops to bound temporary memory.
        val maxSdfPixels = 420_000f
        val sdfScale = min(
            1f,
            kotlin.math.sqrt(maxSdfPixels / cropPixels.toFloat())
        ).coerceAtLeast(0.40f)
        val sdfWidth = max(1, (cropWidth * sdfScale).roundToInt())
        val sdfHeight = max(1, (cropHeight * sdfScale).roundToInt())
        val pixelCountLong = sdfWidth.toLong() * sdfHeight.toLong()
        if (pixelCountLong <= 0L || pixelCountLong > 450_000L) return null

        val pixelCount = pixelCountLong.toInt()
        diagnostics.record("SDF allocation start: crop=${cropWidth}x${cropHeight} output=${sdfWidth}x${sdfHeight} pixels=$pixelCount")
        val sourcePixels = IntArray(pixelCount)
        if (sdfScale >= 0.999f) {
            source.getPixels(sourcePixels, 0, sdfWidth, left, top, sdfWidth, sdfHeight)
        } else {
            val sampleBitmap = Bitmap.createBitmap(
                sdfWidth,
                sdfHeight,
                Bitmap.Config.ARGB_8888
            )
            try {
                val sampleCanvas = Canvas(sampleBitmap)
                sampleCanvas.drawBitmap(
                    source,
                    Rect(left, top, right, bottom),
                    Rect(0, 0, sdfWidth, sdfHeight),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
                sampleBitmap.getPixels(sourcePixels, 0, sdfWidth, 0, 0, sdfWidth, sdfHeight)
            } finally {
                sampleBitmap.recycle()
            }
        }

        val maxUnits = (SDF_RANGE_PX * SDF_CARDINAL_COST.toFloat()).roundToInt()
        val infinity = (maxUnits + 1).toShort()
        val distanceToOutside = ShortArray(pixelCount)
        val distanceToInside = ShortArray(pixelCount)
        for (index in 0 until pixelCount) {
            val inside = Color.alpha(sourcePixels[index]) >= 128
            distanceToOutside[index] = if (inside) infinity else 0
            distanceToInside[index] = if (inside) 0 else infinity
        }

        chamferDistance(distanceToOutside, sdfWidth, sdfHeight, maxUnits)
        chamferDistance(distanceToInside, sdfWidth, sdfHeight, maxUnits)

        val sdfPixels = IntArray(pixelCount)
        for (index in 0 until pixelCount) {
            val alpha = Color.alpha(sourcePixels[index])
            val inside = alpha >= 128
            val signedUnits = if (inside) {
                distanceToOutside[index].toInt()
            } else {
                -distanceToInside[index].toInt()
            }
            // Preserve fractional coverage from the original antialiased edge
            // rather than snapping every contour pixel to fully inside/outside.
            val coverageCorrection = if (alpha in 1..254) {
                (alpha / 255f - 0.5f) / sdfScale
            } else {
                0f
            }
            val signedOutputPixels =
                signedUnits / SDF_CARDINAL_COST.toFloat() / sdfScale +
                    coverageCorrection
            val encoded = (
                0.5f + signedOutputPixels / (2f * SDF_RANGE_PX)
            ).coerceIn(0f, 1f)
            sdfPixels[index] = Color.argb(
                (encoded * 255f).roundToInt(),
                255,
                255,
                255
            )
        }

        return Bitmap.createBitmap(
            sdfWidth,
            sdfHeight,
            Bitmap.Config.ARGB_8888
        ).also { sdf ->
            sdf.setPixels(sdfPixels, 0, sdfWidth, 0, 0, sdfWidth, sdfHeight)
            glyphSdfOriginX = left.toFloat()
            glyphSdfOriginY = top.toFloat()
            glyphSdfScale = sdfScale
            diagnostics.record("SDF allocation complete: $pixelCount pixels")
        }
    }

    private fun chamferDistance(
        distances: ShortArray,
        width: Int,
        height: Int,
        maxUnits: Int
    ) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                var best = distances[index].toInt()
                if (x > 0) {
                    best = min(
                        best,
                        distances[index - 1].toInt() + SDF_CARDINAL_COST
                    )
                }
                if (y > 0) {
                    best = min(
                        best,
                        distances[index - width].toInt() + SDF_CARDINAL_COST
                    )
                    if (x > 0) {
                        best = min(
                            best,
                            distances[index - width - 1].toInt() + SDF_DIAGONAL_COST
                        )
                    }
                    if (x + 1 < width) {
                        best = min(
                            best,
                            distances[index - width + 1].toInt() + SDF_DIAGONAL_COST
                        )
                    }
                }
                distances[index] = min(best, maxUnits).toShort()
            }
        }
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                val index = row + x
                var best = distances[index].toInt()
                if (x + 1 < width) {
                    best = min(
                        best,
                        distances[index + 1].toInt() + SDF_CARDINAL_COST
                    )
                }
                if (y + 1 < height) {
                    best = min(
                        best,
                        distances[index + width].toInt() + SDF_CARDINAL_COST
                    )
                    if (x > 0) {
                        best = min(
                            best,
                            distances[index + width - 1].toInt() + SDF_DIAGONAL_COST
                        )
                    }
                    if (x + 1 < width) {
                        best = min(
                            best,
                            distances[index + width + 1].toInt() + SDF_DIAGONAL_COST
                        )
                    }
                }
                distances[index] = min(best, maxUnits).toShort()
            }
        }
    }

    private fun buildAdaptiveZeroPath(bounds: RectF): Path {
        val width = bounds.width().coerceAtLeast(1f)
        val height = bounds.height().coerceAtLeast(1f)
        val centerX = bounds.centerX()
        val outer = RectF(
            bounds.left - width * 0.075f,
            bounds.top,
            bounds.right + width * 0.075f,
            bounds.bottom
        )
        val outerWidth = outer.width()

        return Path().apply {
            fillType = Path.FillType.EVEN_ODD
            // Broad vertical sides with rounded shoulders: avoid the narrow,
            // elliptical zero produced by simply stretching a text glyph.
            addRoundRect(
                outer,
                outerWidth * 0.30f,
                height * 0.115f,
                Path.Direction.CW
            )
            val counter = RectF(
                centerX - outerWidth * 0.205f,
                bounds.top + height * 0.145f,
                centerX + outerWidth * 0.205f,
                bounds.bottom - height * 0.145f
            )
            addRoundRect(
                counter,
                outerWidth * 0.16f,
                height * 0.075f,
                Path.Direction.CCW
            )
        }
    }

    private fun buildAdaptiveEightPath(bounds: RectF): Path {
        val left = bounds.left
        val right = bounds.right
        val top = bounds.top
        val bottom = bounds.bottom
        val width = bounds.width().coerceAtLeast(1f)
        val height = bounds.height().coerceAtLeast(1f)
        val centerX = bounds.centerX()
        val waistY = bounds.centerY()

        return Path().apply {
            fillType = Path.FillType.EVEN_ODD

            // A continuous outer silhouette: broad upper/lower bowls with a
            // soft inward waist, keeping the characteristic SF-like rhythm.
            moveTo(centerX, top)
            cubicTo(left + width * 0.22f, top, left, top + height * 0.10f,
                left, top + height * 0.27f)
            cubicTo(left, top + height * 0.40f, left + width * 0.13f,
                top + height * 0.46f, centerX, waistY)
            cubicTo(left + width * 0.13f, top + height * 0.54f, left,
                top + height * 0.61f, left, top + height * 0.75f)
            cubicTo(left, bottom - height * 0.10f, left + width * 0.22f,
                bottom, centerX, bottom)
            cubicTo(right - width * 0.22f, bottom, right,
                bottom - height * 0.10f, right, top + height * 0.75f)
            cubicTo(right, top + height * 0.61f, right - width * 0.13f,
                top + height * 0.54f, centerX, waistY)
            cubicTo(right - width * 0.13f, top + height * 0.46f, right,
                top + height * 0.40f, right, top + height * 0.27f)
            cubicTo(right, top + height * 0.10f, right - width * 0.22f,
                top, centerX, top)
            close()

            // Tall rounded-square counters are the defining detail of the
            // expanded lock-screen 8; EVEN_ODD cuts them from the silhouette.
            val counterLeft = left + width * 0.245f
            val counterRight = right - width * 0.245f
            val counterRadiusX = width * 0.095f
            val counterRadiusY = height * 0.045f
            addRoundRect(
                RectF(
                    counterLeft,
                    top + height * 0.125f,
                    counterRight,
                    top + height * 0.425f
                ),
                counterRadiusX,
                counterRadiusY,
                Path.Direction.CW
            )
            addRoundRect(
                RectF(
                    counterLeft - width * 0.008f,
                    top + height * 0.575f,
                    counterRight + width * 0.008f,
                    bottom - height * 0.115f
                ),
                counterRadiusX,
                counterRadiusY,
                Path.Direction.CW
            )
        }
    }

    /**
     * Small per-digit optical advances remain on top of Apple's true wdth axis.
     * The smooth per-glyph curve preserves the tall lock-screen proportions.
     */
    private fun glyphWidthProfile(glyph: String, expansion: Float): Float {
        val progress = expansion.coerceIn(0f, 1f)
        val compact = when (glyph) {
            "1" -> 1.00f
            "2" -> 1.08f
            "3" -> 1.04f
            "4" -> 1.08f
            "5" -> 1.04f
            "6" -> 1.04f
            "7" -> 0.96f
            "8" -> 1.08f
            "9" -> 1.04f
            "0" -> 1.07f
            else -> 1.00f
        }
        val expanded = when (glyph) {
            "1" -> 1.00f
            "2" -> 1.13f
            "3" -> 1.07f
            "4" -> 1.12f
            "5" -> 1.07f
            "6" -> 1.08f
            "7" -> 0.98f
            "8" -> 1.12f
            "9" -> 1.08f
            "0" -> 1.11f
            else -> 1.00f
        }
        return compact + (expanded - compact) * progress
    }

    private fun glyphHeightFactor(glyph: String): Float {
        val optical = when (glyph) {
            "1" -> 1.015f
            "4", "7" -> 0.985f
            "3", "5" -> 1.01f
            else -> 1.0f
        }
        return optical
    }

    private fun wallpaperWeightAdjustment(
        x: Float,
        y: Float,
        radius: Float
    ): Int {
        val image = backdrop ?: return 0
        if (image.isRecycled || image.width <= 0 || image.height <= 0) return 0

        val centerX = x.roundToInt().coerceIn(0, image.width - 1)
        val centerY = y.roundToInt().coerceIn(0, image.height - 1)
        val offset = radius.roundToInt().coerceAtLeast(1)
        var luminance = 0f
        var samples = 0

        for (dy in -1..1) {
            for (dx in -1..1) {
                val sx = (centerX + dx * offset).coerceIn(0, image.width - 1)
                val sy = (centerY + dy * offset).coerceIn(0, image.height - 1)
                val pixel = image.getPixel(sx, sy)
                luminance += (
                    Color.red(pixel) * 0.2126f +
                        Color.green(pixel) * 0.7152f +
                        Color.blue(pixel) * 0.0722f
                    ) / 255f
                samples++
            }
        }

        val average = luminance / samples.coerceAtLeast(1)
        return when {
            average > 0.70f -> 28
            average > 0.54f -> 14
            average < 0.14f -> 8
            else -> 0
        }
    }

    private fun clockFontWidthCoordinate(): Int = when (clockFontStyle) {
        1 -> SF_PRO_WIDTH_CONDENSED
        2 -> SF_PRO_WIDTH_COMPRESSED
        3 -> SF_PRO_WIDTH_EXPANDED
        else -> SF_PRO_WIDTH_REGULAR
    }

    private fun typefaceFor(
        weight: Int,
        opticalSize: Int,
        widthProfile: Int
    ): Typeface {
        val safeWeight = weight.coerceIn(100, 900)
        val safeWidth = widthProfile.coerceIn(SF_PRO_WIDTH_COMPRESSED, SF_PRO_WIDTH_EXPANDED)
        val cacheKey = safeWeight * 1000 + safeWidth
        typefaceCache[cacheKey]?.let { return it }

        val variableFace = try {
            Typeface.Builder(context.assets, VARIABLE_FONT_ASSET_PATH)
                .setFontVariationSettings(
                    "'wght' $safeWeight, 'wdth' $safeWidth"
                )
                .build()
        } catch (fontException: Exception) {
            diagnostics.record("SF Pro renderer font fallback: ${fontException.javaClass.simpleName}")
            Log.w(
                TAG,
                "Official Apple SF Pro variable font unavailable; using its Display Thin face.",
                fontException
            )
            fallbackTypeface
        } catch (memoryError: OutOfMemoryError) {
            diagnostics.report("renderer-sf-pro-typeface-allocation", memoryError)
            fallbackTypeface
        }

        if (typefaceCache.size >= 4) {
            typefaceCache.clear()
        }
        typefaceCache[cacheKey] = variableFace
        return variableFace
    }

    private fun updateGlyphMaskInput() {
        val shader = glassShader ?: return
        val mask = glyphMask ?: return
        try {
            shader.setInputShader(                "uGlyphMask",
                BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            val colon = colonMask ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            shader.setInputShader(
                "uColonMask",
                BitmapShader(colon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            shader.setFloatUniform("uColonOrigin", colonMaskOriginX, colonMaskOriginY)
            val sdf = glyphSdf ?: mask
            shader.setInputShader(
                "uGlyphSdf",
                BitmapShader(sdf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            shader.setFloatUniform("uSdfOrigin", glyphSdfOriginX, glyphSdfOriginY)
            shader.setFloatUniform("uSdfScale", glyphSdfScale)
            shader.setFloatUniform("uSdfRange", SDF_RANGE_PX)
        } catch (exception: RuntimeException) {
            diagnostics.report("shader-glyph-input-refresh", exception)
            Log.w(TAG, "Could not refresh glyph mask/SDF inputs.", exception)
            glassShader = null
        }
    }

    private fun extractWallpaperAccent(bitmap: Bitmap): Int {
        // Pick the strongest recurring saturated hue as the wallpaper's
        // secondary accent. Near-white and near-black samples are ignored.
        val bins = 24
        val weights = DoubleArray(bins)
        val red = DoubleArray(bins)
        val green = DoubleArray(bins)
        val blue = DoubleArray(bins)
        val hsv = FloatArray(3)
        val stepX = max(1, bitmap.width / 72)
        val stepY = max(1, bitmap.height / 144)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                val saturation = hsv[1]
                val value = hsv[2]
                if (saturation >= 0.28f && value >= 0.22f && value <= 0.98f) {
                    val bin = ((hsv[0] / 360f) * bins)
                        .toInt()
                        .coerceIn(0, bins - 1)
                    val weight = saturation.toDouble() *
                        (0.45 + value.toDouble() * 0.55)
                    weights[bin] += weight
                    red[bin] += Color.red(pixel) * weight
                    green[bin] += Color.green(pixel) * weight
                    blue[bin] += Color.blue(pixel) * weight
                }
                x += stepX
            }
            y += stepY
        }

        var best = 0
        for (index in 1 until bins) {
            if (weights[index] > weights[best]) best = index
        }
        if (weights[best] <= 0.0) return Color.rgb(145, 150, 160)

        return Color.rgb(
            (red[best] / weights[best]).toInt().coerceIn(0, 255),
            (green[best] / weights[best]).toInt().coerceIn(0, 255),
            (blue[best] / weights[best]).toInt().coerceIn(0, 255)
        )
    }

    private fun rebuildGlassShader() {
        val background = wallpaperSample ?: return
        val mask = glyphMask ?: return

        try {
            val shader = RuntimeShader(AGSL)
            shader.setInputShader(
                "uWallpaper",
                BitmapShader(
                    background,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP
                )
            )
            shader.setInputShader(
                "uGlyphMask",
                BitmapShader(
                    mask,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP
                )
            )
            val colon = colonMask ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            shader.setInputShader(
                "uColonMask",
                BitmapShader(colon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            shader.setFloatUniform("uColonOrigin", colonMaskOriginX, colonMaskOriginY)
            val sdf = glyphSdf ?: mask
            shader.setInputShader(
                "uGlyphSdf",
                BitmapShader(sdf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            shader.setFloatUniform("uSdfOrigin", glyphSdfOriginX, glyphSdfOriginY)
            shader.setFloatUniform("uSdfScale", glyphSdfScale)
            shader.setFloatUniform("uSdfRange", SDF_RANGE_PX)
            shader.setFloatUniform(
                "uResolution",
                surfaceWidth.toFloat(),
                surfaceHeight.toFloat()
            )
            shader.setFloatUniform(
                "uAccentColor",
                Color.red(adaptiveAccentColor) / 255f,
                Color.green(adaptiveAccentColor) / 255f,
                Color.blue(adaptiveAccentColor) / 255f
            )
            shader.setFloatUniform(
                "uClockTint",
                Color.red(clockColor) / 255f,
                Color.green(clockColor) / 255f,
                Color.blue(clockColor) / 255f
            )
            shader.setFloatUniform(
                "uTintStrength",
                if (clockColor == Color.WHITE) 0f else 0.16f
            )
            shader.setFloatUniform("uFrosted", if (glassMode) 0f else 1f)
            shader.setFloatUniform(
                "uClockCenter",
                clockCenterX,
                clockCenterY
            )
            glassShader = shader
        } catch (exception: RuntimeException) {
            diagnostics.report("agsl-shader-initialization", exception)
            Log.w(TAG, "Unable to initialize AGSL shader.", exception)
            glassShader = null
        } catch (memoryError: OutOfMemoryError) {
            diagnostics.report("agsl-shader-out-of-memory", memoryError)
            glassShader = null
        }
    }

    /**
     * Re-reads the injected clock source and rebuilds glyph outlines when its
     * sample changes. Fixed sample state remains inside the Digit Lab host.
     */
    fun refreshClockText() {
        cachedTimeText = null
        if (surfaceWidth > 0 && surfaceHeight > 0) rebuildGlyphMask(force = true)
    }

    private fun currentTimeText(): String = clockTextSource.currentTimeText(context)

    private fun loadFallbackTypeface(): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, FONT_ASSET_PATH)
        } catch (exception: Exception) {
            Log.w(TAG, "SF Pro Display Thin unavailable; using system light font.", exception)
            Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
    }
}

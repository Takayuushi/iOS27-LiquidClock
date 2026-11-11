# iOS 27 Liquid Clock

An Android live-wallpaper clock experiment for modern Samsung Galaxy devices, targeting an oversized iOS-inspired clock composition.

## Current architecture

- The main renderer is a native Android WallpaperService, not a RemoteViews widget or an application overlay.
- Users choose a wallpaper image through the system document picker. The app copies it into private storage so the wallpaper engine can render it without depending on a temporary document URI permission.
- LiquidWallpaperService draws the wallpaper and clock into the live wallpaper surface. Home-screen icons and launcher controls are naturally composited above the wallpaper by Android.
- LiquidWallpaperRenderer creates a glyph alpha mask for the time and uses AGSL RuntimeShader to sample the selected wallpaper inside the glyphs, adding chromatic refraction, rim lighting, and a subtle animated sheen.
- When a wallpaper is imported, ML Kit Subject Segmentation runs once on-device. Its foreground confidence mask is converted into a feathered alpha cut-out using the original image pixels. The live wallpaper keeps the original wallpaper intact, then draws the glass clock, then draws the feathered foreground subject so the subject can overlap the clock. The shader returns the glyph mask alpha directly, so transparent pixels remain transparent and no rectangular shader backing is drawn. If no useful subject mask is returned, the original wallpaper is used without depth overlap.
- ML Kit Subject Segmentation is currently a beta, Google Play services-delivered model; first-use availability depends on the model being installed.
- The clock uses a 36%-of-screen glyph height target plus a 1.24x vertical-only glyph stretch, persists a draggable normalized center, and supports pinch scaling between 0.72x and 1.48x, and a Light rather than Thin face. The glass fill transmits about 80% of the sampled wallpaper colour through the numerals, with saturation compensation and a restrained cool-white body reflection, subtle chromatic refraction, a dark drop shadow, and directional edge glints. The clock is drawn with a cached wallpaper bitmap and glyph mask. AGSL is evaluated only inside the computed glyph bounds, while the subtle material shimmer updates at 30 fps when the wallpaper is visible. Rendering stops when the wallpaper is not visible. Wallpaper decoding is downsampled to the live surface dimensions to limit memory use.
- SF Pro Display Light (weight 300) is used for the live clock to avoid the hairline appearance of Thin; Thin (100) and Bold (700) are also provisioned as Android font resources. The light face is loaded from the workflow-provisioned app asset. A system light font fallback is used only if the asset is unavailable.

## Lock Screen boundary

A normal third-party WallpaperService does not replace Samsung One UI's secure Lock Screen clock. One UI still draws its own system clock above the wallpaper. This app's live wallpaper is intended first for the Home Screen. The same image can be selected as the Lock Screen wallpaper separately, with Samsung's native clock styling or Good Lock / LockStar controlling the secure clock.

The app no longer requests Display over other apps permission and no longer uses the old floating overlay service. Touch input is enabled on the WallpaperService engine: drag the clock to reposition it and pinch with two fingers to change its scale. The composition is persisted in app preferences. Shader code is reused while the glyph mask is refreshed during drag and resize, avoiding shader recompilation on each pointer movement.

## Build

GitHub Actions builds the Android 17 debug APK with JDK 17 and uploads it as an artifact.

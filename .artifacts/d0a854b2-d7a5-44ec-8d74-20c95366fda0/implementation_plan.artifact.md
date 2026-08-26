# Fix Map Not Showing on Xiaomi 11T Pro

The user reports that the map does not appear on their Xiaomi 11T Pro. This is likely due to one of three common issues on Xiaomi devices:
1. **MIUI Force Dark Mode**: MIUI has a feature that forcedly inverts colors in apps, which often breaks OpenGL surfaces like MapLibre, making them appear completely black or invisible.
2. **SurfaceView vs. TextureView**: High-refresh-rate displays (like the 120Hz screen on the 11T Pro) can sometimes have rendering conflicts with `SurfaceView` when used inside a Jetpack Compose `AndroidView`. Switching to `TextureView` (TextureMode) is a standard fix.
3. **Lifecycle Sync**: The current lifecycle management relies on a field in the Activity, which can be out of sync with the Compose composition.

## Proposed Changes

### [Component Name] UI & Rendering

#### [MODIFY] [themes.xml](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/src/main/res/values/themes.xml)
- Add `android:forceDarkAllowed="false"` to the base theme to prevent MIUI from interfering with the map's rendering.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/src/main/java/com/example/scraper/MainActivity.kt)
- Refactor `OpenMapView` to use `TextureView` by passing `MapLibreMapOptions` with `textureMode(true)`.
- Use `DisposableEffect` and `LifecycleEventObserver` to handle `MapView` lifecycle correctly within the Composable, removing the dependency on the `MainActivity` field.
- Add error logging for style loading failures.

## Verification Plan

### Automated Tests
- Build the app to ensure no compilation errors.

### Manual Verification
- Deploy to a device (if available) and check if the map appears.
- Ask the user to verify if the map now appears on their Xiaomi 11T Pro.

# Implementation Plan - KartaView Integration and MapLibre Migration

This plan outlines the steps to replace the current osmdroid and Google Street View logic with MapLibre and KartaView imagery search/export functionality.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/Damon/AndroidStudioProjects/scraper/gradle/libs.versions.toml)
- Remove `osmdroid` version and library definition.
- Add `maplibre` version (`11.11.0`) and library definition (`org.maplibre.gl:android-sdk`).

#### [MODIFY] [build.gradle.kts](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/build.gradle.kts)
- Remove `libs.osmdroid` dependency.
- Add `libs.maplibre` dependency.
- Remove `libs.androidx.lifecycle.runtime.compose` (not used in the new `MainActivity.kt`).

---

### Core Logic

#### [MODIFY] [MainActivity.kt](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/src/main/java/com/example/scraper/MainActivity.kt)
- Replace `osmdroid` MapView with MapLibre `MapView`.
- Implement two-corner area selection.
- Integrate `KartaViewClient` for imagery search and `KartaViewExporter` for downloading.
- Update UI to show KartaView results and download controls.

#### [NEW] [KartaViewClient.kt](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/src/main/java/com/example/scraper/KartaViewClient.kt)
- Add utility to call KartaView's public API and parse photo metadata.

#### [NEW] [KartaViewExporter.kt](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/src/main/java/com/example/scraper/KartaViewExporter.kt)
- Add utility to download selected images and export JSON/CSV manifests.

---

### Documentation

#### [NEW] [KARTAVIEW_INTEGRATION.md](file:///C:/Users/Damon/AndroidStudioProjects/scraper/KARTAVIEW_INTEGRATION.md)
- Add documentation for the KartaView integration, licensing, and workflow.

## Verification Plan

### Automated Tests
- Run Gradle sync to verify dependencies are correctly resolved.
- Build the project to ensure no compilation errors.

### Manual Verification
1. Launch the app on an emulator/device.
2. Verify the map (MapLibre) loads correctly.
3. Switch to "Select Area" mode and tap two points on the map.
4. Verify the rectangular selection appears.
5. Tap "Find Open Images" and verify KartaView results are listed.
6. Select some images and tap "Save".
7. Verify images and manifests are saved in the `Downloads/KartaView` directory.

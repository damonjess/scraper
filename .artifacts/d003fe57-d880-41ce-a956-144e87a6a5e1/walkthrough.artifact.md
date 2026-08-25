# Walkthrough - KartaView Integration and MapLibre Migration

I have successfully updated the project to use MapLibre for mapping and KartaView for street-level imagery search and export.

## Changes Made

### Build Configuration
- Migrated from `osmdroid` to `MapLibre`.
- Updated `libs.versions.toml` with the `maplibre` dependency.
- Updated `app/build.gradle.kts` to include `libs.maplibre` and removed unused dependencies.

### KartaView Integration
- **[KartaViewClient.kt](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/src/main/java/com/example/scraper/KartaViewClient.kt)**: Added a client to search for public KartaView imagery within a bounding box.
- **[KartaViewExporter.kt](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/src/main/java/com/example/scraper/KartaViewExporter.kt)**: Added an exporter to download selected images and generate JSON/CSV manifests.
- **[MainActivity.kt](file:///C:/Users/Damon/AndroidStudioProjects/scraper/app/src/main/java/com/example/scraper/MainActivity.kt)**:
    - Replaced the map view with MapLibre.
    - Implemented two-corner area selection.
    - Integrated the search and download UI for KartaView images.

### Documentation
- **[KARTAVIEW_INTEGRATION.md](file:///C:/Users/Damon/AndroidStudioProjects/scraper/KARTAVIEW_INTEGRATION.md)**: Added a guide on how the new integration works, including licensing and attribution requirements.

## Verification Results

### Automated Tests
- Gradle Sync: **Passed**
- Build (`app:assembleDebug`): **Passed**

### Manual Verification Steps (Recommended for User)
1. Open the app on a device or emulator.
2. Tap **Select Area** and select two corners on the map.
3. Tap **Find Open Images**.
4. Select one or more images from the list.
5. Tap **Save** and verify the files in `Downloads/KartaView`.

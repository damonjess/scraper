# KartaView open-imagery integration

This Android project now uses **KartaView** rather than Google Street View for its downloadable street-level imagery workflow. KartaView is a public, open street-imagery platform whose published terms license the imagery under **CC BY-SA 4.0** and require the attribution **“© Grab and KartaView Contributors.”**

## Workflow in the app

1. Launch the app and select **Select Area**.
2. Tap two opposite corners on the map to draw a rectangular bounding area.
3. Tap **Find Open Images**. The app calls KartaView’s documented public photo endpoint with the selected bounding box.
4. Review the returned frames, select the ones you want, and tap **Save**.
5. The selected JPEG files are written to `Downloads/KartaView/<timestamp>/`.
6. The same folder contains `kartaview_manifest_<timestamp>.json` and `kartaview_manifest_<timestamp>.csv`, each with the image ID, sequence ID, coordinates, heading, capture timestamp, source URL, local file URI, licence, and attribution.

The first search result page is intentionally limited to 150 frames. Narrow the rectangle and search again if you need to inspect a more specific part of a dense area. This keeps the public API use and mobile memory footprint predictable.

## Android Studio

Open the root `scraper-master` directory in Android Studio, allow Gradle sync to complete, then select a device or emulator and run the `app` configuration. The project was compiled successfully with Android API 35.

No API key is required for this KartaView integration. The included implementation uses the public API and does not place secrets in the APK.

## Main source files

| File | Purpose |
|---|---|
| `MainActivity.kt` | Map-based rectangle selection, KartaView search UI, frame selection, and save action. |
| `KartaViewClient.kt` | Public KartaView bounding-box request and API response parsing. |
| `KartaViewExporter.kt` | Selected JPEG download plus JSON and CSV manifest export. |

## Attribution

Keep the KartaView credit visible wherever downloaded images are displayed or distributed:

> © Grab and KartaView Contributors · CC BY-SA 4.0

For current API details and licence terms, see:

- https://kartaview.org/doc/photos
- https://kartaview.org/terms

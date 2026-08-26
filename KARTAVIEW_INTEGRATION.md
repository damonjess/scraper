# Open street-imagery integration

This Android project searches **KartaView** and, optionally, **Mapillary** for geolocated street-level imagery within the rectangle selected on the map. The app shows one provider at a time so that source, attribution, and licence information remain clear for every downloaded image.

## Workflow

1. Tap **Select Area**.
2. Tap the first corner and then the opposite corner on the map.
3. Choose an imagery provider.
4. Tap **Find KartaView Images** or **Find Mapillary Images**.
5. Review returned frames, select the ones you need, and tap **Save**.
6. The selected JPEGs and both location manifests are saved in `Downloads/OpenStreetImages/<timestamp>/`.

Every JSON and CSV manifest records the provider, image ID, coordinates, heading, capture time, source URL, local URI, licence, and attribution.

## KartaView

KartaView is the default. It needs no API key. The provider rejects large single bounding-box requests, so the app automatically splits a large selected rectangle into KartaView-compatible tiles and combines the distinct results. The search is limited to 100 tiles; draw a smaller box if the app tells you the selection needs more tiles.

KartaView imagery is supplied under CC BY-SA 4.0. Keep the attribution visible where you use or distribute saved KartaView images:

> © Grab and KartaView Contributors

## Mapillary

Mapillary can supplement coverage where KartaView has no frames. It is useful, but it is **not token-free**. Create and register an application in [Mapillary’s developer dashboard](https://www.mapillary.com/dashboard/developers), then paste that app’s **client token** into the field displayed after choosing Mapillary. The token is used only in memory for the current app session and is not stored in the project or device preferences.

Mapillary limits regular bounding-box image queries to an area smaller than 0.01 degrees square. The app automatically divides a selected area into compliant queries, with a 100-tile maximum. The app retains the Mapillary attribution and CC BY-SA licence in every exported record. Follow Mapillary’s current developer terms, including its attribution rules, when displaying or distributing content.

## Android Studio

Open the root `scraper-master` directory in Android Studio, allow Gradle sync to complete, select a device or emulator, and run the `app` configuration. The source has been checked with the `compileDebugKotlin` Gradle task.

## Main files

| File | Purpose |
|---|---|
| `MainActivity.kt` | Status-safe map UI, rectangle selection, provider choice, result selection, and export action. |
| `StreetImage.kt` | Provider-neutral image record and source-attribution model. |
| `KartaViewClient.kt` | Tiled KartaView bounding-box search and response parsing. |
| `MapillaryClient.kt` | Token-based, tiled Mapillary image search and response parsing. |
| `StreetImageExporter.kt` | Downloads selected images and writes provider-aware JSON/CSV manifests. |

## Provider references

- https://kartaview.org/doc/photos
- https://kartaview.org/terms
- https://www.mapillary.com/developer/api-documentation
- https://www.mapillary.com/terms

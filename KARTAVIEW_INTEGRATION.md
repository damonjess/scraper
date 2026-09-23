# Open street-imagery integration

This Android project searches **KartaView** and, optionally, **Mapillary** for geolocated street-level imagery within the rectangle selected on the map. The app shows one provider at a time so that source, attribution, and licence information remain clear for every downloaded image.

## Workflow

1. Tap **Select Area**.
2. Tap the first corner and then the opposite corner on the map.
3. Choose an imagery provider.
4. Tap **Find KartaView Images** or **Find Mapillary Images**.
5. Review returned frames, select the ones you need, and tap **Save**.
6. The selected JPEGs and manifests are saved in `Documents/OpenStreetImages/<CityName>/<exportId>/`.

Every JSON and CSV manifest records the provider, image ID, coordinates, heading, capture time, source URL, local URI, licence, and attribution.

## KartaView

KartaView is the default. It needs no API key. The provider rejects large single bounding-box requests, so the app automatically splits a large selected rectangle into KartaView-compatible tiles and combines the distinct results. The search is limited to 100 tiles; draw a smaller box if the app tells you the selection needs more tiles.

KartaView imagery is supplied under CC BY-SA 4.0. Keep the attribution visible where you use or distribute saved KartaView images:

> © Grab and KartaView Contributors

## Mapillary

Mapillary can supplement coverage where KartaView has no frames. It is useful, but it is **not token-free**. Create and register an application in [Mapillary’s developer dashboard](https://www.mapillary.com/dashboard/developers), then paste that app’s **client token** into the field displayed after choosing Mapillary. The token is used only in memory for the current app session and is not stored in the project or device preferences.

Mapillary limits regular bounding-box image queries to an area smaller than 0.01 degrees square. The app automatically divides a selected area into compliant queries, with a 100-tile maximum. The app retains the Mapillary attribution and CC BY-SA licence in every exported record. Follow Mapillary’s current developer terms, including its attribution rules, when displaying or distributing content.

## Google Street View

Google Street View can supplement coverage where the open providers have no frames. Google offers no bounding-box search, so the app samples the selected rectangle with a **grid of points** and asks the Street View Static API’s metadata endpoint whether coverage exists near each point. Duplicate pano hits are merged, and only the images you select are downloaded.

To use it you need an **API key** from a Google Cloud project with the **Street View Static API** enabled:

1. Create a project at [console.cloud.google.com](https://console.cloud.google.com/) (a billing account must be attached, but you can cap usage so nothing is charged).
2. Enable **Street View Static API** under *APIs & Services → Library*.
3. Create an API key under *APIs & Services → Credentials*.
4. Paste the key into the field shown after choosing Google Street View in the app, or place it in `local.properties` as `google.streetview.key=…` so it is built into the app.

Cost control:

- **Metadata lookups (finding coverage) are free and unmetered.** The grid sweep never bills you.
- **Image downloads are billed** under the Static Street View SKU, which includes the first **10,000 per month free**; each saved image is one request. The app never downloads a pano twice: `thumbnailUrl` is empty and the image URL is only fetched when you tap Save.
- To guarantee $0, set a daily quota cap in *Google Cloud Console → Google Maps Platform → Quotas → Street View Static API* (e.g. 300/day ≈ 9,000/month). Requests then stop instead of billing.
- Images are capped at **640 × 640 px** by Google.

The sample **spacing** (10–500 m, default 50 m) controls grid density: smaller values find more panoramas but send more metadata requests. Areas needing more than 500 sample points are rejected; increase the spacing or draw a smaller area.

Google imagery is supplied under the Google Maps Platform Terms of Service (not a CC licence). The app shows the "© Google" attribution in the results panel and records it in every exported manifest; keep it wherever you use or distribute saved Street View images. The key embedded in image URLs is stripped from `manifest.json`.

## Android Studio

Open the root `scraper-master` directory in Android Studio, allow Gradle sync to complete, select a device or emulator, and run the `app` configuration. The source has been checked with the `compileDebugKotlin` Gradle task.

## Main files

| File | Purpose |
|---|---|
| `MainActivity.kt` | Status-safe map UI, rectangle selection, provider choice, result selection, and export action. |
| `StreetImage.kt` | Provider-neutral image record and source-attribution model. |
| `KartaViewClient.kt` | Tiled KartaView bounding-box search and response parsing. |
| `GoogleStreetViewClient.kt` | Grid-sampled Street View Static API metadata search and pano mapping. |
| `MapillaryClient.kt` | Token-based, tiled Mapillary image search and response parsing. |
| `StreetImageExporter.kt` | Downloads selected images and writes provider-aware JSON/CSV manifests. |

## Provider references

- https://kartaview.org/doc/photos
- https://kartaview.org/terms
- https://www.mapillary.com/developer/api-documentation
- https://www.mapillary.com/terms
- https://developers.google.com/maps/documentation/streetview
- https://mapsplatform.google.com/terms/

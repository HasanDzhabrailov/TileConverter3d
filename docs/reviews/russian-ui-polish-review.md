# Russian UI Polish Review

Review date: 2026-05-07

Scope reviewed:

- `AGENTS.md`
- `docs/terrain-ui-redesign-plan.md`
- `docs/terrain-ui-redesign-status.md`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/AppState.kt`
- `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Api.kt`
- `kotlin/terrain-web-ui/src/jsMain/resources/app.css`

## Findings

1. Medium - MBTiles quick-copy buttons can still copy current-host or localhost URLs when no mobile address is detected.

   `MbtilesCatalogPanel` shows the Russian "Не удалось определить адрес для телефона" warning when `activeMobileAddress` is null, but the tileset card still builds and exposes copy buttons from `publicTileUrlTemplate`, `publicMobileStyleUrl`, `publicStyleUrl`, `publicTilejsonUrl`, or the relative `tileUrlTemplate`. When the UI is opened on `localhost` and no active LAN/mobile address is available, `ApiClient.absoluteUrl(...)` turns relative fallbacks into `http://localhost...` copy targets. That violates the mobile-link requirement that copyable mobile links use the LAN/mobile address and not localhost.

   References:

   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:267`
   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:306`
   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:334`
   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:343`

   Recommendation: only render the mobile quick-copy buttons when `activeMobileAddress` is present, or disable them with the same Russian LAN-address error. If public URLs are retained as a fallback, reject localhost/current-host public values for the mobile copy path.

2. Medium - The MBTiles list still displays raw English/API source type values.

   The tileset cards use `tileset.sourceType.serializedName()`, so users see `raster` or `raster-dem` in the main MBTiles list. The selected tileset details correctly use `sourceType.label()`, but the card copy is still user-facing and does not satisfy the Russian-only/source-type-label polish requirement.

   Reference:

   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:301`

   Recommendation: use `tileset.sourceType.label()` on the card as well, preserving `raster-dem` only where it is an API value or technical endpoint data.

3. Low - The MBTiles card copy actions are four identical `Копировать` buttons with no visible target labels.

   The card exposes copy buttons for tiles, mobile style, style, and TileJSON, but each button only says `Копировать`; only the tile URL is visibly shown above them. On mobile, where the selected-detail section may be farther down the page, users cannot tell which button copies tiles, style, style-mobile, or TileJSON. This weakens the required copyable mobile link experience even though the detailed selected-tileset rows are labeled.

   References:

   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:334`
   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:337`
   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:341`
   - `kotlin/terrain-web-ui/src/jsMain/kotlin/com/terrainconverter/web/ui/Main.kt:345`

   Recommendation: label the card buttons in Russian, for example `Копировать тайлы`, `Копировать стиль`, `Копировать стиль для телефона`, and `Копировать TileJSON`, while keeping the post-click states exactly `Скопировано` and `Не удалось скопировать`.

## Notes

- Job mobile links include `style` and `style-mobile` with the selected `base` parameter.
- MBTiles selected-detail links include selected `base` for `style` and `style-mobile`.
- Copy feedback uses the required states: `Копировать`, `Скопировано`, and `Не удалось скопировать`.
- The responsive CSS still collapses the layout and link rows at mobile widths; no layout regression was found from static review.

## Suggested Verification After Fixes

- Run `gradle -p kotlin/terrain-web-ui syncFrontendDist`.
- Manually open the UI through `localhost` with no detected LAN/mobile address and verify mobile copy buttons do not produce localhost URLs.
- Select a non-default base source and verify copied job and MBTiles `style` / `style-mobile` URLs include `?base={selectedSourceId}`.

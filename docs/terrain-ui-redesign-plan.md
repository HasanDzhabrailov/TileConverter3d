# Terrain UI Redesign Plan

This is the canonical saved implementation plan for the Terrain Converter UI/UX redesign. Later sessions must read this file, `docs/terrain-ui-redesign-status.md`, and any saved review in `docs/reviews/terrain-ui-redesign-plan-review.md` before continuing work. Do not rely on chat history.

## Goals

- Make every user-facing UI string Russian-only.
- Preserve the existing backend workflow: upload -> conversion job -> logs/status -> artifacts -> tile serving.
- Preserve Terrain-RGB, tile pyramid, MBTiles, TileJSON, and style semantics unless this plan explicitly changes UI/API behavior.
- Start Preview as a normal 2D OpenStreetMap map.
- Keep 3D disabled until a completed conversion or a selected `raster-dem` MBTiles source exists.
- Auto-select the completed conversion job and switch Preview to 3D when conversion completes.
- Remove the confusing `Uploaded base` option from Preview.
- Persist global base map sources on the backend in SQLite.
- Generate desktop and mobile styles dynamically with `?base={sourceId}`.
- Provide copyable mobile server links that use the LAN/mobile address instead of localhost.
- Show real-time MBTiles upload/startup progress before the tile server is ready.
- Add selectable cache clearing with checkboxes.
- Keep implementation context and reviews saved across sessions.

## Non-Goals

- Do not redesign terrain conversion math or Terrain-RGB encoding.
- Do not add Python runtime, tooling, or script dependencies.
- Do not merge terrain DEM data with base map MBTiles.
- Do not change CLI flags or artifacts.
- Do not remove uploaded MBTiles list/server behavior.
- Do not make mixed `1201` and `3601` HGT inputs supported.

## Product Rules

- Russian-only copy applies to buttons, labels, placeholders, toast messages, errors, empty states, tooltips, tables, cards, progress labels, and copied-link feedback.
- Provider names such as `OpenStreetMap`, `Google`, `ESRI`, `MBTiles`, `TileJSON`, and file extensions may remain as proper nouns/technical terms.
- Preview defaults to 2D OpenStreetMap with no terrain required.
- Preview 3D is disabled when there is no completed job and no selected uploaded MBTiles with `source_type=raster-dem`.
- Conversion completion auto-switches to 3D only for the newly completed conversion job.
- Uploaded `raster-dem` MBTiles should show that 3D is available, but should not unexpectedly steal focus from an active job preview.
- Uploaded raster/vector/base MBTiles are not terrain sources and must not enable 3D terrain mode.
- `Uploaded base` must be removed from Preview mode/source choices. Uploaded MBTiles remain visible in the MBTiles server list.
- Base map source selection is global UI state and is passed to style endpoints using `?base={sourceId}`.
- Custom base map sources are global backend data, not browser-local data.
- Mobile links must use the active LAN/mobile server address exposed by the backend/UI, not `localhost` or `127.0.0.1`.

## Initial UX

The first visible Preview state is 2D OpenStreetMap:

```text
┌─ Предпросмотр карты ───────────────────────────────┐
│ [2D карта] [3D рельеф недоступен]                  │
│ Подложка: [OpenStreetMap ▼] [Управление]           │
│                                                    │
│                2D OpenStreetMap                    │
│                                                    │
│ Как включить 3D:                                   │
│ 1. Загрузите HGT/ZIP в «Конвертация рельефа».      │
│ 2. Или загрузите MBTiles типа «Рельеф DEM».        │
└────────────────────────────────────────────────────┘
```

Disabled 3D tooltip/caption:

```text
3D режим недоступен. Нужен источник рельефа: завершённая конвертация HGT или MBTiles типа «Рельеф DEM».
```

After conversion completes:

- Select the completed job.
- Switch Preview to `3D рельеф`.
- Keep the currently selected base source.
- Show: `Рельеф готов. 3D просмотр включён.`

For uploaded DEM MBTiles:

- Show `3D доступен` on the tileset card.
- Provide `Открыть в 3D`.
- Switch to 3D only when the user selects it.

## Main Layout

```text
┌─ Тайловый сервер MBTiles ─────────────┐ ┌─ Предпросмотр карты ───────────────┐
│ Загрузка .mbtiles                     │ │ 2D/3D переключатель                │
│ Прогресс загрузки и подготовки        │ │ Выбор подложки                     │
│ Загруженные наборы тайлов             │ │ MapLibre preview                   │
├─ Конвертация рельефа ─────────────────┤ ├─ Статус задачи ───────────────────┤
│ HGT/ZIP, параметры, запуск            │ │ Артефакты и ссылки                 │
├─ Задачи ──────────────────────────────┤ ├─ Логи конвертации ────────────────┤
│ Карточки задач и признаки 3D          │ │ Живые логи                         │
└───────────────────────────────────────┘ ├─ Система ─────────────────────────┤
                                          │ Хранилище и очистка кэша           │
                                          └───────────────────────────────────┘
```

Use the existing Compose UI structure where possible. Prefer small, direct changes over a new design system.

## Base Map Sources

Builtin source IDs:

- `openstreetmap` - OpenStreetMap
- `opentopomap` - OpenTopoMap
- `cartodb-positron` - CartoDB Positron
- `cartodb-dark-matter` - CartoDB Dark Matter
- `esri-satellite` - Спутник ESRI
- `none` - Без подложки

Potential commercial/regional providers such as Google and Yandex must not be hard-coded unless license, attribution, public access, and key requirements are confirmed. If added later, keep them behind explicit configuration.

Backend model:

```kotlin
data class BaseMapSource(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val attribution: String?,
    val maxZoom: Int,
    val isBuiltin: Boolean,
    val createdAt: String?,
    val updatedAt: String?,
)
```

Persistence:

- Store custom sources globally in the backend SQLite database.
- Seed builtin sources at startup or expose them from code through the same repository/service layer.
- Do not store custom sources only in browser local storage.
- Builtin source IDs must be stable because links and style URLs can reference them.

API:

```text
GET    /api/base-sources
POST   /api/base-sources
PUT    /api/base-sources/{sourceId}
DELETE /api/base-sources/{sourceId}
```

Validation:

- `name` is not blank.
- `urlTemplate` contains `{z}`, `{x}`, and `{y}` unless source ID is `none`.
- `urlTemplate` starts with `http://`, `https://`, or `/`.
- `maxZoom` is in `1..22`.
- Custom source IDs are generated or sanitized by backend code.
- Builtin sources cannot be edited or deleted.
- Delete rejects sources that are currently selected only if the UI cannot safely fall back to `openstreetmap`.

## Dynamic Style Endpoints

Desktop style endpoints:

```text
GET /api/jobs/{jobId}/style?base={sourceId}
GET /api/mbtiles/{tilesetId}/style?base={sourceId}
```

Mobile style endpoints:

```text
GET /api/jobs/{jobId}/style-mobile?base={sourceId}
GET /api/mbtiles/{tilesetId}/style-mobile?base={sourceId}
```

Rules:

- Missing `base` defaults to `openstreetmap`.
- `base=none` generates terrain/hillshade without a raster base layer.
- Unknown `base` returns a clear Russian API error where applicable and an appropriate HTTP status.
- Desktop and mobile styles use the same selected source for the same `base` value.
- Preserve MapLibre terrain defaults: `encoding=mapbox`, `tileSize=256`, `scheme=xyz`, and default terrain tile URL semantics unless existing job data says otherwise.
- Job `tiles.json` and `style.json` continue to use relative `/api/jobs/...` URLs where existing contracts require relative URLs.
- Public/copyable mobile links are generated separately with the active LAN/mobile address.

Optional future style type parameter, only if needed after the core redesign:

```text
type=standard
type=terrain-only
type=base-only
```

## Preview Logic

Suggested frontend state:

```kotlin
enum class PreviewMode { TWO_D, THREE_D }

sealed class TerrainPreviewSource {
    data class Job(val jobId: String) : TerrainPreviewSource()
    data class MbtilesDem(val tilesetId: String) : TerrainPreviewSource()
}
```

3D availability:

```text
selectedJob.status == completed OR selectedTileset.source_type == raster-dem
```

2D behavior:

- Always available.
- Uses selected base source.
- Defaults to `openstreetmap`.
- Does not require a job or uploaded MBTiles.

3D behavior:

- Disabled until there is a terrain source.
- Uses completed job terrain or selected DEM MBTiles terrain.
- Uses selected base source as the draped/context base layer.
- Shows Russian guidance when disabled.

Remove these concepts from Preview:

- `Uploaded base`
- Any mode that treats a generic uploaded MBTiles raster as a terrain DEM source.

Keep these outside Preview mode selection:

- Uploaded MBTiles list.
- Copy links for uploaded MBTiles.
- `Открыть в 2D` for raster tilesets if existing behavior supports it.
- `Открыть в 3D` only for `raster-dem` tilesets.

## MBTiles Upload Progress

The UI must show real-time progress before the tile server is ready. The user must not wait on a silent request for large MBTiles files.

Stages:

1. `uploading` - `Загрузка файла на сервер`
2. `validating` - `Проверка MBTiles`
3. `reading_metadata` - `Чтение метаданных`
4. `detecting_type` - `Определение типа данных`
5. `preparing_server` - `Подготовка ссылок тайлового сервера`
6. `ready` - `Готово`
7. `error` - `Ошибка`
8. `cancelled` - `Отменено`

Progress UI:

```text
┌─ Запуск тайлового сервера ────────────────────────┐
│ Файл: big-terrain.mbtiles                          │
│ ███████████████░░░░░ 72%                           │
│ 144 МБ / 200 МБ • 2.8 МБ/с                         │
│ Текущий статус: Чтение metadata и bounds           │
│ [Отменить загрузку]                                │
└────────────────────────────────────────────────────┘
```

Implementation expectations:

- Use browser upload progress when available for file transfer progress.
- Use backend job/progress events for validation, metadata, source type detection, and server preparation.
- Keep progress cards visible until the ready tileset appears in the uploaded MBTiles list.
- On error, keep the failed card with a Russian error message and retry guidance.
- If cancellation is not supported end-to-end, hide or disable cancel with Russian explanation.

## Mobile Links

Mobile link UX must remain copyable and must use the active LAN/mobile address.

For uploaded MBTiles tilesets, expose copy buttons for:

- `Тайлы`
- `Style`
- `Mobile Style`
- `TileJSON`

For conversion jobs, expose copy buttons for:

- `Тайлы рельефа`
- `Style`
- `Mobile Style`
- `TileJSON`

Style links include the selected base source:

```text
/style?base={selectedBaseSourceId}
/style-mobile?base={selectedBaseSourceId}
```

If LAN/mobile address cannot be detected, show:

```text
Не удалось определить адрес для телефона. Проверьте, что телефон и компьютер подключены к одной сети.
```

Copy feedback:

- `Скопировано`
- `Не удалось скопировать`
- `Ссылка использует адрес для телефона: {address}`

## Cache Management

API:

```text
GET    /api/system/storage
DELETE /api/system/cache
```

Selectable checkbox groups:

- `Завершённые задачи`
- `Задачи с ошибкой`
- `Выполняющиеся задачи`
- `Загруженные MBTiles`
- `Пользовательские подложки`

Rules:

- Builtin base sources are never deleted.
- Selected cache/data must be removed from disk, backend state, and UI lists.
- Running jobs require an explicit checkbox and warning.
- If running job cancellation/removal is not supported, disable that checkbox and explain why in Russian.
- Clearing uploaded MBTiles removes server/list state and any copied links become invalid.
- Clearing custom sources falls back selected base source to `openstreetmap` if the active source was deleted.
- Show a confirmation summary before deletion.
- Refresh storage stats and relevant UI lists after deletion.

## Russian Copy Checklist

Translate or replace at least these areas:

- Navigation and section titles.
- Upload forms and drag/drop text.
- Conversion options and validation errors.
- Job statuses and artifact labels.
- Preview controls, empty states, and 3D disabled guidance.
- MBTiles cards, progress states, and source type labels.
- Base source management dialogs/forms.
- Copy buttons and clipboard feedback.
- Cache/storage labels, warnings, and confirmations.
- Generic network/API errors.

Preferred labels:

- `Тайловый сервер MBTiles`
- `Конвертация рельефа`
- `Предпросмотр карты`
- `Логи конвертации`
- `Копировать`
- `Скопировано`
- `Не удалось скопировать`
- `Начать конвертацию`
- `Запустить тайловый сервер`
- `Очистить выбранное`
- `Подложка`
- `Управление подложками`
- `3D рельеф`
- `2D карта`

## Implementation Phases

1. Review this saved plan and write findings to `docs/reviews/terrain-ui-redesign-plan-review.md`.
2. Implement backend SQLite persistence and CRUD for global base map sources.
3. Implement dynamic desktop/mobile style generation with `?base={sourceId}`.
4. Implement frontend base source selection and management in Russian.
5. Implement Preview defaults, remove `Uploaded base`, and gate 3D availability.
6. Implement conversion-complete auto-selection and auto-switch to 3D.
7. Implement real-time MBTiles upload/startup progress.
8. Implement copyable mobile links using the LAN/mobile address and selected base source.
9. Implement cache management with selectable checkboxes and state refresh.
10. Polish remaining Russian copy and responsive desktop/mobile layout.
11. Update `README.md`, `deploy/docker/README.md`, and related docs if runtime/API flow changes.
12. Run verification and save final status.

## Review Workflow

- Before implementation, run `/review-terrain-ui-redesign-plan` and save the review to `docs/reviews/terrain-ui-redesign-plan-review.md`.
- Each implementation session must read `AGENTS.md`, this plan, `docs/terrain-ui-redesign-status.md`, and the saved review if present.
- After each non-review build stage, update `docs/terrain-ui-redesign-status.md` with completed work, verification, blockers, and the next command.
- If a review identifies required plan changes, update this plan before building.
- Do not mark the redesign complete until final verification and docs updates are recorded in the status file.

## Verification

Automated checks:

- Backend changes: `gradle :terrain-web:test`
- Frontend changes: `gradle -p kotlin/terrain-web-ui syncFrontendDist`
- Final full check: `gradle test` and `gradle -p kotlin/terrain-web-ui syncFrontendDist`

Manual checks:

1. App opens with 2D OpenStreetMap Preview.
2. All visible UI copy is Russian-only except proper nouns/technical names.
3. 3D toggle is disabled with clear Russian guidance when no terrain source exists.
4. `Uploaded base` is absent from Preview.
5. Custom base source persists after reload and is loaded from backend SQLite.
6. Desktop job style changes with `?base={sourceId}`.
7. Desktop MBTiles style changes with `?base={sourceId}`.
8. Mobile job style changes with `?base={sourceId}`.
9. Mobile MBTiles style changes with `?base={sourceId}`.
10. Mobile copy buttons use LAN/mobile URLs, not localhost URLs.
11. MBTiles upload shows real-time progress and stage changes before readiness.
12. Existing uploaded MBTiles list remains visible and usable.
13. Uploaded raster MBTiles does not enable 3D terrain.
14. Uploaded `raster-dem` MBTiles enables explicit `Открыть в 3D`.
15. Completed conversion auto-selects the job and switches Preview to 3D.
16. Cache clearing removes selected data from disk, backend state, and UI lists.
17. Cache clearing preserves builtin base sources.
18. Layout remains usable on desktop and mobile widths.

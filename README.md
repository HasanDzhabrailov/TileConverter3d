# Конвертер HGT/SRTM в MapLibre Terrain-RGB

Этот репозиторий содержит офлайн-конвертер, который берет файлы высот SRTM/HGT (`.hgt`) и генерирует terrain-тайлы для MapLibre.

Основные результаты работы:

- `terrain-rgb.mbtiles`
- `terrain/{z}/{x}/{y}.png`
- `terrain/tiles.json`
- `style.json`

Конвертер не изменяет базовую карту и не встраивает terrain в существующий MBTiles. Terrain DEM создается как отдельный источник `raster-dem`.

## Что нужно

- Python `3.11+`
- входные файлы `.hgt` формата SRTM

Поддерживаются только HGT-сетки:

- `1201 x 1201`
- `3601 x 3601`

Смешивать оба разрешения в одном запуске нельзя.

## Установка

Из корня репозитория:

```bash
python -m pip install -e ./converter[test]
```

Если тесты не нужны, можно ставить без extra, но обычно удобнее оставить как есть.

## Быстрый запуск

Запуск из директории `converter/`:

```bash
python -m terrain_converter.cli <путь-к-hgt-файлу-или-папке> --minzoom 8 --maxzoom 12
```

Пример:

```bash
cd converter
python -m terrain_converter.cli ../data/hgt --minzoom 8 --maxzoom 12
```

По умолчанию будут созданы:

- `terrain-rgb.mbtiles`
- `terrain/tiles.json`
- `terrain/{z}/{x}/{y}.png`
- `style.json`

## Основные параметры

```bash
python -m terrain_converter.cli INPUT [INPUT ...] \
  --output-mbtiles terrain-rgb.mbtiles \
  --tile-root terrain \
  --tilejson terrain/tiles.json \
  --style-json style.json \
  --tiles-url http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png \
  --minzoom 8 \
  --maxzoom 12 \
  --name terrain-dem
```

Где:

- `INPUT` - один `.hgt` файл или папка с `.hgt` / `.HGT`
- `--output-mbtiles` - путь к итоговому MBTiles
- `--tile-root` - корень файловой пирамиды PNG-тайлов
- `--tilejson` - путь к `tiles.json`
- `--style-json` - путь к `style.json`
- `--tiles-url` - URL-шаблон, который MapLibre будет использовать для загрузки terrain PNG
- `--minzoom` / `--maxzoom` - диапазон генерируемых zoom-уровней
- `--name` - имя слоя в metadata MBTiles

## Как работает конвертер

1. Проверяет входные `.hgt` файлы.
2. Читает signed 16-bit big-endian высоты.
3. Строит покрытие по границам входных HGT.
4. Генерирует PNG тайлы `256x256` в формате Terrain-RGB.
5. Записывает те же тайлы:
   - в файловую структуру `terrain/{z}/{x}/{y}.png`
   - в `terrain-rgb.mbtiles`
6. Создает `terrain/tiles.json` и `style.json` для MapLibre.

Пиксели вне DEM-покрытия или на неразрешимых `void`-значениях делаются полностью прозрачными.

## Что лежит в выходных файлах

### `terrain-rgb.mbtiles`

SQLite MBTiles с PNG-тайлами terrain DEM.

### `terrain/{z}/{x}/{y}.png`

Обычная файловая пирамида terrain-тайлов. Ее удобно раздавать локальным HTTP-сервером.

### `terrain/tiles.json`

TileJSON для источника MapLibre `raster-dem`.

### `style.json`

Минимальный MapLibre style, уже ссылающийся на terrain source.

## Как использовать результат в MapLibre

По умолчанию конвертер ожидает, что terrain PNG будут доступны по адресу:

```text
http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png
```

То есть после генерации нужно раздать папку с результатом через HTTP.

Если URL будет другим, укажите его во время конвертации через `--tiles-url`.

Пример источника terrain:

```json
{
  "type": "raster-dem",
  "tiles": [
    "http://127.0.0.1:8080/terrain/{z}/{x}/{y}.png"
  ],
  "encoding": "mapbox",
  "tileSize": 256,
  "scheme": "xyz"
}
```

## Проверка и тесты

Из корня репозитория:

```bash
python -m pytest
```

Если `pytest` еще не установлен, сначала выполните:

```bash
python -m pip install -e ./converter[test]
```

Быстрая smoke-проверка без запуска тестов:

```bash
cd converter
python -m compileall terrain_converter tests
```

## Типовой сценарий работы

1. Подготовить папку с `.hgt` файлами одного разрешения.
2. Установить зависимости.
3. Запустить конвертацию с нужным диапазоном `zoom`.
4. Раздать папку `terrain/` через HTTP.
5. Подключить `style.json` или `terrain/tiles.json` в MapLibre-клиенте.

## Ограничения

- нет смешивания `1201` и `3601` в одном запуске
- поддерживаются только валидные HGT-имена вроде `N45E006.hgt`
- terrain генерируется как отдельный DEM-источник, а не как патч к существующей карте

## Дополнительно

- `docs/terrain-pipeline.md` - краткое описание pipeline
- `android-demo/` - минимальные файлы для Android/MapLibre demo
- `AGENTS.md` - короткие repo-specific инструкции для OpenCode

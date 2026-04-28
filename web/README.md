# Terrain Converter Web

## Возможности

- загрузка файлов `.hgt` или `.zip`-архива с HGT-файлами
- необязательная загрузка `base.mbtiles` для подложки в превью
- настройка режима `bbox`, диапазона zoom, размера тайла, схемы и encoding
- запуск `terrain-converter` через backend-обёртку для задач
- потоковая передача логов и статуса задачи через WebSocket
- скачивание `terrain-rgb.mbtiles`, `tiles.json` и `style.json`
- превью результата через MapLibre GL JS
- раздача сгенерированных terrain-тайлов и необязательных base MBTiles через backend

## Локальная разработка

Быстрый запуск на Windows из корня репозитория:

```bat
start-web.cmd
```

Скрипт открывает backend и frontend в отдельных окнах `cmd`.

### Backend

Запускать из корня репозитория:

```bash
python -m pip install -e ./converter
python -m pip install -e ./web/backend[test]
uvicorn app.main:app --host 0.0.0.0 --port 8080 --app-dir web/backend
```

### Frontend

```bash
cd web/frontend
npm install
npm run dev
```

Dev-сервер фронтенда проксирует `/api` и `/ws` на `http://127.0.0.1:8080`.

## Обзор API

- `POST /api/jobs` создать задачу конвертации
- `GET /api/jobs` получить список задач
- `GET /api/jobs/{jobId}` получить детали задачи
- `GET /api/jobs/{jobId}/logs` получить логи
- `GET /api/jobs/{jobId}/terrain/{z}/{x}/{y}.png` отдать PNG terrain-тайл
- `GET /api/jobs/{jobId}/base/{z}/{x}/{y}` отдать тайл из необязательного base MBTiles
- `GET /api/jobs/{jobId}/downloads/{artifact}` скачать артефакт
- `GET /api/jobs/{jobId}/style` получить MapLibre style для превью
- `WS /ws/jobs/{jobId}` получать события задачи и логи в реальном времени
- `POST /api/mbtiles` загрузить готовый `.mbtiles` и начать раздачу тайлов
- `GET /api/mbtiles/{tilesetId}/{z}/{x}/{y}` получить тайл из загруженного `.mbtiles`
- `GET /api/mbtiles/{tilesetId}/metadata` получить metadata MBTiles
- `GET /api/mbtiles/{tilesetId}/tilejson` получить готовый TileJSON
- `GET /api/mbtiles/{tilesetId}/style` получить готовый style.json
- `GET /api/mbtiles/{tilesetId}/style-mobile` получить style для MapLibre Native mobile

Если открывать UI по IP-адресу машины, ссылки на тайлы для `MBTiles server` тоже будут формироваться через этот IP, что удобно для проверки с мобильного устройства.

Для мобильного приложения внутри Wi-Fi сети используй absolute URL из API ответов:

- `job.artifacts.public_terrain_tile_url_template`
- `job.artifacts.public_tilejson`
- `job.artifacts.public_stylejson`
- `mbtiles.public_tile_url_template`

Эти ссылки backend формирует через IP машины в локальной сети, если UI открыт не через LAN-адрес.

Важно: по спецификации MapLibre `terrain` не поддерживается в `MapLibre Native Android/iOS`, поэтому для мобильного клиента используй `style-mobile`, который строит рельеф через `hillshade` поверх OpenStreetMap.

Если на машине несколько сетевых адаптеров и backend выбрал не тот IP, задай его явно через переменную окружения:

```bash
set TERRAIN_WEB_PUBLIC_HOST=172.20.10.2
```

После этого перезапусти backend.

## Docker

```bash
docker compose -f web/docker-compose.yml up --build
```

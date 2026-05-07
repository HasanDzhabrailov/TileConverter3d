// Service Worker для кэширования тайлов
// Кэширует тайлы MapLibre и API запросы с долгосрочным хранением

const CACHE_NAME = 'terrain-converter-tiles-v1';
const TILE_CACHE_MAX_AGE = 30 * 24 * 60 * 60 * 1000; // 30 дней

// Паттерны URL для кэширования
const CACHE_PATTERNS = [
  /\/api\/jobs\/[^/]+\/terrain\/\d+\/\d+\/\d+\.png/,
  /\/api\/mbtiles\/[^/]+\/tiles\/\d+\/\d+\/\d+\.png/,
  /tile\.openstreetmap\.org/,
  /cartodb\.com/,
  /stamen\.com/,
  /arcgisonline\.com/,
];

// Проверка, нужно ли кэшировать URL
function shouldCache(url) {
  return CACHE_PATTERNS.some(pattern => pattern.test(url));
}

// Установка SW
self.addEventListener('install', (event) => {
  console.log('[SW] Installing...');
  self.skipWaiting();
});

// Активация SW
self.addEventListener('activate', (event) => {
  console.log('[SW] Activating...');
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames
          .filter((name) => name !== CACHE_NAME)
          .map((name) => caches.delete(name))
      );
    }).then(() => self.clients.claim())
  );
});

// Обработка fetch запросов
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);
  
  // Кэшируем только GET запросы
  if (request.method !== 'GET') {
    return;
  }
  
  // Проверяем, нужно ли кэшировать этот URL
  if (!shouldCache(url.href)) {
    return;
  }
  
  event.respondWith(handleTileRequest(request));
});

// Обработка запроса тайла с кэшированием - Cache First стратегия
async function handleTileRequest(request) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(request);
  
  // Если есть в кэше и он не слишком старый — возвращаем из кэша
  if (cached) {
    const cachedDate = cached.headers.get('sw-cached-date');
    const age = cachedDate ? (Date.now() - parseInt(cachedDate)) : Infinity;
    
    // Если кэш свежий — возвращаем его
    if (age < TILE_CACHE_MAX_AGE) {
      return cached;
    }
    
    // Кэш устарел, пробуем обновить, но если не получится — вернём старый
    try {
      return await fetchAndCache(request, cache);
    } catch (error) {
      console.log('[SW] Network failed, using stale cache');
      return cached;
    }
  }
  
  // Нет в кэше — загружаем из сети и кэшируем
  try {
    return await fetchAndCache(request, cache);
  } catch (error) {
    console.error('[SW] Failed to fetch tile:', error);
    throw error;
  }
}

// Загрузка и кэширование
async function fetchAndCache(request, cache) {
  const response = await fetch(request);
  
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
  
  // Клонируем ответ, так как его можно прочитать только один раз
  const responseToCache = response.clone();
  
  // Добавляем заголовок с датой кэширования
  const headers = new Headers(responseToCache.headers);
  headers.set('sw-cached-date', Date.now().toString());
  headers.set('cache-control', 'public, max-age=2592000'); // 30 дней
  
  const cachedResponse = new Response(responseToCache.body, {
    status: responseToCache.status,
    statusText: responseToCache.statusText,
    headers: headers,
  });
  
  await cache.put(request, cachedResponse);
  console.log('[SW] Cached tile:', request.url);
  
  // Очищаем старые тайлы если кэш слишком большой
  await cleanupCache(cache);
  
  return response;
}

// Очистка старых записей из кэша
async function cleanupCache(cache) {
  const keys = await cache.keys();
  const maxCacheSize = 1000; // Максимум 1000 тайлов
  
  if (keys.length > maxCacheSize) {
    // Удаляем самые старые записи
    const entries = await Promise.all(
      keys.map(async (key) => {
        const response = await cache.match(key);
        const date = response?.headers.get('sw-cached-date');
        return { key, date: date ? parseInt(date) : 0 };
      })
    );
    
    entries.sort((a, b) => a.date - b.date);
    const toDelete = entries.slice(0, keys.length - maxCacheSize);
    
    await Promise.all(toDelete.map(entry => cache.delete(entry.key)));
    console.log(`[SW] Cleaned up ${toDelete.length} old tiles`);
  }
}

// Обработка сообщений от клиента
self.addEventListener('message', (event) => {
  if (event.data === 'clear-tile-cache') {
    caches.delete(CACHE_NAME).then(() => {
      console.log('[SW] Tile cache cleared');
      event.ports[0].postMessage({ success: true });
    });
  } else if (event.data === 'get-cache-stats') {
    caches.open(CACHE_NAME).then(async (cache) => {
      const keys = await cache.keys();
      let totalSize = 0;
      
      for (const key of keys) {
        const response = await cache.match(key);
        if (response) {
          const blob = await response.blob();
          totalSize += blob.size;
        }
      }
      
      event.ports[0].postMessage({ 
        tileCount: keys.length, 
        totalSize: totalSize 
      });
    });
  }
});

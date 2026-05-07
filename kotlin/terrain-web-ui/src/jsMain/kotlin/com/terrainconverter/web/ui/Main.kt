package com.terrainconverter.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.launch
import kotlin.js.Date
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Small
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.files.File
import org.w3c.xhr.FormData
import kotlin.js.Promise

fun main() {
    installAppErrorFallback()
    renderComposable(rootElementId = "root") {
        App()
    }
}

@Composable
private fun App() {
    val state = rememberAppState()

    Div(attrs = {
        attr("class", "app-shell")
    }) {
        Div(attrs = { attr("class", "app-header") }) {
            H1 {
                Text("Terrain Converter")
            }
            P {
                Text("Конвертируйте рельеф, запускайте MBTiles, проверяйте предпросмотр карты и следите за логами задач.")
            }
        }

        if (state.isLoading && state.jobs.isEmpty() && state.tilesets.isEmpty()) {
            StatusCard("Загрузка приложения", "Получаем состояние сервера, задачи и загруженные MBTiles.")
        } else {
            Dashboard(state)
        }
    }
}

@Composable
private fun Dashboard(state: AppState) {
    Div(attrs = { attr("class", "layout") }) {
        Div(attrs = { attr("class", "left-column") }) {
            MbtilesCatalogPanel(state)
            CustomBasemapPanel(state)
            SystemCachePanel(state)
            ConvertFormPanel(state)
            JobSelectionPanel(state)
        }
        Div(attrs = { attr("class", "right-column") }) {
            Div(attrs = { attr("class", "warning-stack") }) {
                state.refreshError?.let {
                    StatusCard("Предупреждение обновления", it)
                }
                state.jobDetailsError?.let {
                    StatusCard("Предупреждение логов", it)
                }
                state.websocketError?.let {
                    StatusCard("Предупреждение WebSocket", it)
                }
            }
            MapPreviewPanel(state)
            JobStatusPanel(state)
            DownloadPanel(state.selectedJob)
            LogsPanel(state)
        }
    }
}

@Composable
private fun CustomBasemapPanel(state: AppState) {
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var urlTemplate by remember { mutableStateOf("") }
    var attribution by remember { mutableStateOf("") }
    var maxZoom by remember { mutableStateOf("19") }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }

    val customSources = state.baseSources.filter { !it.isBuiltin }

    fun resetForm() {
        name = ""
        urlTemplate = ""
        attribution = ""
        maxZoom = "19"
        error = null
    }

    fun submit() {
        if (isSubmitting) return
        error = null
        success = null

        val trimmedName = name.trim()
        val trimmedUrl = urlTemplate.trim()
        val trimmedAttribution = attribution.trim().takeIf { it.isNotEmpty() }
        val zoom = maxZoom.toIntOrNull()

        if (trimmedName.isBlank()) {
            error = "Введите название подложки."
            return
        }
        if (trimmedUrl.isBlank()) {
            error = "Введите URL шаблон тайлов."
            return
        }
        if (!trimmedUrl.contains("{z}") || !trimmedUrl.contains("{x}") || !trimmedUrl.contains("{y}")) {
            error = "URL шаблон должен содержать {z}, {x} и {y}."
            return
        }
        if (zoom == null || zoom < 1 || zoom > 22) {
            error = "Максимальный zoom должен быть от 1 до 22."
            return
        }

        isSubmitting = true
        scope.launch {
            runCatching {
                ApiClient.createBaseSource(
                    BaseMapSourceRequest(
                        name = trimmedName,
                        urlTemplate = trimmedUrl,
                        attribution = trimmedAttribution,
                        maxZoom = zoom,
                    )
                )
            }.onSuccess { source ->
                state.baseSources = state.baseSources + source
                success = "Подложка \"${source.name}\" добавлена."
                resetForm()
            }.onFailure {
                error = it.message ?: "Не удалось добавить подложку."
            }
            isSubmitting = false
        }
    }

    fun deleteSource(source: BaseMapSource) {
        if (!window.confirm("Удалить подложку \"${source.name}\"?")) return
        scope.launch {
            runCatching { ApiClient.deleteBaseSource(source.id) }
                .onSuccess { deleted ->
                    if (deleted) {
                        state.baseSources = state.baseSources.filter { it.id != source.id }
                        if (state.selectedBaseSourceId == source.id) {
                            state.selectedBaseSourceId = "openstreetmap"
                        }
                    }
                }
                .onFailure {
                    error = "Не удалось удалить подложку: ${it.message}"
                }
        }
    }

    Panel("Пользовательские подложки") {
        if (!isExpanded) {
            P {
                Text("Добавьте свои тайловые серверы для использования в предпросмотре. Встроенных: ${state.baseSources.count { it.isBuiltin }}, пользовательских: ${customSources.size}")
            }
            Button(attrs = {
                attr("type", "button")
                onClick { isExpanded = true }
            }) {
                Text("Управление подложками")
            }
        } else {
            Button(attrs = {
                attr("type", "button")
                attr("class", "copy-button")
                onClick { isExpanded = false }
            }) {
                Text("Скрыть")
            }

            H3 {
                Text("Добавить подложку")
            }

            Div(attrs = { attr("class", "stack") }) {
                Label {
                    Text("Название *")
                    Input(type = InputType.Text, attrs = {
                        value(name)
                        onInput { name = it.value ?: "" }
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("URL шаблон тайлов * (пример: https://example.com/tiles/{z}/{x}/{y}.png)")
                    Input(type = InputType.Text, attrs = {
                        value(urlTemplate)
                        onInput { urlTemplate = it.value ?: "" }
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Атрибуция (опционально)")
                    Input(type = InputType.Text, attrs = {
                        value(attribution)
                        onInput { attribution = it.value ?: "" }
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Максимальный zoom (1-22)")
                    Input(type = InputType.Number, attrs = {
                        value(maxZoom)
                        onInput { ev -> maxZoom = ev.value?.toString() ?: "19" }
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
            }

            error?.let {
                P(attrs = { attr("class", "error") }) {
                    Text(it)
                }
            }
            success?.let {
                P(attrs = { attr("class", "hint") }) {
                    Text(it)
                }
            }

            Div(attrs = { attr("class", "tile-server-quick-links") }) {
                Button(attrs = {
                    attr("type", "button")
                    if (isSubmitting) attr("disabled", "disabled")
                    onClick { submit() }
                }) {
                    Text(if (isSubmitting) "Добавление..." else "Добавить подложку")
                }
                Button(attrs = {
                    attr("type", "button")
                    attr("class", "copy-button")
                    if (isSubmitting) attr("disabled", "disabled")
                    onClick { resetForm() }
                }) {
                    Text("Очистить")
                }
            }

            if (customSources.isNotEmpty()) {
                H3 {
                    Text("Сохранённые подложки")
                }
                Div(attrs = { attr("class", "job-list") }) {
                    customSources.forEach { source ->
                        Div(attrs = { attr("class", "job-card") }) {
                            Span(attrs = { attr("class", "font-strong") }) {
                                Text(source.name)
                            }
                            Small {
                                Text("Zoom: ${source.maxZoom}")
                            }
                            Code {
                                Text(source.urlTemplate)
                            }
                            Div(attrs = { attr("class", "tile-server-quick-links") }) {
                                Button(attrs = {
                                    attr("type", "button")
                                    attr("class", "copy-button")
                                    onClick {
                                        state.selectedBaseSourceId = source.id
                                        success = "Подложка \"${source.name}\" выбрана для предпросмотра"
                                    }
                                }) {
                                    Text(if (state.selectedBaseSourceId == source.id) "Выбрана" else "Выбрать")
                                }
                                Button(attrs = {
                                    attr("type", "button")
                                    attr("class", "copy-button")
                                    onClick { deleteSource(source) }
                                }) {
                                    Text("Удалить")
                                }
                            }
                        }
                    }
                }
            } else {
                P(attrs = { attr("class", "hint") }) {
                    Text("Пользовательских подложек пока нет. Добавьте первую выше.")
                }
            }
        }
    }
}

@Composable
private fun SystemCachePanel(state: AppState) {
    val scope = rememberCoroutineScope()
    var clearCompletedJobs by remember { mutableStateOf(false) }
    var clearFailedJobs by remember { mutableStateOf(false) }
    var clearRunningJobs by remember { mutableStateOf(false) }
    var clearUploadedTilesets by remember { mutableStateOf(false) }
    var clearCustomSources by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tileCacheStats by remember { mutableStateOf<TileCacheStats?>(null) }
    var isLoadingTileCache by remember { mutableStateOf(false) }
    val stats = state.storageStats
    val hasSelection = clearCompletedJobs || clearFailedJobs || clearRunningJobs || clearUploadedTilesets || clearCustomSources

    // Загрузка статистики тайлового кэша
    fun loadTileCacheStats() {
        scope.launch {
            isLoadingTileCache = true
            tileCacheStats = try {
                getTileCacheStats().await()
            } catch (e: Throwable) {
                null
            }
            isLoadingTileCache = false
        }
    }

    // Загружаем статистику при монтировании
    LaunchedEffect(Unit) {
        loadTileCacheStats()
        // Обновляем каждые 10 секунд
        while (true) {
            delay(10_000)
            loadTileCacheStats()
        }
    }

    fun refresh() {
        scope.launch {
            runCatching { ApiClient.loadBootstrap() }
                .onSuccess {
                    state.applyBootstrap(it)
                    error = null
                }
                .onFailure { error = it.message ?: it.toString() }
            loadTileCacheStats()
        }
    }

    fun onClearTileCache() {
        if (!window.confirm("Очистить локальный кэш тайлов?")) return
        scope.launch {
            isLoadingTileCache = true
            val success = try {
                com.terrainconverter.web.ui.clearTileCache().await()
            } catch (e: Throwable) {
                false
            }
            tileCacheStats = null
            isLoadingTileCache = false
            message = if (success) "Кэш тайлов очищен." else "Не удалось очистить кэш."
        }
    }

    fun clearSelected() {
        if (!hasSelection || isClearing) return
        val confirmation = cacheClearConfirmation(
            stats = stats,
            completedJobs = clearCompletedJobs,
            failedJobs = clearFailedJobs,
            runningJobs = clearRunningJobs,
            uploadedTilesets = clearUploadedTilesets,
            customSources = clearCustomSources,
        )
        if (!window.confirm(confirmation)) return
        isClearing = true
        message = null
        error = null
        scope.launch {
            runCatching {
                ApiClient.clearCache(
                    CacheClearRequest(
                        completedJobs = clearCompletedJobs,
                        failedJobs = clearFailedJobs,
                        runningJobs = clearRunningJobs,
                        uploadedTilesets = clearUploadedTilesets,
                        customSources = clearCustomSources,
                    )
                )
            }.onSuccess { result ->
                state.storageStats = result.storage
                val bootstrap = ApiClient.loadBootstrap()
                state.applyBootstrap(bootstrap)
                clearCompletedJobs = false
                clearFailedJobs = false
                clearRunningJobs = false
                clearUploadedTilesets = false
                clearCustomSources = false
                message = "Очищено: завершённые ${result.deletedCompletedJobs}, ошибочные ${result.deletedFailedJobs}, выполняющиеся ${result.deletedRunningJobs}, MBTiles ${result.deletedUploadedTilesets}, пользовательские подложки ${result.deletedCustomSources}."
            }.onFailure {
                error = it.message ?: it.toString()
            }
            isClearing = false
        }
    }

    Panel("Система и кэш") {
        if (stats == null) {
            P { Text("Статистика хранилища загружается.") }
        } else {
            DetailRow("Всего занято", formatBytes(stats.totalBytes.toDouble()))
            DetailRow("Процессы", "${stats.completedJobs + stats.failedJobs + stats.runningJobs} • ${formatBytes(stats.jobsBytes.toDouble())}")
            DetailRow("Загруженные MBTiles", "${stats.uploadedTilesets} • ${formatBytes(stats.tilesetsBytes.toDouble())}")
            DetailRow("База настроек", formatBytes(stats.databaseBytes.toDouble()))
            DetailRow("Пользовательские подложки", stats.customSources.toString())
        }

        H3 { Text("Локальный кэш тайлов (браузер)") }
        if (isLoadingTileCache && tileCacheStats == null) {
            P { Text("Загрузка...") }
        } else {
            val cacheStats = tileCacheStats
            if (cacheStats != null && cacheStats.tileCount > 0) {
                DetailRow("Тайлов в кэше", cacheStats.tileCount.toString())
                DetailRow("Размер кэша", formatBytes(cacheStats.totalSize.toDouble()))
            } else {
                P(attrs = { attr("class", "hint") }) { Text("Кэш пуст. Тайлы будут сохраняться при просмотре карты.") }
            }
        }
        Div(attrs = { attr("class", "tile-server-quick-links") }) {
            Button(attrs = {
                attr("type", "button")
                attr("class", "copy-button")
                if (isLoadingTileCache) attr("disabled", "disabled")
                onClick { onClearTileCache() }
            }) {
                Text(if (isLoadingTileCache) "Очистка..." else "Очистить кэш тайлов")
            }
        }
        P(attrs = { attr("class", "hint") }) {
            Text("Тайлы кэшируются локально для быстрой загрузки при повторном просмотре. Очистите если нужно обновить данные.")
        }

        H3 { Text("Серверное хранилище") }
        Div(attrs = { attr("class", "stack") }) {
            CacheCheckbox("Завершённые процессы (${stats?.completedJobs ?: 0})", clearCompletedJobs, isClearing) { clearCompletedJobs = it }
            CacheCheckbox("Процессы завершенные с ошибкой (${stats?.failedJobs ?: 0})", clearFailedJobs, isClearing) { clearFailedJobs = it }
            CacheCheckbox("Выполняющиеся процессы (${stats?.runningJobs ?: 0})", clearRunningJobs, isClearing) { clearRunningJobs = it }
            CacheCheckbox("Загруженные тайлсеты MBTiles (${stats?.uploadedTilesets ?: 0})", clearUploadedTilesets, isClearing) { clearUploadedTilesets = it }
            CacheCheckbox("Пользовательские подложки (${stats?.customSources ?: 0})", clearCustomSources, isClearing) { clearCustomSources = it }
        }

        P(attrs = { attr("class", "hint") }) {
            Text("Встроенные подложки не удаляются. Очистка выполняющихся процессов останавливает их и удаляет файлы процессов.")
        }
        Div(attrs = { attr("class", "tile-server-quick-links") }) {
            Button(attrs = {
                attr("type", "button")
                if (!hasSelection || isClearing) attr("disabled", "disabled")
                onClick { clearSelected() }
            }) {
                Text(if (isClearing) "Очистка..." else "Очистить выбранное")
            }
            Button(attrs = {
                attr("type", "button")
                attr("class", "copy-button")
                if (isClearing) attr("disabled", "disabled")
                onClick { refresh() }
            }) {
                Text("Обновить")
            }
        }
        message?.let { P(attrs = { attr("class", "hint") }) { Text(it) } }
        error?.let { P(attrs = { attr("class", "error") }) { Text(it) } }
    }
}

internal fun cacheClearConfirmation(
    stats: StorageStats?,
    completedJobs: Boolean,
    failedJobs: Boolean,
    runningJobs: Boolean,
    uploadedTilesets: Boolean,
    customSources: Boolean,
): String {
    val selected = buildList {
        if (completedJobs) add("завершённые процессы: ${stats?.completedJobs ?: 0}")
        if (failedJobs) add("ошибочные процессы: ${stats?.failedJobs ?: 0}")
        if (runningJobs) add("выполняющиеся процессы: ${stats?.runningJobs ?: 0}")
        if (uploadedTilesets) add("загруженные MBTiles: ${stats?.uploadedTilesets ?: 0}")
        if (customSources) add("пользовательские подложки: ${stats?.customSources ?: 0}")
    }
    val runningWarning = if (runningJobs) {
        "\n\nВнимание: выполняющиеся процессы будут остановлены, а их файлы удалены."
    } else {
        ""
    }
    return "Очистить выбранные данные?\n\n${selected.joinToString("\n")}$runningWarning"
}

@Composable
private fun CacheCheckbox(label: String, checked: Boolean, disabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Label {
        Input(type = InputType.Checkbox, attrs = {
            if (checked) attr("checked", "checked")
            if (disabled) attr("disabled", "disabled")
            onChange { onCheckedChange(!checked) }
        })
        Text(" $label")
    }
}

@Composable
private fun MbtilesCatalogPanel(state: AppState) {
    val activeAddress = state.activeMobileAddress

    Panel("Тайловый сервер MBTiles") {
        MbtilesUploadForm(state)

        if (isLocalBrowserHost()) {
StatusCard(
                "Предупреждение для удалённого доступа",
                "Страница открыта через ${window.location.host}. Для внешнего доступа не может использовать localhost этого компьютера. Откройте интерфейс через IP компьютера в локальной сети, например http://<ip-kompyutera>${portSuffixForHint()}, чтобы сервер создал рабочие ссылки для удаленного доступа.",
            )
        }

        activeAddress?.let { address ->
            Div(attrs = { attr("class", "panel") }) {
                Span(attrs = { attr("class", "font-strong") }) {
                    Text("Адрес для удалённого доступа: ${address.host}")
                }
                P(attrs = { attr("class", "hint") }) {
                    Text("Ссылки ниже используют этот адрес и подходят для удаленных устройств в той же сети.")
                }
            }
} ?: P(attrs = { attr("class", "error") }) {
            Text("Не удалось определить адрес для удалённого доступа. Проверьте, что устройство и компьютер подключены к одной сети.")
        }

        H3 {
            Text("Загруженные наборы тайлов")
        }

        if (state.tilesets.isEmpty()) {
            P {
                Text("Серверы MBTiles еще не запущены.")
            }
        } else {
            Div(attrs = { attr("class", "job-list") }) {
                state.tilesets.forEach { tileset ->
                    Div(attrs = {
                        attr("class", "job-card" + if (state.selectedTilesetId == tileset.id) " selected" else "")
                        attr("role", "button")
                        attr("tabindex", "0")
                        onClick { state.selectedTilesetId = tileset.id }
                    }) {
                        Span(attrs = { attr("class", "font-strong") }) {
                            Text(tileset.name ?: tileset.filename)
                        }
                        Span {
                            Text(tileset.sourceType.label())
                        }
                        Small {
                            Text(formatTimestamp(tileset.createdAt))
                        }
                        val tileUrl = activeAddress?.let { "${it.baseUrl}${tileset.tileUrlTemplate}" }
                            ?: tileset.publicTileUrlTemplate
                            ?: tileset.tileUrlTemplate
                        Code {
                            Text(ApiClient.absoluteUrl(tileUrl))
                        }
                        Div(attrs = { attr("class", "tile-server-quick-links") }) {
                            if (tileset.sourceType == SourceType.RASTER) {
                                Button(attrs = {
                                    attr("type", "button")
                                    attr("class", "copy-button")
                                    onClick { state.openTilesetIn2D(tileset) }
                                }) {
                                    Text("Открыть в 2D")
                                }
                            }
                            if (tileset.sourceType == SourceType.RASTER_DEM) {
                                Span(attrs = { attr("class", "badge") }) {
                                    Text("3D доступен")
                                }
                                Button(attrs = {
                                    attr("type", "button")
                                    attr("class", "copy-button")
                                    onClick { state.openTilesetIn3D(tileset) }
                                }) {
                                    Text("Открыть в 3D")
                                }
                            }
                            // Only show copy buttons when we have an active LAN/mobile address
                            activeAddress?.let { address ->
                                val baseUrl = address.baseUrl
                                CopyButton("${baseUrl}${tileset.tileUrlTemplate}", label = "Копировать тайлы")
                                tileset.mobileStyleUrl?.let {
                                    CopyButton("${baseUrl}${appendBaseQuery(it, state.selectedBaseSourceId)}", label = "Копировать стиль для удалееного устройства")
                                }
                                tileset.styleUrl?.let {
                                    CopyButton("${baseUrl}${appendBaseQuery(it, state.selectedBaseSourceId)}", label = "Копировать стиль")
                                }
                                tileset.tilejsonUrl?.let {
                                    CopyButton("${baseUrl}$it", label = "Копировать TileJSON")
                                }
                            }
                        }
                    }
                }
            }
        }

        state.selectedTileset?.let { tileset ->
            H3 {
                Text("Выбранный набор тайлов")
            }
            DetailRow("Файл", tileset.filename)
            DetailRow("Тип", tileset.sourceType.label())
            DetailRow("Zoom", "${tileset.minzoom?.toString() ?: "-"} - ${tileset.maxzoom?.toString() ?: "-"}")
            tileset.attribution?.let {
                DetailRow("Атрибуция", it)
            }
            state.activeMobileAddress?.let { address ->
                Div(attrs = { attr("class", "server-addresses") }) {
                    Div(attrs = { attr("class", "server-address") }) {
                        Span(attrs = { attr("class", "font-strong") }) {
                            Text("Телефон / Wi-Fi")
                        }
                        P(attrs = { attr("class", "server-address-description") }) {
Text("Ссылка использует адрес для удаленного устройства: ${address.host}")
                        }
                        Div(attrs = { attr("class", "link-list") }) {
                            val baseUrl = address.baseUrl
                            TemplateRow("Тайлы", "$baseUrl${tileset.tileUrlTemplate}")
                            tileset.tilejsonUrl?.let {
                                LinkRow("TileJSON", "$baseUrl$it")
                            }
                            tileset.styleUrl?.let {
                                LinkRow("Стиль", "$baseUrl${appendBaseQuery(it, state.selectedBaseSourceId)}")
                            }
                            tileset.mobileStyleUrl?.let {
                                LinkRow("Стиль для удаленного устройства", "$baseUrl${appendBaseQuery(it, state.selectedBaseSourceId)}")
                            }
                        }
                    }
                }
            }

            state.serverAddresses.find { it.id == "localhost" }?.let { localhost ->
                Div(attrs = { attr("class", "server-addresses") }) {
                    Div(attrs = { attr("class", "server-address") }) {
                        Span(attrs = { attr("class", "font-strong") }) {
                            Text(localhost.labelRu())
                        }
                        P(attrs = { attr("class", "server-address-description") }) {
                            Text(localhost.descriptionRu())
                        }
                        Div(attrs = { attr("class", "link-list") }) {
                            TemplateRow("Тайлы", addressScopedUrl(localhost, tileset.tileUrlTemplate))
                            tileset.tilejsonUrl?.let {
                                LinkRow("TileJSON", addressScopedUrl(localhost, it))
                            }
                            tileset.styleUrl?.let {
                                LinkRow("Стиль", addressScopedUrl(localhost, appendBaseQuery(it, state.selectedBaseSourceId)))
                            }
                            tileset.mobileStyleUrl?.let {
                                LinkRow("Стиль для удаленного устройства", addressScopedUrl(localhost, appendBaseQuery(it, state.selectedBaseSourceId)))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isLocalBrowserHost(): Boolean {
    val host = window.location.hostname.lowercase()
    return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0"
}

private fun portSuffixForHint(): String = window.location.port.takeIf { it.isNotBlank() }?.let { ":$it" } ?: ""

@Composable
private fun MbtilesUploadForm(state: AppState) {
    val scope = rememberCoroutineScope()
    var sourceType by remember { mutableStateOf("auto") }
    var isSubmitting by remember { mutableStateOf(false) }
    var uploadState by remember { mutableStateOf<MbtilesUploadUiState?>(null) }
    var activeUpload by remember { mutableStateOf<MbtilesUploadRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun cancelActiveUpload() {
        val request = activeUpload ?: return
        activeUpload = null
        request.cancel()
        val message = "Загрузка отменена."
        error = message
        uploadState = uploadState?.copy(
            stage = MbtilesUploadStage.CANCELLED,
            error = message,
        )
    }
    fun submit() {
        if (isSubmitting) return
        error = null

        val file = inputFiles("mbtiles-upload-input").firstOrNull()
        if (file == null) {
            error = "Сначала выберите файл MBTiles."
            return
        }

        val startedAt = Date.now()
        val uploadId = "upload-${startedAt.toLong()}-${(js("Math.random()") as Double).toString().substringAfter('.')}"
        val formData = FormData()
        formData.append("mbtiles", file, file.name)
        formData.append("source_type", sourceType)
        formData.append("upload_id", uploadId)

        isSubmitting = true
        uploadState = MbtilesUploadUiState(
            uploadId = uploadId,
            filename = file.name,
            stage = MbtilesUploadStage.UPLOADING,
            percent = 0,
            loadedBytes = 0.0,
            totalBytes = file.asDynamic().size as? Double,
            startedAt = startedAt,
        )
        scope.launch {
            var polling: CoroutineJob? = null
            val request = ApiClient.startMbtilesTilesetUpload(formData, uploadId) { progress ->
                uploadState = uploadState?.copy(
                    stage = MbtilesUploadStage.UPLOADING,
                    percent = progress.percent,
                    loadedBytes = progress.loaded,
                    totalBytes = progress.total,
                )
            }
            activeUpload = request
            polling = launch {
                while (true) {
                    delay(350)
                    val progress = runCatching { ApiClient.getMbtilesUploadProgress(uploadId) }.getOrNull() ?: continue
                    uploadState = uploadState?.copy(
                        stage = progress.stage,
                        filename = progress.filename ?: uploadState?.filename ?: file.name,
                        error = progress.error,
                    )
                    if (progress.stage in setOf(MbtilesUploadStage.READY, MbtilesUploadStage.ERROR, MbtilesUploadStage.CANCELLED)) {
                        break
                    }
                }
            }
            runCatching { request.promise.await() }
                .onSuccess { tileset ->
                    state.mergeTileset(tileset)
                    state.selectedTilesetId = tileset.id
                    if (tileset.sourceType == SourceType.RASTER_DEM) {
                        state.openTilesetIn3D(tileset)
                    }
                    error = null
                    uploadState = uploadState?.copy(
                        stage = MbtilesUploadStage.READY,
                        percent = 100,
                        error = null,
                    )
                    delay(900)
                    uploadState = null
                }
                .onFailure {
                    val message = localizeUploadError(it.message)
                    error = message
                    uploadState = uploadState?.copy(
                        stage = if (message.contains("отмен", ignoreCase = true)) MbtilesUploadStage.CANCELLED else MbtilesUploadStage.ERROR,
                        error = message,
                    )
                }
            polling.cancel()
            activeUpload = null
            isSubmitting = false
        }
    }

    val currentUpload by rememberUpdatedState(activeUpload)
    DisposableEffect(Unit) {
        onDispose {
            currentUpload?.cancel()
            activeUpload = null
        }
    }

    Form(attrs = {
        attr("class", "stack")
        onSubmit {
            it.preventDefault()
            submit()
        }
    }) {
        Label {
            Text("Файл MBTiles")
            Input(type = InputType.File, attrs = {
                id("mbtiles-upload-input")
                attr("accept", ".mbtiles")
                if (isSubmitting) attr("disabled", "disabled")
            })
        }
        Label {
            Text("Тип MBTiles")
            Select(attrs = {
                if (isSubmitting) attr("disabled", "disabled")
                onChange { sourceType = it.value ?: "auto" }
            }) {
                Option(value = "auto", attrs = { if (sourceType == "auto") attr("selected", "selected") }) { Text("Определить автоматически") }
                Option(value = "raster", attrs = { if (sourceType == "raster") attr("selected", "selected") }) { Text("Растровая карта") }
                Option(value = "raster-dem", attrs = { if (sourceType == "raster-dem") attr("selected", "selected") }) { Text("Рельеф DEM") }
            }
        }
        error?.let {
            P(attrs = { attr("class", "error") }) {
                Text(it)
            }
        }
        uploadState?.let { progress ->
            Div(attrs = {
                attr("class", "upload-progress")
                attr("role", "progressbar")
                attr("aria-valuemin", "0")
                attr("aria-valuemax", "100")
                attr("aria-valuenow", progress.percent.toString())
            }) {
                Div(attrs = {
                    attr("class", "upload-progress-fill")
                    attr("style", "width: ${progress.percent.coerceIn(0, 100)}%;")
                })
            }
            P(attrs = { attr("class", "hint") }) {
                Text("Файл: ${progress.filename}")
            }
            P(attrs = { attr("class", "hint") }) {
                Text("${progress.percent}% • ${formatBytes(progress.loadedBytes)} / ${formatBytes(progress.totalBytes)} • ${formatBytes(progress.bytesPerSecond)}/с")
            }
            P(attrs = { attr("class", if (progress.stage == MbtilesUploadStage.ERROR || progress.stage == MbtilesUploadStage.CANCELLED) "error" else "hint") }) {
                Text("Текущий статус: ${progress.stage.label()}${progress.error?.let { ": ${localizeUploadError(it)}" } ?: ""}")
            }
            if (progress.stage == MbtilesUploadStage.UPLOADING && isSubmitting) {
                Button(attrs = {
                    attr("type", "button")
                    attr("class", "copy-button")
                    onClick { cancelActiveUpload() }
                }) {
                    Text("Отменить загрузку")
                }
            } else if (isSubmitting && progress.stage != MbtilesUploadStage.ERROR && progress.stage != MbtilesUploadStage.CANCELLED) {
                P(attrs = { attr("class", "hint") }) {
                    Text("Передача завершена. Отмена подготовки на сервере пока недоступна.")
                }
            }
            if (progress.stage == MbtilesUploadStage.ERROR) {
                P(attrs = { attr("class", "hint") }) {
                    Text("Проверьте, что выбран корректный файл .mbtiles, и попробуйте снова.")
                }
            }
        }
        Button(attrs = {
            attr("type", "submit")
            if (isSubmitting) attr("disabled", "disabled")
        }) {
            Text(if (isSubmitting) "Загрузка..." else "Запустить тайловый сервер")
        }
    }
}

@Composable
private fun ConvertFormPanel(state: AppState) {
    val scope = rememberCoroutineScope()
    var bboxMode by remember { mutableStateOf("auto") }
    var west by remember { mutableStateOf("") }
    var south by remember { mutableStateOf("") }
    var east by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("") }
    var scheme by remember { mutableStateOf("xyz") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (isSubmitting) return
        error = null

        val hgtFiles = inputFiles("job-hgt-files-input")
        if (hgtFiles.isEmpty()) {
            error = "Загрузите хотя бы один файл HGT или ZIP-архив."
            return
        }

        val formData = FormData()
        hgtFiles.forEach { file ->
            formData.append("hgt_files", file, file.name)
        }
        inputFiles("job-base-mbtiles-input").firstOrNull()?.let { file ->
            formData.append("base_mbtiles", file, file.name)
        }
        formData.append("bbox_mode", bboxMode)
        formData.append("minzoom", inputValue("job-minzoom-input"))
        formData.append("maxzoom", inputValue("job-maxzoom-input"))
        formData.append("tile_size", inputValue("job-tile-size-input"))
        formData.append("scheme", scheme)
        formData.append("encoding", "mapbox")
        if (bboxMode == "manual") {
            formData.append("west", west)
            formData.append("south", south)
            formData.append("east", east)
            formData.append("north", north)
        }

        isSubmitting = true
        scope.launch {
            runCatching { ApiClient.createJob(formData) }
                .onSuccess { job ->
                    state.mergeJob(job)
                    state.selectedJobId = job.id
                    error = null
                }
                .onFailure {
                    error = it.message ?: "Не удалось создать задачу."
                }
            isSubmitting = false
        }
    }

    Panel("Конвертация рельефа") {
        Form(attrs = {
            attr("class", "stack")
            onSubmit {
                it.preventDefault()
                submit()
            }
        }) {
            Div(attrs = { attr("class", "upload-panel stack") }) {
                H3 {
                    Text("Файлы")
                }
                Label {
                    Text("Файлы HGT или ZIP-архив")
                    Input(type = InputType.File, attrs = {
                        id("job-hgt-files-input")
                        attr("multiple", "multiple")
                        attr("accept", ".hgt,.zip")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Дополнительный base.mbtiles")
                    Input(type = InputType.File, attrs = {
                        id("job-base-mbtiles-input")
                        attr("accept", ".mbtiles")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
            }

            Div(attrs = { attr("class", "form-grid form-grid-2") }) {
                Label {
                    Text("Границы")
                    Select(attrs = {
                        if (isSubmitting) attr("disabled", "disabled")
                        onChange { bboxMode = it.value ?: "auto" }
                    }) {
                        Option(value = "auto", attrs = { if (bboxMode == "auto") attr("selected", "selected") }) { Text("Автоматически") }
                        Option(value = "manual", attrs = { if (bboxMode == "manual") attr("selected", "selected") }) { Text("Вручную") }
                    }
                }
                Label {
                    Text("Схема тайлов")
                    Select(attrs = {
                        if (isSubmitting) attr("disabled", "disabled")
                        onChange { scheme = it.value ?: "xyz" }
                    }) {
                        Option(value = "xyz", attrs = { if (scheme == "xyz") attr("selected", "selected") }) { Text("xyz") }
                        Option(value = "tms", attrs = { if (scheme == "tms") attr("selected", "selected") }) { Text("tms") }
                    }
                }
            }

            if (bboxMode == "manual") {
                Div(attrs = { attr("class", "form-grid form-grid-4") }) {
                    Label {
                        Text("Запад")
                        Input(type = InputType.Text, attrs = {
                            id("job-west-input")
                            value(west)
                            onInput { west = it.value ?: "" }
                            if (isSubmitting) attr("disabled", "disabled")
                        })
                    }
                    Label {
                        Text("Юг")
                        Input(type = InputType.Text, attrs = {
                            id("job-south-input")
                            value(south)
                            onInput { south = it.value ?: "" }
                            if (isSubmitting) attr("disabled", "disabled")
                        })
                    }
                    Label {
                        Text("Восток")
                        Input(type = InputType.Text, attrs = {
                            id("job-east-input")
                            value(east)
                            onInput { east = it.value ?: "" }
                            if (isSubmitting) attr("disabled", "disabled")
                        })
                    }
                    Label {
                        Text("Север")
                        Input(type = InputType.Text, attrs = {
                            id("job-north-input")
                            value(north)
                            onInput { north = it.value ?: "" }
                            if (isSubmitting) attr("disabled", "disabled")
                        })
                    }
                }
            }

            Div(attrs = { attr("class", "form-grid form-grid-4") }) {
                Label {
                    Text("Мин. масштаб")
                    Input(type = InputType.Number, attrs = {
                        id("job-minzoom-input")
                        value("8")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Макс. масштаб")
                    Input(type = InputType.Number, attrs = {
                        id("job-maxzoom-input")
                        value("12")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Размер тайла")
                    Input(type = InputType.Number, attrs = {
                        id("job-tile-size-input")
                        value("256")
                        if (isSubmitting) attr("disabled", "disabled")
                    })
                }
                Label {
                    Text("Кодирование")
                    Input(type = InputType.Text, attrs = {
                        value("mapbox")
                        attr("disabled", "disabled")
                    })
                }
            }

            error?.let {
                P(attrs = { attr("class", "error") }) {
                    Text(it)
                }
            }

            Button(attrs = {
                attr("type", "submit")
                if (isSubmitting) attr("disabled", "disabled")
            }) {
                Text(if (isSubmitting) "Запуск..." else "Начать конвертацию")
            }
        }
    }
}

@Composable
private fun JobSelectionPanel(state: AppState) {
    Panel("Очередь задач") {
        if (state.jobs.isEmpty()) {
            P {
                Text("Задач пока нет.")
            }
        } else {
            Div(attrs = { attr("class", "job-list") }) {
                state.jobs.forEach { job ->
                    Button(attrs = {
                        attr("class", "job-card" + if (state.selectedJobId == job.id) " selected" else "")
                        onClick { state.selectJob(job) }
                    }) {
                        Span(attrs = { attr("class", "font-strong") }) {
                            Text(job.id.take(8))
                        }
                        Span {
                            Text(job.status.label())
                        }
                        Small {
                            Text(formatTimestamp(job.createdAt))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPreviewPanel(state: AppState) {
    val sourceJob = (state.terrainPreviewSource as? TerrainPreviewSource.Job)
        ?.let { source -> state.jobs.firstOrNull { it.id == source.jobId && it.status == JobStatus.COMPLETED } }
    val sourceDemTileset = (state.terrainPreviewSource as? TerrainPreviewSource.MbtilesDem)
        ?.let { source -> state.tilesets.firstOrNull { it.id == source.tilesetId && it.sourceType == SourceType.RASTER_DEM } }
    val fallbackCompletedJob = if (state.terrainPreviewSource == null) state.selectedJob?.takeIf { it.status == JobStatus.COMPLETED } else null
    val completedJob = sourceJob ?: fallbackCompletedJob
    val demTileset = if (completedJob == null) sourceDemTileset else null
    val hasTerrainSource = completedJob != null || demTileset != null
    val mode = if (state.previewMode == PreviewMode.THREE_D && hasTerrainSource) PreviewMode.THREE_D else PreviewMode.TWO_D
    val rasterTileset = state.twoDPreviewTileset?.takeIf { it.sourceType == SourceType.RASTER }
    val selectedBaseSource = state.selectedBaseSource
    val mapKey = listOf(
        mode.name,
        selectedBaseSource.id,
        completedJob?.id.orEmpty(),
        demTileset?.id.orEmpty(),
        rasterTileset?.id.orEmpty(),
    ).joinToString(":")

    var pitch by remember(mapKey) { mutableStateOf(55) }
    var zoom by remember(mapKey) { mutableStateOf<Double?>(null) }
    var previewError by remember(mapKey) { mutableStateOf<String?>(null) }
    var mapInstance by remember(mapKey) { mutableStateOf<MapLibreMap?>(null) }
    val mapId = "map-preview"

    Panel("Предпросмотр карты") {
        Div(attrs = { attr("class", "preview-mode-grid") }) {
            Button(attrs = {
                attr("type", "button")
                attr("class", if (mode == PreviewMode.TWO_D) "active-option" else "")
                onClick {
                    state.previewMode = PreviewMode.TWO_D
                    state.twoDPreviewTilesetId = null
                    state.previewNotice = null
                }
            }) {
                Text("2D карта")
            }
            Button(attrs = {
                attr("type", "button")
                attr("class", if (mode == PreviewMode.THREE_D) "active-option" else "")
                if (!hasTerrainSource) attr("disabled", "disabled")
                attr("title", disabled3DMessage)
                onClick {
                    if (hasTerrainSource) {
                        state.previewMode = PreviewMode.THREE_D
                        state.previewNotice = null
                    }
                }
            }) {
                Text(if (hasTerrainSource) "3D рельеф" else "3D рельеф недоступен")
            }
        }

        Div(attrs = { attr("class", "preview-base-row") }) {
            Span(attrs = { attr("class", "detail-label") }) {
                Text("Подложка")
            }
            state.availablePreviewBaseSources().forEach { previewBase ->
                Button(attrs = {
                    attr("type", "button")
                    attr("class", if (selectedBaseSource.id == previewBase.id) "active-option compact-option" else "compact-option")
                    onClick { state.selectedBaseSourceId = previewBase.id }
                }) {
                    Text(previewBase.name)
                }
            }
        }

        if (!hasTerrainSource) {
            P(attrs = { attr("class", "hint") }) {
                Text(disabled3DMessage)
            }
        }
        if (mode == PreviewMode.TWO_D && rasterTileset == null) {
            P(attrs = { attr("class", "hint") }) {
                Text("2D OpenStreetMap доступна сразу. Чтобы включить 3D, загрузите HGT/ZIP в конвертацию или MBTiles типа «Рельеф DEM».")
            }
        }
        state.previewNotice?.let {
            P(attrs = { attr("class", "hint") }) {
                Text(it)
            }
        }

        if (mode == PreviewMode.THREE_D) {
            Div(attrs = { attr("class", "preview-toolbar") }) {
                Label {
                    Text("Наклон камеры: $pitch°")
                    Input(type = InputType.Range, attrs = {
                        value(pitch.toString())
                        attr("min", "0")
                        attr("max", "85")
                        onInput {
                            val value = it.value?.toString()?.toIntOrNull() ?: return@onInput
                            pitch = value
                            mapInstance?.setPitch(value.toDouble())
                        }
                    })
                }
                Div(attrs = { attr("class", "preview-stat") }) {
                    Text("Zoom: ${zoom?.let { value -> jsNumber(value) } ?: "-"}")
                }
            }
        } else {
            Div(attrs = { attr("class", "preview-stat") }) {
                Text("Zoom: ${zoom?.let { value -> jsNumber(value) } ?: "-"}")
            }
        }

        Div(attrs = {
            id(mapId)
            attr("class", "map")
        })
        previewError?.let {
            P(attrs = { attr("class", "error") }) {
                Text(it)
            }
        }
    }

    DisposableEffect(mapKey) {
        mapInstance?.remove()
        mapInstance = null
        previewError = null

        val container = document.getElementById(mapId)?.unsafeCast<HTMLDivElement>()
        if (container != null) {
            runCatching {
                // Performance parameters for map creation
                val maxTileCacheSize = 128 // Limit memory usage
                val cacheSize = 128

                val map = when (mode) {
                    PreviewMode.TWO_D -> createMapLibreMap(
                        container = container,
                        style = rasterTileset?.let { buildMbtilesPreviewStyle(it, null) } ?: buildBasePreviewStyle(selectedBaseSource),
                        center = rasterTileset?.view?.let { arrayOf(it.centerLon, it.centerLat) } ?: arrayOf(0.0, 0.0),
                        zoom = rasterTileset?.view?.zoom?.toDouble() ?: 2.0,
                        pitch = 0.0,
                        maxTileCacheSize = maxTileCacheSize,
                        cacheSize = cacheSize,
                    )

                    PreviewMode.THREE_D -> {
                        val terrainJob = completedJob
                        val terrainTileset = if (terrainJob == null) demTileset else null
                        createMapLibreMap(
                            container = container,
                            style = terrainJob?.let { buildTerrainPreviewStyle(it, selectedBaseSource) }
                                ?: buildMbtilesPreviewStyle(terrainTileset ?: error("Нет источника рельефа"), selectedBaseSource),
                            center = terrainJob?.result?.bounds?.let { arrayOf((it.west + it.east) / 2.0, (it.south + it.north) / 2.0) }
                                ?: terrainTileset?.view?.let { arrayOf(it.centerLon, it.centerLat) }
                                ?: arrayOf(0.0, 0.0),
                            zoom = terrainJob?.options?.minzoom?.let { maxOf(it, 8).toDouble() }
                                ?: terrainTileset?.view?.zoom?.toDouble()
                                ?: 1.0,
                            pitch = pitch.toDouble(),
                            maxTileCacheSize = maxTileCacheSize,
                            cacheSize = cacheSize,
                        )
                    }
                }
                zoom = map.getZoom()
                map.on("zoom") {
                    zoom = map.getZoom()
                }
                map.on("pitch") {
                    pitch = map.getPitch().toInt()
                }
                map.on("error") { event ->
                    previewError = mapLibreErrorMessage(event)
                }
                val bounds = when (mode) {
                    PreviewMode.TWO_D -> rasterTileset?.bounds
                    PreviewMode.THREE_D -> completedJob?.result?.bounds ?: demTileset?.bounds
                }
                bounds?.let {
                    map.fitBounds(
                        bounds = arrayOf(
                            arrayOf(it.west, it.south),
                            arrayOf(it.east, it.north),
                        ),
                        options = js("({ padding: 24, duration: 0 })"),
                    )
                    if (mode == PreviewMode.THREE_D) {
                        map.setPitch(pitch.toDouble())
                    }
                    zoom = map.getZoom()
                }
                map.addControl(createNavigationControl(), "top-right")
                window.setTimeout({ map.resize() }, 0)
                window.setTimeout({ map.resize() }, 250)
                mapInstance = map
            }.onFailure {
                previewError = "Не удалось запустить предпросмотр: ${it.message ?: it.toString()}"
            }
        }

        onDispose {
            mapInstance?.remove()
            mapInstance = null
        }
    }

    SideEffect {
        if (mode == PreviewMode.THREE_D) {
            mapInstance?.setPitch(pitch.toDouble())
        }
    }
}

@Composable
private fun JobStatusPanel(state: AppState) {
    Panel("Статус задачи") {
        val job = state.selectedJob
        if (job == null) {
            P {
                Text("Выберите задачу, чтобы увидеть статус, артефакты и ссылки.")
            }
            return@Panel
        }

        Div(attrs = { attr("class", "status-row") }) {
            Span(attrs = { attr("class", "font-strong") }) {
                Text(job.status.label())
            }
            job.error?.let {
                Span(attrs = { attr("class", "error") }) {
                    Text(it)
                }
            }
        }
        job.result.tileCount?.let {
            DetailRow("Тайлы", it.toString())
        }
        DetailRow("Создано", formatTimestamp(job.createdAt))
        DetailRow("Обновлено", formatTimestamp(job.updatedAt))
        DetailRow("Подложка MBTiles", if (job.hasBaseMbtiles) "есть" else "нет")
        job.artifacts.tilejson?.let {
            LinkRow("TileJSON", it)
        }
        job.artifacts.stylejson?.let {
            LinkRow("Стиль", appendBaseQuery(ApiClient.styleUrl(job.id), state.selectedBaseSourceId))
        }
        job.artifacts.terrainTileUrlTemplate?.let {
            TemplateRow("Тайлы рельефа", it)
        }
        job.artifacts.publicTerrainTileUrlTemplate?.let {
            TemplateRow("Публичные тайлы рельефа", it)
        }
        job.artifacts.publicTilejson?.let {
            LinkRow("Публичный TileJSON", it)
        }
        job.artifacts.publicStylejson?.let {
            LinkRow("Публичный стиль", appendBaseQuery(it, state.selectedBaseSourceId))
        }
        state.activeMobileAddress?.let { address ->
            Div(attrs = { attr("class", "server-addresses") }) {
                Div(attrs = { attr("class", "server-address") }) {
                    Span(attrs = { attr("class", "font-strong") }) {
                        Text("Ссылки для удаленного устройства")
                    }
P(attrs = { attr("class", "server-address-description") }) {
                        Text("Ссылка использует адрес для удаленного устройства: ${address.host}")
                    }
                    Div(attrs = { attr("class", "link-list") }) {
                        val baseUrl = address.baseUrl
                        job.artifacts.terrainTileUrlTemplate?.let {
                            TemplateRow("Тайлы рельефа", "$baseUrl$it")
                        }
                        job.artifacts.tilejson?.let {
                            LinkRow("TileJSON", "$baseUrl$it")
                        }
                        LinkRow("Стиль", "$baseUrl${appendBaseQuery(ApiClient.styleUrl(job.id), state.selectedBaseSourceId)}")
                        LinkRow("Стиль для удаленного устройства", "$baseUrl${appendBaseQuery(ApiClient.mobileStyleUrl(job.id), state.selectedBaseSourceId)}")
                    }
                }
}
        } ?: P(attrs = { attr("class", "error") }) {
            Text("Не удалось определить адрес для удаленного устройства. Проверьте, что мобильное устройство и компьютер подключены к одной сети.")
        }
    }
}

@Composable
private fun DownloadPanel(job: Job?) {
    if (job == null || job.status != JobStatus.COMPLETED) {
        return
    }

    Panel("Загрузки") {
        Div(attrs = { attr("class", "download-links") }) {
            job.artifacts.terrainMbtiles?.let {
                ArtifactLink("terrain-rgb.mbtiles", it)
            }
            job.artifacts.tilejson?.let {
                ArtifactLink("tiles.json", it)
            }
            job.artifacts.stylejson?.let {
                ArtifactLink("style.json", it)
            }
        }
    }
}

@Composable
private fun LogsPanel(state: AppState) {
    Panel("Логи конвертации") {
        Pre(attrs = { attr("class", "logs") }) {
            Text(if (state.logs.isEmpty()) "" else state.logs.joinToString("\n"))
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Div(attrs = { attr("class", "panel stack") }) {
        H3 {
            Text(title)
        }
        content()
    }
}

@Composable
private fun StatusCard(title: String, message: String) {
    Div(attrs = { attr("class", "panel warning-panel") }) {
        H2 {
            Text(title)
        }
        P {
            Text(message)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Div(attrs = { attr("class", "detail-row") }) {
        Span(attrs = { attr("class", "detail-label") }) {
            Text(label)
        }
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("/")) {
            Code {
                Text(value)
            }
        } else {
            Text(value)
        }
    }
}

@Composable
private fun LinkRow(label: String, href: String) {
    val absoluteHref = ApiClient.absoluteUrl(href)

    Div(attrs = { attr("class", "detail-row") }) {
        Span(attrs = { attr("class", "detail-label") }) {
            Text(label)
        }
        Div(attrs = { attr("class", "template-row") }) {
            A(href = absoluteHref, attrs = {
                attr("target", "_blank")
                attr("rel", "noreferrer")
            }) {
                Text(absoluteHref)
            }
            CopyButton(absoluteHref)
        }
    }
}

@Composable
private fun TemplateRow(label: String, value: String) {
    val absoluteValue = ApiClient.absoluteUrl(value)
    var copyStatus by remember { mutableStateOf<String?>(null) }

    Div(attrs = { attr("class", "detail-row") }) {
        Span(attrs = { attr("class", "detail-label") }) {
            Text(label)
        }
        Div(attrs = { attr("class", "template-row") }) {
            Code {
                Text(absoluteValue)
            }
            CopyButton(absoluteValue) { copyStatus = it }
        }
        copyStatus?.let {
            Small(attrs = { attr("class", if (it == copySuccessText) "muted" else "error") }) {
                Text(it)
            }
        }
    }
}

@Composable
private fun ArtifactLink(label: String, href: String) {
    val absoluteHref = ApiClient.absoluteUrl(href)

    Div(attrs = { attr("class", "artifact-link") }) {
        A(href = absoluteHref, attrs = {
            attr("target", "_blank")
            attr("rel", "noreferrer")
        }) {
            Text(label)
        }
        CopyButton(absoluteHref)
    }
}

@Composable
private fun CopyButton(value: String, label: String = copyIdleText, onStatusChange: ((String?) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var ownStatus by remember { mutableStateOf<String?>(null) }
    val status: (String?) -> Unit = onStatusChange ?: { value -> ownStatus = value }

    Button(attrs = {
        attr("class", "copy-button")
        attr("type", "button")
        onClick {
            scope.launch {
                val copied = copyTextToClipboard(value)
                status(if (copied) copySuccessText else copyErrorText)
                window.setTimeout({ status(null) }, 2000)
            }
        }
    }) {
        Text(ownStatus ?: label)
    }
}

private fun formatTimestamp(value: String): String = Date(value).toLocaleString()

private const val copyIdleText = "Копировать"
private const val copySuccessText = "Скопировано"
private const val copyErrorText = "Не удалось скопировать"

private const val disabled3DMessage = "3D режим недоступен. Нужен источник рельефа: завершённая конвертация HGT или MBTiles типа «Рельеф DEM»."

internal fun appendBaseQuery(url: String, baseSourceId: String): String {
    val separator = if (url.contains("?")) "&" else "?"
    return "$url${separator}base=${encodeURIComponent(baseSourceId)}"
}

private fun AppState.availablePreviewBaseSources(): List<BaseMapSource> {
    val sources = baseSources.ifEmpty { listOf(fallbackOpenStreetMapSource, noneBaseMapSource) }
    return sources.filter { it.id == "none" || it.urlTemplate.isNotBlank() }
}

private val noneBaseMapSource = BaseMapSource(
    id = "none",
    name = "Без подложки",
    urlTemplate = "",
    maxZoom = 1,
    isBuiltin = true,
)

private external fun encodeURIComponent(value: String): String

private fun JobStatus.serializedName(): String = when (this) {
    JobStatus.PENDING -> "pending"
    JobStatus.RUNNING -> "running"
    JobStatus.COMPLETED -> "completed"
    JobStatus.FAILED -> "failed"
}

private fun JobStatus.label(): String = when (this) {
    JobStatus.PENDING -> "Ожидает"
    JobStatus.RUNNING -> "Выполняется"
    JobStatus.COMPLETED -> "Завершено"
    JobStatus.FAILED -> "Ошибка"
}

private fun SourceType.serializedName(): String = when (this) {
    SourceType.RASTER -> "raster"
    SourceType.RASTER_DEM -> "raster-dem"
}

private fun SourceType.label(): String = when (this) {
    SourceType.RASTER -> "Растровая карта"
    SourceType.RASTER_DEM -> "Рельеф DEM"
}

private data class MbtilesUploadUiState(
    val uploadId: String,
    val filename: String,
    val stage: MbtilesUploadStage,
    val percent: Int,
    val loadedBytes: Double,
    val totalBytes: Double?,
    val startedAt: Double,
    val error: String? = null,
) {
    val bytesPerSecond: Double
        get() {
            val elapsedSeconds = ((Date.now() - startedAt) / 1000.0).coerceAtLeast(0.001)
            return loadedBytes / elapsedSeconds
        }
}

private fun MbtilesUploadStage.label(): String = when (this) {
    MbtilesUploadStage.UPLOADING -> "Загрузка файла на сервер"
    MbtilesUploadStage.VALIDATING -> "Проверка MBTiles"
    MbtilesUploadStage.READING_METADATA -> "Чтение метаданных"
    MbtilesUploadStage.DETECTING_TYPE -> "Определение типа данных"
    MbtilesUploadStage.PREPARING_SERVER -> "Подготовка ссылок тайлового сервера"
    MbtilesUploadStage.READY -> "Готово"
    MbtilesUploadStage.ERROR -> "Ошибка"
    MbtilesUploadStage.CANCELLED -> "Отменено"
}

private fun formatBytes(value: Double?): String {
    val bytes = value ?: return "-"
    val units = listOf("Б", "КБ", "МБ", "ГБ")
    var scaled = bytes
    var unitIndex = 0
    while (scaled >= 1024.0 && unitIndex < units.lastIndex) {
        scaled /= 1024.0
        unitIndex += 1
    }
    val digits = if (scaled >= 10.0 || unitIndex == 0) 0 else 1
    return "${scaled.asDynamic().toFixed(digits) as String} ${units[unitIndex]}"
}

private fun localizeUploadError(message: String?): String {
    val raw = extractErrorDetail(message.orEmpty())
    return when {
        raw.contains("aborted", ignoreCase = true) -> "Загрузка отменена."
        raw.contains(".mbtiles", ignoreCase = true) -> "Нужно выбрать файл .mbtiles."
        raw.contains("source_type", ignoreCase = true) -> "Некорректный тип MBTiles. Выберите автоопределение, растровую карту или рельеф DEM."
        raw.contains("Upload failed", ignoreCase = true) -> "Не удалось обработать MBTiles на сервере. Проверьте файл и попробуйте снова."
        raw.contains("Request failed", ignoreCase = true) -> "Не удалось загрузить MBTiles. Проверьте подключение к серверу и попробуйте снова."
        raw.isBlank() -> "Не удалось загрузить MBTiles. Попробуйте снова."
        else -> raw
    }
}

private fun extractErrorDetail(message: String): String {
    val marker = "\"detail\":\""
    if (!message.startsWith("{") || !message.contains(marker)) return message
    return message.substringAfter(marker)
        .substringBeforeLast("\"")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
}

private fun addressScopedUrl(address: ServerAddress, path: String): String {
    return ApiClient.absoluteUrl(address.baseUrl + path)
}

private fun ServerAddress.labelRu(): String = when (id) {
    "public" -> "Публичный адрес"
    "lan-domain" -> "Домен локальной сети для удалённых устройств"
    "local-domain" -> "Локальный домен"
    "lan-primary" -> "Wi-Fi / локальная сеть"
    "localhost" -> "Этот компьютер"
    "request-host" -> "Адрес текущего браузера"
    else -> if (id.startsWith("lan-alt-")) "Альтернативный адрес локальной сети" else label
}

private fun ServerAddress.descriptionRu(): String = when (id) {
    "public" -> "Используйте этот внешний адрес из других сетей и с удалённых устройств."
    "lan-domain" -> "HTTP-домен указывает на найденный IP в локальной сети. Используйте его с удалееного устройства в той же сети."
    "local-domain" -> "Попробуйте этот HTTP .local адрес с другого устройства в той же сети."
    "lan-primary" -> "Используйте этот адрес с удалееного устройства или другого устройства в той же локальной сети."
    "localhost" -> "Используйте этот адрес только на локальном компьютере, где запущен сервер."
    "request-host" -> "Это адрес, через который текущий браузер открыл интерфейс."
    else -> if (id.startsWith("lan-alt-")) "Альтернативный адрес локальной сети." else description
}

private fun installAppErrorFallback() {
    fun renderError(message: String) {
        val safeMessage = message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        document.getElementById("root")?.innerHTML = """
            <div class="app-shell">
              <div class="app-header">
                <h1>Terrain Converter</h1>
              </div>
              <main class="layout">
                <section class="panel warning-panel">
                  <h2>Что-то пошло не так</h2>
                  <pre>$safeMessage</pre>
                </section>
              </main>
            </div>
        """.trimIndent()
    }

    window.addEventListener("error", { event ->
        val message = event.asDynamic().message?.toString() ?: "Необработанная ошибка интерфейса"
        renderError(message)
    })
    window.addEventListener("unhandledrejection", { event ->
        val reason = event.asDynamic().reason
        val message = reason?.message?.toString() ?: reason?.toString() ?: "Необработанная асинхронная ошибка интерфейса"
        renderError(message)
    })
}

private fun inputValue(id: String): String {
    val input = document.getElementById(id)?.unsafeCast<HTMLInputElement>()
    return input?.value.orEmpty()
}

private fun inputFiles(id: String): List<File> {
    val input = document.getElementById(id)?.unsafeCast<HTMLInputElement>() ?: return emptyList()
    val files = input.files ?: return emptyList()
    val result = mutableListOf<File>()
    for (index in 0 until files.length) {
        files.item(index)?.let(result::add)
    }
    return result
}

internal fun buildTerrainPreviewStyle(job: Job, baseSource: BaseMapSource?): dynamic {
    val sources = js("({})")
    sources.terrain = js("({})")
    sources.terrain.type = "raster-dem"
    sources.terrain.tiles = arrayOf("/api/jobs/${job.id}/terrain/{z}/{x}/{y}.png")
    sources.terrain.encoding = job.options.encoding.serializedName()
    sources.terrain.tileSize = job.options.tileSize
    sources.terrain.scheme = job.options.scheme.serializedName()
    // Performance: limit tile loading to job bounds
    job.result.bounds?.let {
        sources.terrain.bounds = arrayOf(it.west, it.south, it.east, it.north)
    }
    job.options.minzoom?.let { sources.terrain.minzoom = it }
    job.options.maxzoom?.let { sources.terrain.maxzoom = it }

    val layers = mutableListOf<dynamic>()
    layers.add(backgroundLayer())

    addBasePreviewLayer(sources, layers, baseSource, job.result.bounds, job.options.minzoom, job.options.maxzoom)

    layers.add(hillshadeLayer(id = "terrain-hillshade", source = "terrain"))

    val style = js("({})")
    style.version = 8
    style.sources = sources
    style.layers = layers.toTypedArray()
    style.terrain = js("({ source: 'terrain', exaggeration: 1.15 })")
    return style
}

private fun buildBasePreviewStyle(baseSource: BaseMapSource?, bounds: BBox? = null, minzoom: Int? = null, maxzoom: Int? = null): dynamic {
    val sources = js("({})")
    val layers = mutableListOf<dynamic>()
    layers.add(backgroundLayer())

    addBasePreviewLayer(sources, layers, baseSource, bounds, minzoom, maxzoom)

    val style = js("({})")
    style.version = 8
    style.sources = sources
    style.layers = layers.toTypedArray()
    return style
}

private fun buildMbtilesPreviewStyle(tileset: MbtilesTileset, baseSource: BaseMapSource?): dynamic {
    val sources = js("({})")
    sources["mbtiles-raster"] = js("({})")
    sources["mbtiles-raster"].type = tileset.sourceType.serializedName()
    sources["mbtiles-raster"].tiles = arrayOf(tileset.tileUrlTemplate)
    sources["mbtiles-raster"].tileSize = 256
    // Performance: limit tile loading to tileset bounds
    tileset.bounds?.let {
        sources["mbtiles-raster"].bounds = arrayOf(it.west, it.south, it.east, it.north)
    }
    tileset.minzoom?.let { sources["mbtiles-raster"].minzoom = it }
    tileset.maxzoom?.let { sources["mbtiles-raster"].maxzoom = it }
    if (tileset.sourceType == SourceType.RASTER_DEM) {
        sources["mbtiles-raster"].encoding = "mapbox"
    }

    val layers = mutableListOf<dynamic>()
    layers.add(backgroundLayer())

    addBasePreviewLayer(sources, layers, baseSource, tileset.bounds, tileset.minzoom, tileset.maxzoom)

    val style = js("({})")
    style.version = 8
    style.sources = sources
    if (tileset.sourceType == SourceType.RASTER_DEM) {
        layers.add(hillshadeLayer(id = "mbtiles-hillshade", source = "mbtiles-raster"))
        style.layers = layers.toTypedArray()
        style.terrain = js("({ source: 'mbtiles-raster', exaggeration: 1.15 })")
    } else {
        layers.add(rasterLayer(id = "mbtiles-raster", source = "mbtiles-raster"))
        style.layers = layers.toTypedArray()
    }
    return style
}

private fun addBasePreviewLayer(
    sources: dynamic,
    layers: MutableList<dynamic>,
    baseSource: BaseMapSource?,
    bounds: BBox? = null,
    minzoom: Int? = null,
    maxzoom: Int? = null,
) {
    if (baseSource == null || baseSource.id == "none") return

    sources["base-map"] = js("({})")
    sources["base-map"].type = "raster"
    sources["base-map"].tiles = arrayOf(baseSource.urlTemplate)
    sources["base-map"].tileSize = 256
    sources["base-map"].maxzoom = baseSource.maxZoom
    // Performance: limit base map tiles to data bounds to reduce requests
    bounds?.let { sources["base-map"].bounds = arrayOf(it.west, it.south, it.east, it.north) }
    baseSource.attribution?.let { sources["base-map"].attribution = it }
    layers.add(rasterLayer(id = "base-map", source = "base-map"))
}

private fun backgroundLayer(): dynamic {
    val paint = js("({})")
    paint["background-color"] = "#dbeafe"

    val layer = js("({})")
    layer.id = "background"
    layer.type = "background"
    layer.paint = paint
    return layer
}

private fun rasterLayer(id: String, source: String): dynamic {
    val layer = js("({})")
    layer.id = id
    layer.type = "raster"
    layer.source = source
    return layer
}

private fun hillshadeLayer(id: String, source: String): dynamic {
    val paint = js("({})")
    paint["hillshade-shadow-color"] = "#1e293b"
    paint["hillshade-highlight-color"] = "#f8fafc"
    paint["hillshade-accent-color"] = "#94a3b8"
    paint["hillshade-exaggeration"] = 0.8

    val layer = js("({})")
    layer.id = id
    layer.type = "hillshade"
    layer.source = source
    layer.paint = paint
    return layer
}

private fun TerrainEncoding.serializedName(): String = when (this) {
    TerrainEncoding.MAPBOX -> "mapbox"
}

private fun TileScheme.serializedName(): String = when (this) {
    TileScheme.XYZ -> "xyz"
    TileScheme.TMS -> "tms"
}

private fun mapLibreErrorMessage(event: dynamic): String {
    val message = event?.error?.message ?: event?.message
    return "Предупреждение предпросмотра: ${message?.toString() ?: "MapLibre не смог загрузить ресурс карты."}"
}

private suspend fun copyTextToClipboard(value: String): Boolean {
    val isSecureContext = window.asDynamic().isSecureContext as? Boolean ?: false
    if (!isSecureContext && copyTextWithTextarea(value)) return true

    val clipboard = window.navigator.asDynamic().clipboard ?: return false
    return runCatching {
        clipboard.writeText(value).unsafeCast<Promise<Unit>>().await()
    }.isSuccess || copyTextWithTextarea(value)
}

private fun copyTextWithTextarea(value: String): Boolean {
    val body = document.body ?: return false
    val textarea = document.createElement("textarea").unsafeCast<HTMLTextAreaElement>()
    textarea.value = value
    textarea.setAttribute("readonly", "readonly")
    textarea.style.position = "fixed"
    textarea.style.left = "-9999px"
    body.appendChild(textarea)
    textarea.select()
    val copied = runCatching { document.asDynamic().execCommand("copy") as Boolean }.getOrDefault(false)
    body.removeChild(textarea)
    return copied
}

private fun jsNumber(value: Double): String = value.asDynamic().toFixed(2) as String

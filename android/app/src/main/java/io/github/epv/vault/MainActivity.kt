package io.github.epv.vault

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * EPV1 Android front-end. Directly exports RGB PNGs to Pictures/EncryptedPhotoVault,
 * where Pixel's Google Photos can back them up after the device folder is enabled.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = VaultRepository(this)
        setContent {
            MaterialTheme {
                val scanStore = remember { NsfwProgressStore(this@MainActivity) }
                var password by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var nsfwThreshold by remember { mutableFloatStateOf(scanStore.lastThreshold()) }
                var nsfwTarget by remember { mutableStateOf(scanStore.lastFolder()) }
                var scanJob by remember { mutableStateOf<Job?>(null) }
                var nsfwCheckpoint by remember { mutableLongStateOf(0L) }
                var status by remember { mutableStateOf("Готово к работе. Данные не отправляются на сторонние серверы.") }
                var galleryUri by remember { mutableStateOf<Uri?>(null) }
                var cloudSelected by remember { mutableStateOf(false) }
                var galleryItems by remember { mutableStateOf<List<VaultRepository.Item>>(emptyList()) }
                var imagePreview by remember { mutableStateOf<Bitmap?>(null) }
                var videoPreview by remember { mutableStateOf<EpvMediaDataSource?>(null) }
                var previewTitle by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                val thumbSemaphore = remember { Semaphore(2) }
                fun startNsfwScan(folder: Uri) {
                    if (busy || password.isEmpty()) {
                        status = "Введите пароль или завершите текущую операцию"
                        return
                    }
                    val currentPassword = password
                    val currentThreshold = nsfwThreshold
                    val scanId = scanStore.scanId(folder, currentThreshold)
                    try {
                        scanStore.rememberFolder(folder, currentThreshold)
                        nsfwTarget = folder
                    } catch (t: Exception) {
                        status = "Не удалось сохранить папку: ${t.localizedMessage}"
                        return
                    }
                    scanJob = scope.launch {
                        busy = true
                        status = "Продолжение проверки с сохранённого места..."
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val jobContext = currentCoroutineContext()
                                LocalNsfwClassifier(this@MainActivity).use { classifier ->
                                    repo.encryptFolder(
                                        folder, currentPassword,
                                        { index, count, filename, part, parts ->
                                            runOnUiThread {
                                                if (scanJob?.isActive == true) {
                                                    status = if (part == 0)
                                                        "Поиск 18+: $index/$count — $filename"
                                                    else
                                                        "Шифрование 18+: $index/$count — PNG $part/$parts"
                                                }
                                            }
                                        },
                                        checkCancelled = { jobContext.ensureActive() },
                                        shouldEncrypt = { uri ->
                                            jobContext.ensureActive()
                                            val score = classifier.scoreMedia(uri)
                                            jobContext.ensureActive()
                                            score != null && score >= currentThreshold
                                        },
                                        alreadyProcessed = { uri, length, modified ->
                                            scanStore.alreadyScanned(scanId, uri, length, modified)
                                        },
                                        onProcessed = { uri, length, modified ->
                                            scanStore.markScanned(scanId, uri, length, modified)
                                        }
                                    )
                                }
                            }
                            nsfwCheckpoint = withContext(Dispatchers.IO) {
                                scanStore.completedCount(scanId)
                            }
                            status = "Поиск завершён. Уже проверено ранее: ${result.previouslyProcessed}; " +
                                "новых зашифровано: ${result.successful} (${result.pngCount} PNG), " +
                                "без 18+: ${result.skipped}; ошибок: ${result.failed}. " +
                                result.failures.joinToString("; ")
                        } catch (_: CancellationException) {
                            // Completed receipts were committed before cancellation.
                            status = "Поиск 18+ остановлен. Проверенные файлы сохранены в прогрессе. " +
                                "Нажмите «Продолжить», чтобы начать с оставшихся."
                        } catch (t: Throwable) {
                            status = "Ошибка поиска 18+: ${t.localizedMessage}. " +
                                "Готовые файлы останутся отмечены для продолжения."
                        } finally {
                            nsfwCheckpoint = scanStore.completedCount(scanId)
                            busy = false
                            scanJob = null
                        }
                    }
                }
                LaunchedEffect(nsfwTarget, nsfwThreshold) {
                    val target = nsfwTarget
                    nsfwCheckpoint = if (target == null) 0L else withContext(Dispatchers.IO) {
                        scanStore.completedCount(scanStore.scanId(target, nsfwThreshold))
                    }
                }
                val encryptPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { files ->
                    if (files.isEmpty()) return@rememberLauncherForActivityResult
                    if (password.isEmpty()) { status = "Введите пароль"; return@rememberLauncherForActivityResult }
                    val currentPassword = password
                    scope.launch {
                        busy = true
                        try {
                            var total = 0
                            withContext(Dispatchers.IO) {
                                for ((index, file) in files.withIndex()) {
                                    val filename = file.lastPathSegment.orEmpty().takeLast(50)
                                    withContext(Dispatchers.Main) {
                                        status = "Шифрование ${index + 1}/${files.size}: $filename"
                                    }
                                    total += repo.encryptOne(file, currentPassword, { n, count ->
                                        // Progress is intentionally throttled to one update per PNG.
                                        runOnUiThread { status = "Файл ${index + 1}/${files.size}, часть $n/$count" }
                                    })
                                }
                            }
                            status = "Готово: ${files.size} файлов → $total PNG. Папка Pictures/EncryptedPhotoVault"
                        } catch (t: Throwable) {
                            status = "Ошибка шифрования: ${t.localizedMessage}"
                        } finally { busy = false }
                    }
                }
                val encryptFolderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { folder ->
                    if (folder == null) return@rememberLauncherForActivityResult
                    if (password.isEmpty()) { status = "Введите пароль"; return@rememberLauncherForActivityResult }
                    val currentPassword = password
                    scope.launch {
                        busy = true
                        status = "Поиск файлов в выбранной папке..."
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val jobContext = currentCoroutineContext()
                                repo.encryptFolder(folder, currentPassword, { file, total, name, part, parts ->
                                    runOnUiThread {
                                        status = if (part == 0)
                                            "Шифрование $file/$total: $name"
                                        else
                                            "Шифрование $file/$total: $name — PNG $part/$parts"
                                    }
                                }, { jobContext.ensureActive() })
                            }
                            status = when {
                                result.total == 0 -> "В выбранной папке нет файлов для шифрования"
                                result.failed == 0 -> "Готово: ${result.successful} файлов → ${result.pngCount} PNG. Pictures/EncryptedPhotoVault"
                                else -> "Зашифровано ${result.successful}/${result.total} файлов → ${result.pngCount} PNG. " +
                                    "Ошибок: ${result.failed}. " + result.failures.joinToString("; ")
                            }
                        } catch (t: Throwable) {
                            status = "Ошибка обработки папки: ${t.localizedMessage}"
                        } finally {
                            busy = false
                        }
                    }
                }
                val restorePicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { folder ->
                    if (folder == null) return@rememberLauncherForActivityResult
                    if (password.isEmpty()) { status = "Введите пароль"; return@rememberLauncherForActivityResult }
                    val currentPassword = password
                    scope.launch {
                        busy = true
                        try {
                            val recovered = withContext(Dispatchers.IO) {
                                repo.restore(folder, currentPassword) { n, count ->
                                    runOnUiThread { status = "Восстановление: часть $n/$count" }
                                }
                            }
                            status = "Восстановлено ${recovered.size} файлов в Download/EncryptedPhotoVault: " +
                                    recovered.take(3).joinToString(", ")
                        } catch (t: Throwable) {
                            status = "Ошибка восстановления: ${t.localizedMessage}"
                        } finally { busy = false }
                    }
                }
                val galleryPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { folder ->
                    if (folder == null) return@rememberLauncherForActivityResult
                    if (password.isEmpty()) { status = "Введите пароль"; return@rememberLauncherForActivityResult }
                    val currentPassword = password
                    scope.launch {
                        busy = true
                        status = "Чтение зашифрованной галереи..."
                        try {
                            val items = withContext(Dispatchers.IO) { repo.gallery(folder, currentPassword) }
                            galleryUri = folder
                            cloudSelected = false
                            galleryItems = items
                            status = "Найдено ${items.size} зашифрованных файлов"
                        } catch (t: Throwable) { status = "Ошибка галереи: ${t.localizedMessage}" }
                        finally { busy = false }
                    }
                }
                val cloudPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickMultipleVisualMedia(100)
                ) { selected ->
                    if (selected.isEmpty()) return@rememberLauncherForActivityResult
                    if (password.isEmpty()) { status = "Введите пароль"; return@rememberLauncherForActivityResult }
                    val currentPassword = password
                    scope.launch {
                        busy = true
                        status = "Чтение выбранных PNG через Google Фото..."
                        try {
                            val items = withContext(Dispatchers.IO) {
                                repo.galleryPicked(selected, currentPassword)
                            }
                            cloudSelected = true
                            galleryUri = null
                            galleryItems = items
                            status = if (items.isEmpty())
                                "Не найдено EPV1 PNG. Выбирайте оригинальные PNG, а не обычные фото или изменённые копии."
                            else
                                "Открыто из облачного выбора: ${items.size} файлов. " +
                                "Скачивать их вручную в папку приложения не требуется."
                        } catch (t: Throwable) {
                            status = "Ошибка облачного выбора: ${t.localizedMessage}"
                        } finally { busy = false }
                    }
                }
                val nsfwFolderPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { folder ->
                    if (folder == null) return@rememberLauncherForActivityResult
                    if (busy || password.isEmpty()) {
                        status = "Введите пароль или завершите текущую операцию"
                        return@rememberLauncherForActivityResult
                    }
                    try {
                        contentResolver.takePersistableUriPermission(
                            folder, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: SecurityException) {
                        status = "Нет постоянного доступа к папке: после перезапуска потребуется выбрать её снова"
                    }
                    startNsfwScan(folder)
                }
                if (imagePreview != null) {
                    Dialog(
                        onDismissRequest = { imagePreview = null },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(10.dp)) {
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(previewTitle, style = MaterialTheme.typography.titleMedium,
                                         modifier = Modifier.weight(1f), maxLines = 2,
                                         overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { imagePreview = null }) { Text("Закрыть") }
                                }
                                var zoom by remember(imagePreview) { mutableFloatStateOf(1f) }
                                var pan by remember(imagePreview) { mutableStateOf(Offset.Zero) }
                                val transform = rememberTransformableState { deltaZoom, deltaPan, _ ->
                                    zoom = (zoom * deltaZoom).coerceIn(1f, 5f)
                                    pan = if (zoom <= 1f) Offset.Zero else pan + deltaPan
                                }
                                Box(Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center) {
                                    Image(bitmap = imagePreview!!.asImageBitmap(),
                                        contentDescription = previewTitle,
                                        modifier = Modifier.fillMaxSize()
                                            .graphicsLayer(scaleX = zoom, scaleY = zoom,
                                                translationX = pan.x, translationY = pan.y)
                                            .transformable(transform),
                                        contentScale = ContentScale.Fit)
                                }
                            }
                        }
                    }
                }
                if (videoPreview != null) {
                    val selected = videoPreview!!
                    AlertDialog(
                        onDismissRequest = { videoPreview = null },
                        title = { Text(previewTitle) },
                        text = { PrivateVideoPlayer(selected) },
                        confirmButton = { TextButton(onClick = { videoPreview = null }) { Text("Закрыть") } }
                    )
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Encrypted Photo Vault", style = MaterialTheme.typography.headlineMedium)
                        Text("EPV1 • Шифрование медиа в пиксели PNG", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Пароль шифрования") },
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !busy,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Пароль не передаётся в облако. Потеря пароля означает потерю доступа к файлам.",
                             style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Зашифровать", style = MaterialTheme.typography.titleLarge)
                        Text("Выберите фото, видео или другие файлы. PNG сохранятся в Pictures/EncryptedPhotoVault. " +
                             "Эту папку можно перенести на Pixel XL для загрузки в Google Фото.")
                        Button(
                            onClick = { encryptPicker.launch(arrayOf("*/*")) },
                            enabled = !busy && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Выбрать файлы и зашифровать") }
                        Text("Или выберите целую папку — все файлы внутри, включая подпапки, " +
                             "будут зашифрованы по очереди. Исходные файлы не изменяются.",
                             style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { encryptFolderPicker.launch(null) },
                            enabled = !busy && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Выбрать папку и зашифровать всё") }
                        HorizontalDivider()
                        Text("Восстановить", style = MaterialTheme.typography.titleLarge)
                        Text("Скачайте PNG из Google Фото в одну папку и укажите её. " +
                             "Оригиналы появятся в Download/EncryptedPhotoVault. " +
                             "При шифровании папки вложенные каталоги сохраняются при восстановлении.")
                        Button(
                            onClick = { restorePicker.launch(null) },
                            enabled = !busy && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Выбрать папку с PNG") }
                        HorizontalDivider()
                        HorizontalDivider()
                        Text("Локальный фильтр 18+", style = MaterialTheme.typography.titleLarge)
                        Text("Модель TensorFlow Lite работает только на устройстве. Для фото проверяется изображение, " +
                             "для видео — несколько кадров. Обнаруженные файлы шифруются, оригиналы не удаляются.")
                        Text("Порог: ${(nsfwThreshold * 100).toInt()}%")
                        Slider(value = nsfwThreshold, onValueChange = { nsfwThreshold = it },
                            valueRange = 0.5f..0.98f, enabled = !busy)
                        if (nsfwTarget != null) {
                            Text("Папка: ${nsfwTarget?.lastPathSegment?.substringAfterLast(':') ?: "выбрана"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("В базе уже проверено: $nsfwCheckpoint файлов для этого порога",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { nsfwFolderPicker.launch(null) },
                            enabled = !busy && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Выбрать папку и запустить поиск 18+") }
                        if (nsfwTarget != null) {
                            Button(
                                onClick = { nsfwTarget?.let(::startNsfwScan) },
                                enabled = !busy && password.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("▶ Продолжить с последнего файла") }
                        }
                        if (scanJob != null) {
                            Button(
                                onClick = {
                                    status = "Остановка поиска 18+..."
                                    scanJob?.cancel()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("■ Остановить поиск 18+") }
                        }
                        if (nsfwTarget != null) {
                            TextButton(
                                onClick = {
                                    val folder = nsfwTarget ?: return@TextButton
                                    scanStore.clear(scanStore.scanId(folder, nsfwThreshold))
                                    nsfwCheckpoint = 0L
                                    status = "Прогресс сброшен для выбранного порога. " +
                                        "При следующем запуске файлы проверятся заново."
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Сбросить прогресс для этой папки и порога") }
                        }
                        Text("Прогресс записывается после каждого полностью обработанного файла. " +
                             "При остановке или перезапуске повторный анализ обработанных файлов не нужен. " +
                             "Для продолжения используйте тот же пароль и порог.",
                             style = MaterialTheme.typography.bodySmall)
                        Text("Результаты классификации могут быть ошибочны. Рекомендуется проверить их вручную; " +
                             "данные не удаляются и не передаются в интернет.",
                             style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Приватная галерея", style = MaterialTheme.typography.titleLarge)
                        Text("Выберите папку со скачанными EPV PNG. Фото показываются только в памяти, " +
                             "видео воспроизводится из зашифрованных частей без экспорта в общую папку.")
                        Button(
                            onClick = { galleryPicker.launch(null) },
                            enabled = !busy && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Открыть зашифрованную галерею") }
                        Button(
                            onClick = {
                                cloudPicker.launch(PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = !busy && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Выбрать зашифрованные PNG из Google Фото") }
                        Text("Google позволяет читать только выбранные элементы. " +
                             "Укажи все PNG-части нужного файла: без них восстановление невозможно. " +
                             "Google Фото должен быть включён как облачный источник Android Photo Picker.",
                             style = MaterialTheme.typography.bodySmall)
                        if (galleryItems.isNotEmpty()) {
                            Text("Моя галерея • ${galleryItems.size} файлов",
                                style = MaterialTheme.typography.titleMedium)
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(132.dp),
                                modifier = Modifier.fillMaxWidth().height(440.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(galleryItems, key = { it.id }) { item ->
                                    var thumb by remember(item.id, galleryUri, cloudSelected) {
                                        mutableStateOf<Bitmap?>(null)
                                    }
                                    LaunchedEffect(item.id, galleryUri, cloudSelected, password) {
                                        if (item.mime?.startsWith("image/") == true) {
                                            val currentPassword = password
                                            thumb = withContext(Dispatchers.IO) {
                                                thumbSemaphore.withPermit {
                                                try {
                                                    val opened = if (cloudSelected)
                                                        repo.openPickedMedia(item.id, currentPassword)
                                                    else
                                                        repo.openMedia(galleryUri ?: return@withContext null,
                                                            item.id, currentPassword)
                                                    try {
                                                        val bounds = BitmapFactory.Options().apply {
                                                            inJustDecodeBounds = true
                                                        }
                                                        opened.inputStream().use {
                                                            BitmapFactory.decodeStream(it, null, bounds)
                                                        }
                                                        val opts = BitmapFactory.Options()
                                                        var scale = 1
                                                        while (bounds.outWidth / scale > 280 ||
                                                               bounds.outHeight / scale > 280) scale *= 2
                                                        opts.inSampleSize = scale
                                                        opened.inputStream().use {
                                                            BitmapFactory.decodeStream(it, null, opts)
                                                        }
                                                    } finally { opened.close() }
                                                } catch (_: Exception) { null }
                                                }
                                            }
                                        }
                                    }
                                    Card(
                                        Modifier.fillMaxWidth().clickable(
                                            enabled = !busy && item.mime != null) {
                                            val folder = galleryUri
                                            val currentPassword = password
                                            scope.launch {
                                                busy = true
                                                status = "Расшифровка предпросмотра..."
                                                var source: EpvMediaDataSource? = null
                                                try {
                                                    val opened = withContext(Dispatchers.IO) {
                                                        if (cloudSelected)
                                                            repo.openPickedMedia(item.id, currentPassword)
                                                        else
                                                            repo.openMedia(folder ?: error("Выберите папку"),
                                                                item.id, currentPassword)
                                                    }
                                                    source = opened
                                                    previewTitle = opened.filename
                                                    if (opened.mime.startsWith("image/")) {
                                                        val bitmap = withContext(Dispatchers.IO) {
                                                            val bounds = BitmapFactory.Options().apply {
                                                                inJustDecodeBounds = true
                                                            }
                                                            opened.inputStream().use {
                                                                BitmapFactory.decodeStream(it, null, bounds)
                                                            }
                                                            val opts = BitmapFactory.Options()
                                                            var sample = 1
                                                            while (bounds.outWidth / sample > 2048 ||
                                                                   bounds.outHeight / sample > 2048) sample *= 2
                                                            opts.inSampleSize = sample
                                                            opened.inputStream().use {
                                                                BitmapFactory.decodeStream(it, null, opts)
                                                            }
                                                        } ?: error("Формат изображения не поддерживается")
                                                        opened.close()
                                                        source = null
                                                        imagePreview = bitmap
                                                    } else if (opened.mime.startsWith("video/")) {
                                                        videoPreview = opened
                                                        source = null
                                                    } else error("Для других форматов используйте восстановление")
                                                    status = "Предпросмотр открыт"
                                                } catch (t: Throwable) {
                                                    status = "Ошибка предпросмотра: ${t.localizedMessage}"
                                                } finally { source?.close(); busy = false }
                                            }
                                        }
                                    ) {
                                        Column(Modifier.padding(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            val bitmap = thumb
                                            if (bitmap != null) {
                                                Image(bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = item.name,
                                                    modifier = Modifier.fillMaxWidth().height(104.dp),
                                                    contentScale = ContentScale.Crop)
                                            } else {
                                                Box(Modifier.fillMaxWidth().height(104.dp),
                                                    contentAlignment = Alignment.Center) {
                                                    Text(if (item.mime?.startsWith("video/") == true)
                                                        "▶ Видео" else "▧")
                                                }
                                            }
                                            Text(item.name ?: item.id.take(12),
                                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall)
                                            Text("${item.parts}/${item.expected} PNG",
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        HorizontalDivider()
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        Text("Важно: режим Google Фото «Исходное качество». " +
                             "Не удаляйте исходники, пока не проверили загрузку и восстановление.",
                             style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}


@Composable
private fun PrivateVideoPlayer(source: EpvMediaDataSource) {
    var ready by remember(source) { mutableStateOf(false) }
    val player = remember(source) { MediaPlayer() }
    DisposableEffect(player, source) {
        try {
            player.setDataSource(source)
            player.setOnPreparedListener { ready = true; it.start() }
            player.prepareAsync()
        } catch (_: Exception) { ready = false }
        onDispose {
            player.release()
            source.close()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { player.setDisplay(holder) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) { player.setDisplay(null) }
                    })
                }
            },
            modifier = Modifier.fillMaxWidth().height(230.dp)
        )
        Button(enabled = ready, onClick = {
            if (player.isPlaying) player.pause() else player.start()
        }) { Text("Пауза / Продолжить") }
    }
}

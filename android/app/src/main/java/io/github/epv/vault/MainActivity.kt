package io.github.epv.vault

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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                var password by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf("Готово к работе. Данные не отправляются на сторонние серверы.") }
                var galleryUri by remember { mutableStateOf<Uri?>(null) }
                var galleryItems by remember { mutableStateOf<List<VaultRepository.Item>>(emptyList()) }
                var imagePreview by remember { mutableStateOf<Bitmap?>(null) }
                var videoPreview by remember { mutableStateOf<EpvMediaDataSource?>(null) }
                var previewTitle by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
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
                            galleryItems = items
                            status = "Найдено ${items.size} зашифрованных файлов"
                        } catch (t: Throwable) { status = "Ошибка галереи: ${t.localizedMessage}" }
                        finally { busy = false }
                    }
                }
                if (imagePreview != null) {
                    AlertDialog(
                        onDismissRequest = { imagePreview = null },
                        title = { Text(previewTitle) },
                        text = {
                            Image(bitmap = imagePreview!!.asImageBitmap(),
                                  contentDescription = previewTitle,
                                  modifier = Modifier.fillMaxWidth().heightIn(max = 510.dp),
                                  contentScale = ContentScale.Fit)
                        },
                        confirmButton = { TextButton(onClick = { imagePreview = null }) { Text("Закрыть") } }
                    )
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
                        Text("Приватная галерея", style = MaterialTheme.typography.titleLarge)
                        Text("Выберите папку со скачанными EPV PNG. Фото показываются только в памяти, " +
                             "видео воспроизводится из зашифрованных частей без экспорта в общую папку.")
                        Button(
                            onClick = { galleryPicker.launch(null) },
                            enabled = !busy && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Открыть зашифрованную галерею") }
                        galleryItems.forEach { item ->
                            Card(
                                Modifier.fillMaxWidth().clickable(enabled = !busy && item.mime != null) {
                                    val folder = galleryUri ?: return@clickable
                                    val currentPassword = password
                                    scope.launch {
                                        busy = true
                                        status = "Расшифровка предпросмотра..."
                                        var source: EpvMediaDataSource? = null
                                        try {
                                            val opened = withContext(Dispatchers.IO) {
                                                repo.openMedia(folder, item.id, currentPassword)
                                            }
                                            source = opened
                                            previewTitle = opened.filename
                                            if (opened.mime.startsWith("image/")) {
                                                val bitmap = withContext(Dispatchers.IO) {
                                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                                    opened.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
                                                    val opts = BitmapFactory.Options()
                                                    var sample = 1
                                                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                                                    opts.inSampleSize = sample
                                                    opened.inputStream().use { BitmapFactory.decodeStream(it, null, opts) }
                                                } ?: error("Формат изображения не поддерживается")
                                                opened.close()
                                                source = null
                                                imagePreview = bitmap
                                            } else if (opened.mime.startsWith("video/")) {
                                                videoPreview = opened
                                                source = null
                                            } else {
                                                error("Встроенный просмотр предназначен для фото и видео. Для других файлов используйте восстановление.")
                                            }
                                            status = "Предпросмотр открыт"
                                        } catch (t: Throwable) {
                                            status = "Ошибка предпросмотра: ${t.localizedMessage}"
                                        } finally { source?.close(); busy = false }
                                    }
                                }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(item.name ?: item.id.take(12), style = MaterialTheme.typography.titleMedium)
                                    Text("${item.parts}/${item.expected} PNG • ${item.mime ?: "недоступно"}",
                                        style = MaterialTheme.typography.bodySmall)
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

package io.github.epv.vault

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Foreground, user-started 18+ classifier. Screen-off does not pause work.
 * After force-stop or system kill, user restarts and SQLite queue resumes.
 */
class NsfwScanService : Service() {
    companion object {
        const val START = "io.github.epv.vault.SCAN_START"
        const val STOP = "io.github.epv.vault.SCAN_STOP"
        const val ORGANIZE = "organize"
        const val ENCRYPT = "encrypt"
        const val SOURCE = "source"
        const val TARGET = "target"
        const val THRESHOLD = "threshold"
        const val REMOTE = "remote"
        const val TOKEN = "token"
        const val PASSWORD = "password"
        private const val CHANNEL = "epv_nsfw_background"
        private const val NOTIFICATION_ID = 8021
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lock: PowerManager.WakeLock? = null
    private var message = "Запуск поиска 18+"
    private var lastUpdateMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Поиск 18+ в фоне", NotificationManager.IMPORTANCE_LOW))
        ScanBackgroundState.serviceAlive = true
        ScanBackgroundState.refresh(this)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, NsfwScanService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Encrypted Photo Vault — 18+")
            .setContentText(message)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop)
            .build()
    }

    private fun publish(mode: String, text: String) {
        message = text
        ScanBackgroundState.publish(this, mode, text)
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateMs > 1800) {
            lastUpdateMs = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification())
        }
    }

    private fun releaseLock() {
        try { if (lock?.isHeld == true) lock?.release() }
        finally { lock = null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        if (intent?.action != START) { stopSelf(startId); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val mode = intent.getStringExtra("mode").orEmpty()
        val source = intent.getParcelableExtra<Uri>(SOURCE)
        val target = intent.getParcelableExtra<Uri>(TARGET)
        val threshold = intent.getFloatExtra(THRESHOLD, .70f)
        val remote = intent.getBooleanExtra(REMOTE, false)
        val token = intent.getStringExtra(TOKEN).orEmpty()
        val password = intent.getStringExtra(PASSWORD).orEmpty()
        if (source == null || mode !in setOf(ORGANIZE, ENCRYPT) ||
            (mode == ORGANIZE && target == null) ||
            (mode == ORGANIZE && remote && token.length < 12) ||
            (mode == ENCRYPT && password.isEmpty())) {
            ScanBackgroundState.publish(this, "", "Неверные параметры поиска")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            lock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "EncryptedPhotoVault:NSFWBackground").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (t: Throwable) {
            ScanBackgroundState.publish(this, "", "Android запретил фоновую службу: " +
                t.localizedMessage)
            releaseLock()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        job = scope.launch {
            publish(mode, if (remote) "Соединение с ПК по USB..." else "Начинаю поиск...")
            try {
                if (mode == ORGANIZE) organize(source, target!!, threshold, remote, token)
                else encrypt(source, password, threshold)
            } catch (_: CancellationException) {
                publish(mode, "Остановлено. Прогресс сохранён, можно продолжить.")
            } catch (t: Throwable) {
                publish(mode, "Ошибка 18+: " + t.localizedMessage + ". Очередь сохранена.")
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    ScanBackgroundState.publish(this@NsfwScanService, "", message)
                    releaseLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun organize(
        source: Uri, target: Uri, threshold: Float, remote: Boolean, token: String
    ) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val check = { context.ensureActive() }
        val store = NsfwProgressStore(this@NsfwScanService)
        val queue = NsfwFileQueue(this@NsfwScanService)
        val id = store.moveScanId(source, target, threshold)
        var client: PcNsfwClient? = null
        var classifier: LocalNsfwClassifier? = null
        try {
            if (remote) {
                client = PcNsfwClient(this@NsfwScanService, token)
                client.checkConnection()
            }
            val pc = client
            val local = lazy(LazyThreadSafetyMode.NONE) {
                LocalNsfwClassifier(this@NsfwScanService).also { classifier = it }
            }
            val result = NsfwOrganizer(this@NsfwScanService).moveFolder(
                sourceUri = source, destinationUri = target,
                threshold = threshold, scanId = id, queue = queue,
                score = { uri ->
                    check()
                    if (pc != null) pc.scoreSingle(uri, check)
                    else local.value.scoreMedia(uri)
                },
                scoreBatch = if (pc != null) { uris -> pc.scoreBatch(uris, check) } else null,
                alreadyChecked = { uri, len, time -> store.alreadyScanned(id, uri, len, time) },
                markChecked = { uri, len, time -> store.markScanned(id, uri, len, time) },
                cancelled = check,
                onIndexing = { stage, count ->
                    publish(ORGANIZE, "$stage: найдено $count файлов")
                },
                onProgress = { index, total, name ->
                    publish(ORGANIZE, "18+: $index/$total — $name")
                }
            )
            publish(ORGANIZE, "Готово. Перенесено ${result.moved}, пропущено " +
                "${result.safe}, копий без удаления ${result.copiedNotDeleted}, " +
                "ошибок ${result.errors}.")
        } finally {
            client?.close()
            classifier?.close()
            queue.close()
            store.close()
        }
    }

    private suspend fun encrypt(source: Uri, password: String, threshold: Float) =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val store = NsfwProgressStore(this@NsfwScanService)
            val id = store.scanId(source, threshold)
            try {
                LocalNsfwClassifier(this@NsfwScanService).use { classifier ->
                    val result = VaultRepository(this@NsfwScanService).encryptFolder(
                        source, password,
                        { index, total, name, part, count ->
                            publish(ENCRYPT, "18+: $index/$total — $name" +
                                if (part > 0) " PNG $part/$count" else "")
                        },
                        checkCancelled = { context.ensureActive() },
                        shouldEncrypt = { uri ->
                            context.ensureActive()
                            classifier.scoreMedia(uri)?.let { it >= threshold } ?: false
                        },
                        alreadyProcessed = { uri, len, time ->
                            store.alreadyScanned(id, uri, len, time)
                        },
                        onProcessed = { uri, len, time ->
                            store.markScanned(id, uri, len, time)
                        }
                    )
                    publish(ENCRYPT, "Готово. Зашифровано ${result.successful}, " +
                        "пропущено ${result.skipped}, ошибок ${result.failed}.")
                }
            } finally { store.close() }
        }

    override fun onTimeout(startId: Int, fgsType: Int) {
        publish(ScanBackgroundState.runningMode,
            "Лимит Android на работу в фоне (6 часов). Откройте EPV для продолжения.")
        job?.cancel()
        releaseLock()
        stopSelf(startId)
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseLock()
        ScanBackgroundState.serviceAlive = false
        ScanBackgroundState.publish(this, "", message)
        super.onDestroy()
    }
}

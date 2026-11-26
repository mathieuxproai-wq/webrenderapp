package com.hyperframe.render

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodecList
import android.media.MediaMuxer
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hyperframe PoC — service compagnon de rendu web GPU headless zero-copy.
 *
 * Pipeline (garde-fou 1 : aucun readback CPU) :
 *
 *   WebView (LAYER_TYPE_HARDWARE, VISIBLE)
 *      └─ Presentation (affichée sur le VirtualDisplay privé)
 *           └─ VirtualDisplay (VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
 *                └─ Surface cible = mediaCodec.createInputSurface()
 *                     └─ MediaCodec (video/avc, COLOR_FormatSurface)
 *                          └─ MediaMuxer ──> MediaStore.Downloads/hyperframe_poc.mp4
 *
 * Garde-fou 2 : aucun ADB / Wireless Debugging / Shizuku / root / MediaProjection.
 * Le port CDP est exposé par un simple relais TCP 127.0.0.1:9222 -> socket
 * Unix abstrait webview_devtools_remote_<pid>.
 */
class HeadlessRenderService : Service() {

    // ---------------------------------------------------------------------
    // État du pipeline
    // ---------------------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pipelineActive = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val trackStarted = AtomicBoolean(false)

    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var outputParcelFileDescriptor: ParcelFileDescriptor? = null
    @Volatile private var outputUri: Uri? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var presentation: RenderPresentation? = null
    @Volatile private var drainThread: Thread? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private var trackIndex = -1
    private var currentUrl = DEFAULT_URL
    private var currentWidth = DEFAULT_WIDTH
    private var currentHeight = DEFAULT_HEIGHT
    private var currentFps = DEFAULT_FPS
    private val frameCount = AtomicLong(0)

    private var frameKeeperRunnable: Runnable? = null

    @Volatile private var cdpServerSocket: ServerSocket? = null
    @Volatile private var cdpAcceptThread: Thread? = null
    private val cdpConnectionThreads = ConcurrentLinkedQueue<Thread>()

    // ---------------------------------------------------------------------
    // Presentation : WebView matérielle rendue sur le VirtualDisplay privé
    // ---------------------------------------------------------------------

    /**
     * Garde-fous 1, 3 et 5 : la WebView est l'unique moteur de rendu, elle est
     * accélérée matériellement et reste View.VISIBLE dans la Presentation
     * attachée au VirtualDisplay privé.
     */
    private inner class RenderPresentation(outerContext: Context, display: Display) :
        Presentation(outerContext, display, R.style.Theme_Hyperframe_Presentation) {

        lateinit var webView: WebView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }

            val root = FrameLayout(context)
            root.setBackgroundColor(Color.BLACK)

            webView = WebView(context)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            // Garde-fou 3 : WebView native + couche matérielle obligatoire.
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView.setBackgroundColor(Color.BLACK)
            webView.isVerticalScrollBarEnabled = false
            webView.isHorizontalScrollBarEnabled = false
            webView.webViewClient = WebViewClient()
            webView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            root.addView(webView)
            setContentView(root)

            // Garde-fou 4 : nettoyage du cache au démarrage (limite mémoire).
            webView.clearCache(true)
            webView.loadUrl(currentUrl)
        }
    }

    // ---------------------------------------------------------------------
    // Cycle de vie du service
    // ---------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        // Garde-fou 3 : débogage WebView (CDP) activé, pas d'ADB requis.
        WebView.setWebContentsDebuggingEnabled(true)
        createNotificationChannel()
        Log.i(TAG, "[INIT] Service créé, pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD_URL -> {
                startForegroundIfNeeded(getString(R.string.notification_title))
                loadUrlHot(intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL)
            }

            ACTION_STOP -> {
                startForegroundIfNeeded("Arrêt en cours…")
                requestStop("Commande STOP reçue")
            }

            else -> { // ACTION_START / null (redelivery du système)
                startForegroundIfNeeded(getString(R.string.notification_title))
                val url = intent?.getStringExtra(EXTRA_URL) ?: DEFAULT_URL
                val width = clampDimension(
                    intent?.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH) ?: DEFAULT_WIDTH, MAX_WIDTH
                )
                val height = clampDimension(
                    intent?.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT) ?: DEFAULT_HEIGHT, MAX_HEIGHT
                )
                val fps = clampFps(intent?.getIntExtra(EXTRA_FPS, DEFAULT_FPS) ?: DEFAULT_FPS)
                if (pipelineActive.get()) {
                    Log.i(
                        TAG,
                        "[PIPE] START ignoré : pipeline déjà actif " +
                            "(utiliser ACTION_LOAD_URL ou ACTION_STOP)"
                    )
                } else {
                    startPipeline(url, width, height, fps)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Garde-fou 5 : libération systématique du WakeLock dans onDestroy().
        stopFrameKeeper()
        stopCdpProxy()
        releaseWakeLock()
        // Nettoyage défensif si le pipeline n'a pas été arrêté proprement.
        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "[STOP] VirtualDisplay: ${e.message}") }
        try { presentation?.dismiss() } catch (e: Exception) { Log.e(TAG, "[STOP] Presentation: ${e.message}") }
        try { presentation?.webView?.destroy() } catch (e: Exception) { Log.e(TAG, "[STOP] WebView: ${e.message}") }
        try { encoder?.signalEndOfInputStream() } catch (e: Exception) { Log.e(TAG, "[STOP] EOS: ${e.message}") }
        try { encoder?.stop() } catch (e: Exception) { Log.e(TAG, "[STOP] encoder.stop: ${e.message}") }
        try { encoder?.release() } catch (e: Exception) { Log.e(TAG, "[STOP] encoder.release: ${e.message}") }
        try { inputSurface?.release() } catch (e: Exception) { Log.e(TAG, "[STOP] inputSurface: ${e.message}") }
        try { if (trackStarted.get()) muxer?.stop() } catch (e: Exception) { Log.e(TAG, "[STOP] muxer.stop: ${e.message}") }
        try { muxer?.release() } catch (e: Exception) { Log.e(TAG, "[STOP] muxer.release: ${e.message}") }
        try { outputParcelFileDescriptor?.close() } catch (e: Exception) { Log.e(TAG, "[STOP] pfd: ${e.message}") }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Démarrage du pipeline
    // ---------------------------------------------------------------------

    private fun startPipeline(url: String, width: Int, height: Int, fps: Int) {
        if (!pipelineActive.compareAndSet(false, true)) return

        stopRequested.set(false)
        trackStarted.set(false)
        trackIndex = -1
        frameCount.set(0)
        currentUrl = url
        currentWidth = width
        currentHeight = height
        currentFps = fps

        try {
            // Garde-fou 5 : notification de premier plan + PARTIAL_WAKE_LOCK.
            startForegroundIfNeeded(getString(R.string.notification_title))
            acquireWakeLock()

            // Garde-fou 7 : destination MP4 (MediaStore.Downloads, scoped storage).
            prepareOutputTarget()

            // Garde-fou 1 : encodeur vidéo/avc matériel alimenté par une Surface.
            val codecInfo = findHardwareAvcEncoder()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, computeBitrate(width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            }

            val codec = if (codecInfo != null) {
                Log.i(TAG, "[INIT] Encodeur matériel sélectionné: ${codecInfo.name}")
                MediaCodec.createByCodecName(codecInfo.name)
            } else {
                Log.i(TAG, "[INIT] Aucun encodeur AVC matériel trouvé, fallback createEncoderByType")
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            }
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Garde-fou 1 : UNIQUE lien entre le rendu et l'encodeur.
            val surface = codec.createInputSurface()
            inputSurface = surface
            codec.start()
            Log.i(
                TAG,
                "[INIT] MediaCodec configuré ($width x $height @ ${fps}fps), " +
                    "inputSurface valide=${surface.isValid}"
            )

            // Garde-fou 7 : MediaMuxer écrit directement dans le fichier MediaStore.
            val pfd = outputParcelFileDescriptor
                ?: throw IllegalStateException("ParcelFileDescriptor MediaStore indisponible")
            val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val drainer = Thread({ drainEncoderLoop() }, "HyperframeEncoderDrain")
            drainer.isDaemon = true
            drainThread = drainer
            drainer.start()

            // Garde-fous 1 et 2 : VirtualDisplay privé dont la surface cible est
            // l'inputSurface de l'encodeur (aucune permission système requise).
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val densityDpi = resources.displayMetrics.densityDpi
            val vd = displayManager.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: throw IllegalStateException("createVirtualDisplay a renvoyé null")
            virtualDisplay = vd
            Log.i(TAG, "[VD] VirtualDisplay créé, displayId=${vd.display.displayId}")

            // Garde-fou 3 : Presentation + WebView matérielle visible.
            val renderPresentation = RenderPresentation(this, vd.display)
            presentation = renderPresentation
            renderPresentation.show()
            Log.i(
                TAG,
                "[WEBVIEW] Presentation affichée, " +
                    "isHardwareAccelerated=${renderPresentation.webView.isHardwareAccelerated}, " +
                    "url=$currentUrl"
            )

            // Garde-fou 5 : maintien léger de trame (anti-gel écran éteint).
            startFrameKeeper()

            // Garde-fou 2 : relais CDP sans ADB.
            startCdpProxy()

            Log.i(
                TAG,
                "[PIPE] Pipeline zero-copy actif ($width x $height @ ${fps}fps) -> $outputUri"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[ERREUR] Démarrage du pipeline : ${e.message}", e)
            requestStop("Échec du démarrage")
        }
    }

    private fun drainEncoderLoop() {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        var eosReceived = false
        try {
            while (!eosReceived) {
                val index = codec.dequeueOutputBuffer(info, ENCODER_POLL_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val mux = muxer
                        if (mux != null && !trackStarted.get()) {
                            trackIndex = mux.addTrack(codec.outputFormat)
                            mux.start()
                            trackStarted.set(true)
                            Log.i(TAG, "[MUXER] Piste vidéo démarrée, écriture vers $outputUri")
                        }
                    }

                    index >= 0 -> {
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && trackStarted.get()) {
                            val buffer = codec.getOutputBuffer(index)
                            val mux = muxer
                            if (buffer != null && mux != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                mux.writeSampleData(trackIndex, buffer, info)
                                val count = frameCount.incrementAndGet()
                                if (count % STATS_INTERVAL_FRAMES == 0L) {
                                    Log.i(TAG, "[STATS] Frames encodées: $count")
                                }
                            }
                        }
                        try {
                            codec.releaseOutputBuffer(index, false)
                        } catch (e: Exception) {
                            Log.e(TAG, "[ERREUR] releaseOutputBuffer: ${e.message}")
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "[STATS] EOS encodeur reçu, frames totales=${frameCount.get()}")
                            eosReceived = true
                        }
                    }

                    else -> { // INFO_TRY_AGAIN_LATER
                        Thread.sleep(2)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "[ERREUR] Drain encodeur interrompu: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "[ERREUR] Drain encodeur: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------------
    // Arrêt propre du pipeline
    // ---------------------------------------------------------------------

    private fun requestStop(reason: String) {
        if (!pipelineActive.get()) {
            Log.i(TAG, "[STOP] Aucun pipeline actif ($reason), arrêt du service.")
            stopForegroundAndService()
            return
        }
        val stopper = Thread({ teardownPipeline(reason) }, "HyperframeStopper")
        stopper.isDaemon = true
        stopper.start()
    }

    private fun teardownPipeline(reason: String) {
        stopRequested.set(true)
        Log.i(TAG, "[STOP] Arrêt du pipeline demandé ($reason)")

        // 1) Presentation (garde-fou 5 : le WebView est détruit après dismiss).
        runOnMainSync {
            stopFrameKeeper()
            val renderPresentation = presentation
            presentation = null
            try { renderPresentation?.dismiss() } catch (e: Exception) {
                Log.e(TAG, "[STOP] Presentation dismiss: ${e.message}")
            }
            try { renderPresentation?.webView?.destroy() } catch (e: Exception) {
                Log.e(TAG, "[STOP] WebView destroy: ${e.message}")
            }
        }

        // 2) VirtualDisplay.
        val vd = virtualDisplay
        virtualDisplay = null
        try { vd?.release() } catch (e: Exception) {
            Log.e(TAG, "[STOP] VirtualDisplay release: ${e.message}")
        }

        // 3) Drain de l'EOS (garde-fou 7 : MP4 lisible).
        val codec = encoder
        try { codec?.signalEndOfInputStream() } catch (e: Exception) {
            Log.e(TAG, "[STOP] signalEndOfInputStream: ${e.message}")
        }
        val drainer = drainThread
        drainThread = null
        try { drainer?.join(TEARDOWN_JOIN_TIMEOUT_MS) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // 4) Fermeture MediaMuxer / MediaCodec / Surface.
        val mux = muxer
        muxer = null
        var finalized = false
        if (trackStarted.get()) {
            try {
                mux?.stop()
                finalized = true
            } catch (e: Exception) {
                Log.e(TAG, "[STOP] muxer.stop() a échoué: ${e.message}")
            }
        }
        try { mux?.release() } catch (e: Exception) { Log.e(TAG, "[STOP] muxer.release: ${e.message}") }
        if (codec != null) {
            try { codec.stop() } catch (e: Exception) { Log.e(TAG, "[STOP] codec.stop: ${e.message}") }
            try { codec.release() } catch (e: Exception) { Log.e(TAG, "[STOP] codec.release: ${e.message}") }
        }
        encoder = null
        val surface = inputSurface
        inputSurface = null
        try { surface?.release() } catch (e: Exception) { Log.e(TAG, "[STOP] surface.release: ${e.message}") }
        val pfd = outputParcelFileDescriptor
        outputParcelFileDescriptor = null
        try { pfd?.close() } catch (e: Exception) { Log.e(TAG, "[STOP] pfd.close: ${e.message}") }

        // 5) Finalisation MediaStore (IS_PENDING=0) ou nettoyage du fichier invalide.
        if (finalized) {
            finalizeOutput()
            Log.i(TAG, "[STOP] MediaMuxer et MediaCodec fermés proprement. Fichier finalisé.")
        } else {
            deletePendingOutput()
            Log.i(TAG, "[STOP] Aucune vidéo valide produite, fichier temporaire nettoyé.")
        }

        // 6) Relais CDP + WakeLock.
        stopCdpProxy()
        releaseWakeLock()

        pipelineActive.set(false)
        stopForegroundAndService()
    }

    // ---------------------------------------------------------------------
    // Actions Intent
    // ---------------------------------------------------------------------

    /** Action LOAD_URL : changement d'URL à chaud sans couper l'enregistrement. */
    private fun loadUrlHot(url: String) {
        val renderPresentation = presentation
        if (renderPresentation == null) {
            Log.i(TAG, "[WEBVIEW] LOAD_URL ignoré (aucune Presentation active), url=$url")
            return
        }
        mainHandler.post {
            try {
                currentUrl = url
                renderPresentation.webView.loadUrl(url)
                Log.i(TAG, "[WEBVIEW] URL chargée à chaud: $url")
                updateNotificationText("Rendu: $url")
            } catch (e: Exception) {
                Log.e(TAG, "[WEBVIEW] LOAD_URL échec: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Destination MP4 (MediaStore.Downloads)
    // ---------------------------------------------------------------------

    /**
     * Garde-fou 7 : écriture dans Téléchargements via MediaStore.Downloads
     * (API 29+), compatible avec le Scoped Storage d'Android 17 sans
     * demander de permission de stockage.
     */
    private fun prepareOutputTarget() {
        val resolver = contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        var displayName = OUTPUT_FILE_NAME
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/", OUTPUT_FILE_NAME),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    val existing = ContentUris.withAppendedId(collection, id)
                    try {
                        resolver.delete(existing, null, null)
                        Log.i(TAG, "[MUXER] Ancien fichier supprimé: $existing")
                    } catch (e: Exception) {
                        Log.e(TAG, "[MUXER] Suppression de l'ancien fichier refusée: ${e.message}")
                        displayName = "hyperframe_poc_${System.currentTimeMillis()}.mp4"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MUXER] Recherche de l'ancien fichier: ${e.message}")
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore.Downloads.insert a renvoyé null")
        outputUri = uri
        outputParcelFileDescriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("openFileDescriptor($uri) a renvoyé null")
        Log.i(TAG, "[MUXER] Destination MediaStore préparée: $uri")
    }

    private fun finalizeOutput() {
        val uri = outputUri ?: return
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
            Log.i(TAG, "[MUXER] MP4 disponible dans Téléchargements: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "[MUXER] Finalisation MediaStore: ${e.message}")
        }
        outputUri = null
    }

    private fun deletePendingOutput() {
        val uri = outputUri ?: return
        outputUri = null
        try {
            contentResolver.delete(uri, null, null)
            Log.i(TAG, "[MUXER] Fichier incomplet supprimé: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "[MUXER] Suppression du fichier incomplet: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // WakeLock (garde-fou 5)
    // ---------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.setReferenceCounted(false)
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = lock
        Log.i(TAG, "[INIT] PARTIAL_WAKE_LOCK acquis (${WAKE_LOCK_TIMEOUT_MS / 60000} min max)")
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[STOP] WakeLock release: ${e.message}")
        }
        wakeLock = null
    }

    // ---------------------------------------------------------------------
    // Maintien de trame (garde-fou 5)
    // ---------------------------------------------------------------------

    /**
     * Maintien léger de trame : postInvalidateOnAnimation() périodique pour
     * éviter que Chromium ne mette en pause requestAnimationFrame lorsque
     * l'écran physique est éteint.
     */
    private fun startFrameKeeper() {
        stopFrameKeeper()
        val intervalMs = (1000L / currentFps).coerceIn(8L, 100L)
        val runnable = object : Runnable {
            override fun run() {
                try {
                    presentation?.webView?.postInvalidateOnAnimation()
                } catch (e: Exception) {
                    // La WebView peut être en cours de destruction : on ignore.
                }
                mainHandler.postDelayed(this, intervalMs)
            }
        }
        frameKeeperRunnable = runnable
        mainHandler.postDelayed(runnable, intervalMs)
        Log.i(TAG, "[WEBVIEW] Maintien de trame actif (postInvalidateOnAnimation toutes les ${intervalMs}ms)")
    }

    private fun stopFrameKeeper() {
        frameKeeperRunnable?.let { mainHandler.removeCallbacks(it) }
        frameKeeperRunnable = null
    }

    // ---------------------------------------------------------------------
    // Relais CDP sans ADB (garde-fou 2)
    // ---------------------------------------------------------------------

    private fun startCdpProxy() {
        if (cdpAcceptThread?.isAlive == true) return
        val socketName = "webview_devtools_remote_${android.os.Process.myPid()}"
        val acceptor = Thread({
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), CDP_PROXY_PORT), CDP_BACKLOG)
                cdpServerSocket = server
                Log.i(TAG, "[CDP] Proxy TCP 127.0.0.1:9222 actif -> socket unix $socketName")
                while (!stopRequested.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        break
                    }
                    val worker = Thread({ handleCdpClient(client, socketName) }, "HyperframeCdpConn")
                    worker.isDaemon = true
                    cdpConnectionThreads.add(worker)
                    worker.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[CDP] Proxy indisponible: ${e.message}")
            }
        }, "HyperframeCdpAccept")
        acceptor.isDaemon = true
        cdpAcceptThread = acceptor
        acceptor.start()
    }

    private fun handleCdpClient(client: Socket, socketName: String) {
        val closed = AtomicBoolean(false)
        fun shutdown() {
            if (closed.compareAndSet(false, true)) {
                try { client.close() } catch (e: Exception) { /* déjà fermée */ }
            }
        }
        var local: LocalSocket? = null
        try {
            client.tcpNoDelay = true
            val ls = LocalSocket()
            local = ls
            ls.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            Log.i(TAG, "[CDP] Connexion relayée: ${client.inetAddress?.hostAddress ?: "?"}:${client.port}")

            val upstream = Thread({
                relayStream(client.getInputStream(), ls.outputStream, "cdp->webview")
                shutdown()
            }, "HyperframeCdpUp")
            val downstream = Thread({
                relayStream(ls.inputStream, client.getOutputStream(), "webview->cdp")
                shutdown()
            }, "HyperframeCdpDown")
            upstream.isDaemon = true
            downstream.isDaemon = true
            upstream.start()
            downstream.start()
        } catch (e: Exception) {
            Log.e(TAG, "[CDP] Relay impossible: ${e.message}")
            shutdown()
            try { local?.close() } catch (e2: Exception) { /* déjà fermé */ }
        }
    }

    private fun relayStream(input: InputStream, output: OutputStream, label: String) {
        val buffer = ByteArray(RELAY_BUFFER_SIZE)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: Exception) {
            // Fin de connexion ou socket cassé : terminaison normale du relay.
            Log.i(TAG, "[CDP] Relay $label terminé (${e.javaClass.simpleName})")
        }
    }

    private fun stopCdpProxy() {
        try { cdpServerSocket?.close() } catch (e: Exception) { Log.e(TAG, "[CDP] server.close: ${e.message}") }
        cdpServerSocket = null
        cdpAcceptThread = null
        cdpConnectionThreads.clear()
    }

    // ---------------------------------------------------------------------
    // Notification de premier plan
    // ---------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Rendu web GPU headless zero-copy"
        channel.setShowBadge(false)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            Intent(this, HeadlessRenderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun startForegroundIfNeeded(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationText(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "[INIT] Mise à jour notification: ${e.message}")
        }
    }

    private fun stopForegroundAndService() {
        mainHandler.post {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "[STOP] stopForeground/stopSelf: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Utilitaires
    // ---------------------------------------------------------------------

    private fun runOnMainSync(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        latch.await(RUN_ON_MAIN_SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Garde-fou 4 : jamais plus de 1920x1080 (limite mémoire Android 17). */
    private fun clampDimension(value: Int, max: Int): Int {
        val positive = if (value <= 0) max else value
        val capped = positive.coerceAtMost(max)
        return capped - (capped % 2)
    }

    private fun clampFps(value: Int): Int = if (value <= 0) DEFAULT_FPS else value.coerceAtMost(60)

    private fun computeBitrate(width: Int, height: Int): Int {
        val pixels = width * height
        return when {
            pixels <= 921_600 -> 8_000_000     // <= 720p
            pixels <= 2_073_600 -> 12_000_000  // <= 1080p
            else -> 16_000_000
        }
    }

    private fun findHardwareAvcEncoder(): MediaCodecInfo? {
        return try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder &&
                    info.isHardwareAccelerated &&
                    info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[INIT] Recherche encodeur matériel: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------
    // Constantes
    // ---------------------------------------------------------------------

    companion object {
        const val TAG = "HyperframePoC"

        const val ACTION_START = "com.hyperframe.render.action.START"
        const val ACTION_LOAD_URL = "com.hyperframe.render.action.LOAD_URL"
        const val ACTION_STOP = "com.hyperframe.render.action.STOP"

        const val EXTRA_URL = "url"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"

        const val DEFAULT_URL = "file:///android_asset/test.html"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_FPS = 60
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080

        const val OUTPUT_FILE_NAME = "hyperframe_poc.mp4"
        const val CDP_PROXY_PORT = 9222

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hyperframe_poc"
        private const val REQUEST_CODE_STOP = 2001
        private const val VIRTUAL_DISPLAY_NAME = "HyperframePoC-VirtualDisplay"
        private const val WAKE_LOCK_TAG = "HyperframePoC::HeadlessRender"
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000
        private const val ENCODER_POLL_TIMEOUT_US = 10_000L
        private const val RELAY_BUFFER_SIZE = 16 * 1024
        private const val CDP_BACKLOG = 8
        private const val STATS_INTERVAL_FRAMES = 120L
        private const val TEARDOWN_JOIN_TIMEOUT_MS = 5000L
        private const val RUN_ON_MAIN_SYNC_TIMEOUT_MS = 5000L
    }
}

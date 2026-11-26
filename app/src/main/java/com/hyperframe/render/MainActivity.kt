package com.hyperframe.render

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Phase 2 (Checkpoint 3) — panneau de contrôle persistant.
 *
 * La Phase 1 enchaînait permissions -> startForegroundService -> finish().
 * Désormais, l'activité affiche un tableau de bord XML ultra-léger
 * (ScrollView / LinearLayout / Button / EditText / RadioGroup, aucun
 * Jetpack Compose) qui :
 *
 *   1. affiche l'état du pipeline (badge ACTIF / ARRÊTÉ, compteur de
 *      frames en direct, chemin du MP4, URL en cours) rafraîchi toutes
 *      les 1 s via Handler sur l'état partagé [RenderState] (même
 *      processus, aucun Binder, aucun réseau) ;
 *   2. affiche les ports locaux (CDP 9222, API HTTP 8765) et le jeton
 *      Bearer avec un bouton Copier Token ;
 *   3. pilote HeadlessRenderService exclusivement par Intents
 *      (ACTION_START / ACTION_LOAD_URL / ACTION_STOP).
 *
 * Garde-fou 1 (inchangé) : cette interface ne touche JAMAIS à la WebView
 * ni au VirtualDisplay, qui restent 100 % dans HeadlessRenderService.
 */
class MainActivity : Activity() {

    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvStatusBadge: TextView
    private lateinit var tvTokenValue: TextView
    private lateinit var tvFrames: TextView
    private lateinit var tvMp4: TextView
    private lateinit var tvCurrentUrl: TextView
    private lateinit var etUrl: EditText
    private lateinit var rb720: RadioButton
    private lateinit var rb1080: RadioButton

    @Volatile private var cachedToken: String? = null

    /** Rafraîchissement de la télémétrie toutes les 1 s (spec Section 3.B). */
    private val ticker = object : Runnable {
        override fun run() {
            refreshDashboard()
            uiHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusBadge = findViewById(R.id.tv_status_badge)
        tvTokenValue = findViewById(R.id.tv_token_value)
        tvFrames = findViewById(R.id.tv_frames)
        tvMp4 = findViewById(R.id.tv_mp4)
        tvCurrentUrl = findViewById(R.id.tv_current_url)
        etUrl = findViewById(R.id.et_url)
        rb720 = findViewById(R.id.rb_720)
        rb1080 = findViewById(R.id.rb_1080)

        findViewById<Button>(R.id.btn_copy_token).setOnClickListener { copyToken() }
        findViewById<Button>(R.id.btn_test_asset).setOnClickListener {
            etUrl.setText(HeadlessRenderService.DEFAULT_URL)
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener { startRenderClicked() }
        findViewById<Button>(R.id.btn_load).setOnClickListener { loadUrlClicked() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { stopClicked() }
        findViewById<Button>(R.id.btn_permissions).setOnClickListener {
            permissionsAndBatteryClicked()
        }

        loadTokenAsync()
        // Demande unique et non bloquante de POST_NOTIFICATIONS (API 33+).
        requestNotificationsIfNeeded()

        Log.i(TAG, "[MAIN] Tableau de bord affiché")
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        uiHandler.postDelayed(ticker, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------------
    // Télémétrie temps réel (badge, frames, MP4, URL)
    // ------------------------------------------------------------------

    private fun refreshDashboard() {
        val running = RenderState.running

        // Le jeton est généré par le serveur HTTP du service (CP2), unique
        // écrivain ; l'UI se contente de relire le fichier tant qu'il est absent.
        if (cachedToken == null) {
            loadTokenAsync()
        }

        if (running) {
            tvStatusBadge.text = getString(R.string.status_active)
            tvStatusBadge.setBackgroundResource(R.drawable.badge_active)
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_active_text))
        } else {
            tvStatusBadge.text = getString(R.string.status_stopped)
            tvStatusBadge.setBackgroundResource(R.drawable.badge_stopped)
            tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badge_stopped_text))
        }

        tvFrames.text = getString(R.string.label_frames_template, RenderState.framesEncoded)
        tvMp4.text = getString(
            R.string.label_mp4_path_template,
            RenderState.lastFinalizedFile ?: getString(R.string.value_none)
        )
        tvCurrentUrl.text = getString(
            R.string.label_current_url_template,
            if (running) RenderState.url else getString(R.string.value_none)
        )
    }

    // ------------------------------------------------------------------
    // Contrôles -> HeadlessRenderService (Intents uniquement)
    // ------------------------------------------------------------------

    private fun selectedWidthHeight(): Pair<Int, Int> =
        if (rb1080.isChecked) 1920 to 1080 else 1280 to 720

    private fun currentUrlInput(): String {
        val raw = etUrl.text?.toString()?.trim().orEmpty()
        return raw.ifBlank { HeadlessRenderService.DEFAULT_URL }
    }

    private fun startRenderClicked() {
        val (width, height) = selectedWidthHeight()
        val intent = Intent(this, HeadlessRenderService::class.java)
            .setAction(HeadlessRenderService.ACTION_START)
            .putExtra(HeadlessRenderService.EXTRA_URL, currentUrlInput())
            .putExtra(HeadlessRenderService.EXTRA_WIDTH, width)
            .putExtra(HeadlessRenderService.EXTRA_HEIGHT, height)
        sendServiceCommand(intent, getString(R.string.toast_start_sent))
    }

    private fun loadUrlClicked() {
        if (!RenderState.running) {
            Toast.makeText(this, getString(R.string.toast_service_not_running), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, HeadlessRenderService::class.java)
            .setAction(HeadlessRenderService.ACTION_LOAD_URL)
            .putExtra(HeadlessRenderService.EXTRA_URL, currentUrlInput())
        sendServiceCommand(intent, getString(R.string.toast_load_sent))
    }

    private fun stopClicked() {
        val intent = Intent(this, HeadlessRenderService::class.java)
            .setAction(HeadlessRenderService.ACTION_STOP)
        sendServiceCommand(intent, getString(R.string.toast_stop_sent))
    }

    private fun sendServiceCommand(intent: Intent, successMessage: String) {
        try {
            startForegroundService(intent)
            Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
            refreshDashboard()
        } catch (e: Exception) {
            Log.e(TAG, "[MAIN] Commande service refusée : ${e.message}")
            Toast.makeText(this, "Commande refusée : ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Permissions & batterie
    // ------------------------------------------------------------------

    private fun permissionsAndBatteryClicked() {
        val requested = requestNotificationsIfNeeded()
        val batteryHandled = requestBatteryExemptionIfNeeded()
        if (!requested && !batteryHandled) {
            Toast.makeText(this, "Permissions déjà accordées", Toast.LENGTH_SHORT).show()
        }
    }

    /** @return true si une demande de permission vient d'être lancée. */
    private fun requestNotificationsIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "[MAIN] Demande de POST_NOTIFICATIONS")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATIONS)
            return true
        }
        return false
    }

    /** @return true si l'écran d'exemption batterie vient d'être ouvert. */
    private fun requestBatteryExemptionIfNeeded(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "[MAIN] Exemption batterie déjà accordée")
            return false
        }
        return try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
            Log.i(TAG, "[MAIN] Proposition d'exemption d'optimisation batterie affichée")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[MAIN] Exemption batterie indisponible : ${e.message}")
            false
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_NOTIFICATIONS) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[MAIN] POST_NOTIFICATIONS accordée=$granted (le rendu reste pilotable)")
    }

    // ------------------------------------------------------------------
    // Jeton Bearer (affichage + copie)
    // ------------------------------------------------------------------

    /** Lecture du jeton hors thread principal, puis affichage tronqué. */
    private fun loadTokenAsync() {
        Thread({
            val token = try {
                val file = File(filesDir, TOKEN_FILE_NAME)
                if (file.exists()) file.readText().trim().ifEmpty { null } else null
            } catch (e: Exception) {
                Log.e(TAG, "[MAIN] Lecture du jeton : ${e.message}")
                null
            }
            cachedToken = token
            runOnUiThread {
                tvTokenValue.text = token ?: getString(R.string.value_token_pending)
            }
        }, "HyperframeTokenRead").start()
    }

    private fun copyToken() {
        val token = cachedToken
        if (token.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.toast_token_missing), Toast.LENGTH_SHORT).show()
            loadTokenAsync()
            return
        }
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("hyperframe_token", token))
            Toast.makeText(this, getString(R.string.toast_token_copied), Toast.LENGTH_SHORT).show()
            Log.i(TAG, "[MAIN] Jeton copié dans le presse-papiers")
        } catch (e: Exception) {
            Log.e(TAG, "[MAIN] Copie du jeton : ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HyperframePoC"
        private const val REQUEST_CODE_NOTIFICATIONS = 1001
        private const val REFRESH_INTERVAL_MS = 1_000L
        private const val TOKEN_FILE_NAME = "auth_token.txt"
    }
}

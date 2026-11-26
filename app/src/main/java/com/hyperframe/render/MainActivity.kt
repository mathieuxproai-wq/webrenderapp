package com.hyperframe.render

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Garde-fou 6 : interface headless stricte.
 *
 * Aucune UI n'est gonflée. L'activité enchaîne uniquement :
 *   1. la vérification / demande de POST_NOTIFICATIONS (API 33+),
 *   2. la proposition de l'exemption d'optimisation batterie
 *      (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) si non accordée,
 *   3. le démarrage de HeadlessRenderService via startForegroundService(),
 *   4. l'appel à finish().
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) Permission de notification (API 33+) : demandée si absente.
        //    Le pipeline reprend dans onRequestPermissionsResult.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "[MAIN] Demande de POST_NOTIFICATIONS")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATIONS)
            return
        }
        startRenderPipeline()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_NOTIFICATIONS) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "[MAIN] POST_NOTIFICATIONS accordée=$granted (le pipeline démarre quand même)")
        startRenderPipeline()
    }

    /** 2) + 3) : exemption batterie, démarrage du service, puis 4) finish(). */
    private fun startRenderPipeline() {
        val url = intent?.getStringExtra(HeadlessRenderService.EXTRA_URL)
            ?: HeadlessRenderService.DEFAULT_URL

        val serviceIntent = Intent(this, HeadlessRenderService::class.java)
            .setAction(HeadlessRenderService.ACTION_START)
            .putExtra(HeadlessRenderService.EXTRA_URL, url)

        try {
            startForegroundService(serviceIntent)
            Log.i(TAG, "[MAIN] HeadlessRenderService démarré via startForegroundService, url=$url")
        } catch (e: Exception) {
            Log.e(TAG, "[MAIN] Échec du démarrage du service : ${e.message}", e)
        }

        requestBatteryExemptionIfNeeded()
        finish()
    }

    /** Proposition (non bloquante) de l'exemption d'optimisation batterie. */
    private fun requestBatteryExemptionIfNeeded() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "[MAIN] Exemption batterie déjà accordée")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
            Log.i(TAG, "[MAIN] Proposition d'exemption d'optimisation batterie affichée")
        } catch (e: Exception) {
            Log.e(TAG, "[MAIN] Exemption batterie indisponible : ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HyperframePoC"
        private const val REQUEST_CODE_NOTIFICATIONS = 1001
    }
}

package com.hyperframe.render

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 2 (Checkpoint 2) — serveur de contrôle HTTP local, bibliothèque
 * externe zéro (java.net.ServerSocket pur).
 *
 * Garde-fou réseau Android 17 : le serveur écoute EXCLUSIVEMENT sur
 * 127.0.0.1 (jamais 0.0.0.0), compatible avec le cloisonnement réseau
 * local (targetSdk = 36). L'autorisation est un jeton Bearer local stocké
 * dans filesDir/auth_token.txt ; seul GET /health reste ouvert.
 *
 * Endpoints JSON :
 *   GET  /health  -> {"status":"ok","service_running":bool}      (ouvert)
 *   GET  /status  -> {"running","url","fps","frames_encoded",
 *                     "cdp_port","output_file"}
 *   POST /start   -> {"url","width","height","fps"} démarrage
 *                    (garde-fou 4 : STOP interne si un MP4 est en cours)
 *   POST /load    -> {"url"} chargement à chaud dans la WebView active
 *                    sans couper le MediaMuxer
 *   POST /stop    -> {"status":"stopped","file":"..."} finalise le MP4
 *
 * Aucune opération de rendu ici : ce serveur ne fait que router des
 * commandes vers [ControlPlane], implémenté par HeadlessRenderService.
 */
class LocalControlHttpServer(
    private val context: Context,
    private val control: ControlPlane
) {

    /** Contrat minimal consommé par le serveur (implémenté par le service). */
    interface ControlPlane {
        val isRunning: Boolean
        val currentUrl: String
        val currentFps: Int
        val framesEncoded: Long
        val cdpPort: Int
        val outputFile: String?

        /**
         * Démarre le rendu (ou planifie un redémarrage après finalisation du
         * MP4 en cours, garde-fou 4). Retourne true si la commande a été
         * acceptée (démarrage direct ou planifié).
         */
        fun requestStart(url: String, width: Int, height: Int, fps: Int): Boolean

        /** Charge une URL à chaud. false si aucune WebView active. */
        fun requestLoad(url: String): Boolean

        /** Arrêt bloquant : finalise le MP4 avant de répondre. */
        fun requestStopBlocking(): StopOutcome
    }

    /** Résultat de l'arrêt bloquant (finalisation MediaMuxer incluse). */
    data class StopOutcome(
        val stopped: Boolean,
        val finalized: Boolean,
        val file: String?
    )

    private val running = AtomicBoolean(false)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private val connectionSockets = ConcurrentLinkedQueue<Socket>()
    private val connectionThreads = ConcurrentLinkedQueue<Thread>()

    /** Jeton Bearer local (généré ou relu depuis filesDir/auth_token.txt). */
    @Volatile private var token: String? = null

    // ------------------------------------------------------------------
    // Cycle de vie du serveur
    // ------------------------------------------------------------------

    /** Démarre l'accepteur (thread daemon). Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        ensureToken()
        val acceptor = Thread({
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), CONTROL_PORT),
                    CONTROL_BACKLOG
                )
                serverSocket = server
                Log.i(
                    TAG,
                    "[HTTP] Serveur de contrôle actif sur http://127.0.0.1:$CONTROL_PORT " +
                        "(auth Bearer, GET /health ouvert)"
                )
                while (running.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        break
                    }
                    serve(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[HTTP] Serveur indisponible: ${e.message}")
            } finally {
                running.set(false)
                Log.i(TAG, "[HTTP] Boucle d'acceptation terminée")
            }
        }, "HyperframeHttpAccept")
        acceptor.isDaemon = true
        acceptThread = acceptor
        acceptor.start()
    }

    /** Arrêt propre : fermeture de la socket d'écoute et des connexions. */
    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (e: Exception) {
            Log.e(TAG, "[HTTP] serverSocket.close: ${e.message}")
        }
        serverSocket = null
        acceptThread = null
        for (socket in connectionSockets) {
            try { socket.close() } catch (e: Exception) { /* déjà fermée */ }
        }
        connectionSockets.clear()
        connectionThreads.clear()
        Log.i(TAG, "[HTTP] Serveur de contrôle arrêté")
    }

    /** Jeton Bearer courant (chargé à la volée si nécessaire). */
    fun currentToken(): String {
        ensureToken()
        return token ?: ""
    }

    private fun ensureToken() {
        if (token != null) return
        synchronized(this) {
            if (token != null) return
            token = loadOrCreateToken()
        }
    }

    // ------------------------------------------------------------------
    // Jeton Bearer local (filesDir/auth_token.txt)
    // ------------------------------------------------------------------

    private fun loadOrCreateToken(): String {
        val file = File(context.filesDir, TOKEN_FILE_NAME)
        // 1) Tentative de création si absent.
        try {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                val generated = generateToken()
                file.writeText(generated)
                Log.i(TAG, "[HTTP] Token=$generated")
                Log.i(TAG, "[HTTP] Jeton généré: ${file.absolutePath}")
                return generated
            }
        } catch (e: Exception) {
            Log.e(TAG, "[HTTP] Génération du jeton: ${e.message}")
        }
        // 2) Relecture du fichier existant.
        return try {
            val existing = file.readText().trim()
            if (existing.isNotEmpty()) {
                Log.i(TAG, "[HTTP] Token=$existing")
                existing
            } else {
                throw IllegalStateException("fichier jeton vide")
            }
        } catch (e: Exception) {
            // 3) Dernier recours : régénération forcée.
            val generated = generateToken()
            try { file.writeText(generated) } catch (e2: Exception) {
                Log.e(TAG, "[HTTP] Écriture du jeton: ${e2.message}")
            }
            Log.i(TAG, "[HTTP] Token=$generated")
            generated
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    // ------------------------------------------------------------------
    // Boucle de service par connexion
    // ------------------------------------------------------------------

    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = CLIENT_TIMEOUT_MS
        connectionSockets.add(client)
        val worker = Thread({ handleClient(client) }, "HyperframeHttpConn")
        worker.isDaemon = true
        connectionThreads.add(worker)
        worker.start()
    }

    private fun handleClient(client: Socket) {
        try {
            val request = readRequest(client.getInputStream())
            val response = if (request == null) {
                Response(400, """{"error":"bad_request"}""")
            } else {
                route(request)
            }
            respond(client, response)
        } catch (e: SocketTimeoutException) {
            respond(client, Response(408, """{"error":"timeout"}"""))
        } catch (e: Exception) {
            Log.e(TAG, "[HTTP] Erreur de connexion: ${e.message}")
        } finally {
            connectionSockets.remove(client)
            try { client.close() } catch (e: Exception) { /* déjà fermée */ }
        }
    }

    private fun route(request: Request): Response {
        val (method, path, headers, body) = request

        // GET /health : unique endpoint sans authentification.
        if (method == "GET" && path == "/health") {
            val json = JSONObject()
                .put("status", "ok")
                .put("service_running", control.isRunning)
            return Response(200, json.toString())
        }

        // Authentification Bearer obligatoire pour tout le reste.
        if (!authorized(headers)) {
            Log.i(TAG, "[HTTP] 401 Unauthorized sur $method $path")
            return Response(
                401,
                """{"error":"unauthorized"}""",
                extraHeaders = "WWW-Authenticate: Bearer realm=\"hyperframe\"\r\n"
            )
        }

        return when {
            method == "GET" && path == "/status" -> statusResponse()
            method == "POST" && path == "/start" -> startResponse(body)
            method == "POST" && path == "/load" -> loadResponse(body)
            method == "POST" && path == "/stop" -> stopResponse()
            method == "GET" && path == "/token" -> tokenResponse()
            else -> Response(404, """{"error":"not_found"}""")
        }
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val header = headers["authorization"] ?: return false
        val schemeEnd = header.indexOf(' ')
        val scheme = if (schemeEnd > 0) header.substring(0, schemeEnd).trim() else header
        if (!scheme.equals("Bearer", ignoreCase = true)) return false
        val presented = if (schemeEnd > 0) header.substring(schemeEnd + 1).trim() else ""
        if (presented.isEmpty()) return false
        return MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            currentToken().toByteArray(StandardCharsets.UTF_8)
        )
    }

    // ------------------------------------------------------------------
    // Réponses des endpoints
    // ------------------------------------------------------------------

    private fun statusResponse(): Response {
        val json = JSONObject()
            .put("running", control.isRunning)
            .put("url", control.currentUrl)
            .put("fps", control.currentFps)
            .put("frames_encoded", control.framesEncoded)
            .put("cdp_port", control.cdpPort)
            .put("output_file", control.outputFile ?: "")
        return Response(200, json.toString())
    }

    private fun tokenResponse(): Response {
        val json = JSONObject()
            .put("token", currentToken())
        return Response(200, json.toString())
    }

    private fun startResponse(body: String): Response {
        if (body.isBlank()) {
            return Response(400, """{"error":"missing_body","expected":"{\"url\":\"...\",\"width\":1280,\"height\":720,\"fps\":60}"}""")
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return Response(400, """{"error":"invalid_json"}""")
        }
        val url = json.optString("url", "")
        val width = json.optInt("width", 0)
        val height = json.optInt("height", 0)
        val fps = json.optInt("fps", 0)
        val accepted = control.requestStart(url, width, height, fps)
        if (!accepted) {
            return Response(409, """{"error":"start_refused"}""")
        }
        return Response(
            200,
            JSONObject()
                .put("status", "accepted")
                .put("url", url)
                .put("width", width)
                .put("height", height)
                .put("fps", fps)
                .toString()
        )
    }

    private fun loadResponse(body: String): Response {
        if (body.isBlank()) {
            return Response(400, """{"error":"missing_body","expected":"{\"url\":\"...\"}"}""")
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return Response(400, """{"error":"invalid_json"}""")
        }
        val url = json.optString("url", "")
        if (url.isBlank()) {
            return Response(400, """{"error":"missing_url"}""")
        }
        return if (control.requestLoad(url)) {
            Response(200, JSONObject().put("status", "loading").put("url", url).toString())
        } else {
            Response(409, """{"error":"no_active_webview"}""")
        }
    }

    private fun stopResponse(): Response {
        val outcome = control.requestStopBlocking()
        return if (outcome.stopped) {
            Response(
                200,
                JSONObject()
                    .put("status", if (outcome.finalized) "stopped" else "idle")
                    .put("file", outcome.file ?: "")
                    .toString()
            )
        } else {
            Response(500, """{"error":"stop_timeout"}""")
        }
    }

    // ------------------------------------------------------------------
    // Analyse HTTP minimale (une requête par connexion, Connection: close)
    // ------------------------------------------------------------------

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class Response(
        val status: Int,
        val payload: String,
        val extraHeaders: String = ""
    )

    private fun readRequest(input: InputStream): Request? {
        val headerBuffer = ByteArrayOutputStream()
        var state = 0 // 0 = aucun CR, 1 = CR, 2 = CRLF, 3 = CRLF+CR
        while (state != 4 && headerBuffer.size() < MAX_HEADER_BYTES) {
            val byte = input.read()
            if (byte == -1) return null
            headerBuffer.write(byte)
            state = when (byte) {
                CR -> if (state == 0 || state == 2) state + 1 else 0
                LF -> if (state == 1) state + 1 else if (state == 3) 4 else 0
                else -> 0
            }
        }
        if (state != 4) return null

        val headerText = String(headerBuffer.toByteArray(), StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLineParts = lines[0].trim().split(" ")
        if (requestLineParts.size < 2) return null
        val method = requestLineParts[0].uppercase()
        val target = requestLineParts[1]
        val path = target.substringBefore('?')

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size - 1) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] =
                line.substring(separator + 1).trim()
        }

        val contentLength = headers["content-length"]?.toIntOrNull()
            ?.coerceIn(0, MAX_BODY_BYTES) ?: 0
        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(bodyBytes, read, contentLength - read)
                if (count < 0) break
                read += count
            }
            String(bodyBytes, 0, read, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return Request(method, path, headers, body)
    }

    private fun respond(client: Socket, response: Response) {
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val payloadBytes = response.payload.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${payloadBytes.size}\r\n" +
            response.extraHeaders +
            "Connection: close\r\n" +
            "\r\n"
        try {
            val output = client.getOutputStream()
            output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
            output.write(payloadBytes)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "[HTTP] Écriture de la réponse: ${e.message}")
        }
    }

    companion object {
        const val TAG = "HyperframePoC"

        /** Port de contrôle fixé par la spec Phase 2. */
        const val CONTROL_PORT = 8765

        private const val CONTROL_BACKLOG = 8
        private const val TOKEN_FILE_NAME = "auth_token.txt"
        private const val TOKEN_BYTES = 24 // 48 caractères hexadécimaux
        private const val CLIENT_TIMEOUT_MS = 15_000
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 1 * 1024 * 1024
        private const val CR = '\r'.code
        private const val LF = '\n'.code
    }
}

# AGENT_SPEC_PHASE1.md — Génération et Compilation du PoC (Rendu Web GPU Headless Zero-Copy - Android 17 / SDK 36)

## 1. CONTEXTE D'EXÉCUTION & RÔLE DE L'AGENT
* **Environnement de l'agent :** IDE sous Ubuntu avec **Gradle et le SDK Android 36 disponibles pour la compilation**, mais **sans appareil Android ni émulateur connecté**.
* **Rôle exclusif de l'agent :**
  1. Écrire l'intégralité des fichiers sources du projet Android (Gradle, Manifest, ressources XML, Kotlin, et `assets/test.html`) en respectant scrupuleusement les 8 garde-fous (Section 3).
  2. Lancer la compilation locale (`./gradlew assembleDebug`) et corriger toutes les erreurs de compilation ou de lint jusqu'à obtenir un fichier **`app-debug.apk` valide**.
* **INTERDICTION D'EXÉCUTER OU TESTER L'APK :** Ne tente **jamais** d'installer ou d'exécuter l'APK (`adb install`, émulateur, tests instrumentés ou Harness Mobile). L'utilisateur récupérera l'APK compilé et effectuera tous les tests manuellement sur son appareil physique Android 17.

**⚠️ POINT D'ARRÊT OBLIGATOIRE :** Dès que `./gradlew assembleDebug` réussit (`BUILD SUCCESSFUL`) et que le chemin vers `app-debug.apk` est généré, **arrête-toi immédiatement**, affiche le chemin exact de l'APK ainsi que la **Checklist de Test Manuel (Section 6)** à l'attention de l'utilisateur.

---

## 2. ARCHITECTURE TECHNIQUE IMPOSÉE

```text
[PROOT / TERMUX (glibc) sur l'appareil Android 17]
  ├── Serveur Web (Vite/Remotion) : http://127.0.0.1:3000
  └── Contrôleur Puppeteer/CDP    : ws://127.0.0.1:9222
          │
==========│==================== (Frontière Processus Android)
          ▼
[APK COMPAGNON ANDROID - HeadlessRenderService]
  ├── Proxy TCP (127.0.0.1:9222) <──> Socket Unix (localabstract:webview_devtools_remote_<PID>)
  ├── Presentation (attachée au VirtualDisplay privé)
  │     └── WebView (View.LAYER_TYPE_HARDWARE, View.VISIBLE)
  ├── VirtualDisplay (VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, sans flag PUBLIC)
  │     └── Surface cible = mediaCodec.createInputSurface()
  └── MediaCodec (video/avc Hardware) ──> MediaMuxer ──> Download/hyperframe_poc.mp4
```

---

## 3. GARDE-FOUS NON NÉGOCIABLES (ANTI-DÉRIVE)

Tout code violant l'une des 8 règles suivantes est **immédiatement invalide** :

1. **INTERDICTION ABSOLUE DU READBACK CPU :**
   * **Interdit :** `Bitmap`, `PixelCopy`, `webView.capturePicture()`, `Canvas` logiciel, `glReadPixels`, `ByteBuffer` en entrée de `MediaCodec`, ou `page.screenshot()` dans la boucle vidéo.
   * **Obligatoire :** L'unique lien entre le rendu et l'encodeur est `val inputSurface = mediaCodec.createInputSurface()` passé en argument à `displayManager.createVirtualDisplay(...)`.
2. **ZÉRO DÉPENDANCE EXTERNE (PORTABILITÉ TOTALE) :**
   * **Interdit :** ADB, Wireless Debugging, Shizuku, `su` (root), ou `MediaProjection` (qui demande une autorisation écran à chaque lancement).
   * **Obligatoire :** Utiliser un `VirtualDisplay` privé avec le drapeau `DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` (aucune permission système requise).
3. **UN SEUL MOTEUR DE RENDU :**
   * **Interdit :** Compiler Chromium, importer GeckoView, Crosswalk ou FFmpeg.
   * **Obligatoire :** Utiliser uniquement `android.webkit.WebView` natif avec `WebView.setWebContentsDebuggingEnabled(true)` et `setLayerType(View.LAYER_TYPE_HARDWARE, null)`.
4. **COMPATIBILITÉ ANDROID 17 & SDK 36 :**
   * **SDK :** Fixer `compileSdk = 36` et `targetSdk = 36` (avec `minSdk = 29`) pour utiliser le SDK 36 local et éviter le blocage réseau local introduit au SDK 37 (`ACCESS_LOCAL_NETWORK`), même à l'exécution sur Android 17.
   * **Réseau local :** Créer `res/xml/network_security_config.xml` autorisant le trafic HTTP clair **uniquement** sur `127.0.0.1` et `localhost`.
   * **Service :** Déclarer `android:foregroundServiceType="specialUse"` (avec la balise `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Offscreen GPU Web Rendering" />` requise par Android 14+) dans `AndroidManifest.xml`, et démarrer le service depuis `MainActivity` au premier plan avant d'appeler `finish()`.
   * **Mémoire (App Memory Limits Android 17) :** Ne pas dépasser `1920x1080` pour le `VirtualDisplay` et appeler `webView.clearCache(true)` au démarrage.
5. **ANTI-GEL ÉCRAN ÉTEINT :**
   * Acquérir un `PowerManager.PARTIAL_WAKE_LOCK` dans le `ForegroundService` (et le relâcher proprement dans `onDestroy()`).
   * Maintenir la `WebView` à `View.VISIBLE` à l'intérieur de la `Presentation`.
   * Ajouter un mécanisme léger de maintien de trame (ex. `webView.postInvalidateOnAnimation()` périodique ou pilotage actif) pour éviter que Chromium ne mette en pause `requestAnimationFrame` lorsque l'écran physique est éteint.
6. **AUCUNE INTERFACE UTILISATEUR (HEADLESS STRICT) :**
   * `MainActivity` doit uniquement : vérifier/demander `POST_NOTIFICATIONS` (et proposer l'exemption batterie `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` si non accordée), lancer `HeadlessRenderService` via `startForegroundService()`, puis appeler `finish()`.
7. **PÉRIMÈTRE STRICT DU POC :**
   * Pas d'UI Jetpack Compose de réglage, pas de gestionnaire de fichiers, pas de streaming réseau RTMP/WebRTC en Phase 1. Uniquement l'écriture locale d'un fichier `.mp4` via `MediaMuxer` et l'exposition du port CDP `9222`.
8. **ZÉRO RECHERCHE EXTERNE / ZÉRO DIVAGATION :**
   * Ne pas chercher d'alternatives d'architecture sur le web. Toutes les API Android requises sont définies dans ce document. Écris des fichiers complets, sans `// TODO` ni code tronqué. En cas d'erreur `./gradlew assembleDebug`, analyse uniquement le message d'erreur local et corrige le code en respectant ces règles.

---

## 4. ARBORESCENCE DES FICHIERS À PRODUIRE ET COMPILER

L'agent doit créer ou mettre à jour l'ensemble de ces fichiers puis lancer `./gradlew assembleDebug` :

1. **`settings.gradle.kts` & `build.gradle.kts` (projet et module `:app`) :**
   * Package : `com.hyperframe.render`
   * `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29`
   * Dépendances minimales : `androidx.core:core-ktx` uniquement (pas de Jetpack Compose ni de bibliothèques tierces lourdes).
2. **`app/src/main/res/xml/network_security_config.xml` :**
   * Autorise `cleartextTrafficPermitted="true"` uniquement pour `127.0.0.1` et `localhost`.
3. **`app/src/main/AndroidManifest.xml` :**
   * Permissions : `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
   * Déclaration de `android:networkSecurityConfig="@xml/network_security_config"`.
   * Déclaration de `MainActivity` (`exported="true"`, thème translucide/sans barre d'action) et `HeadlessRenderService` (`foregroundServiceType="specialUse"`, `exported="true"`, avec la propriété `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`).
4. **`app/src/main/assets/test.html` :**
   * Page autonome HTML5 + Canvas 2D / WebGL affichant une animation fluide à 60 FPS, un compteur de frames en temps réel, un chronomètre en millisecondes et un titre `<title>Hyperframe PoC Ready</title>` (permet de tester immédiatement sans serveur externe).
5. **`app/src/main/java/com/hyperframe/render/MainActivity.kt` :**
   * Demande `POST_NOTIFICATIONS` (API 33+) et l'exemption d'optimisation batterie, démarre `HeadlessRenderService` avec l'URL par défaut `file:///android_asset/test.html` (ou extra `url` s'il est fourni), puis appelle `finish()`.
6. **`app/src/main/java/com/hyperframe/render/HeadlessRenderService.kt` :**
   * **Gestion des commandes Intent :**
     * Action par défaut / `START` : démarre le pipeline sur l'extra `url` (défaut : `file:///android_asset/test.html`, résolution défaut : `1280x720`, `60` fps).
     * Action `LOAD_URL` : charge une nouvelle URL à chaud dans la WebView active sans couper l'enregistrement.
     * Action `STOP` : arrête proprement la `Presentation`, le `VirtualDisplay`, draine l'EOS (`signalEndOfInputStream()`) de `MediaCodec`, ferme proprement `MediaMuxer` pour que le MP4 soit lisible, libère le `WakeLock` et appelle `stopSelf()`.
   * **Pipeline Vidéo Zero-Copy :** `MediaCodec` (`video/avc`, `COLOR_FormatSurface`) → `inputSurface` → `DisplayManager.createVirtualDisplay` (`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`) → `Presentation` → `WebView` (`LAYER_TYPE_HARDWARE`).
   * **Écriture MP4 :** Enregistrement dans le dossier public Téléchargements (`Environment.DIRECTORY_DOWNLOADS`) sous le nom `hyperframe_poc.mp4` via `MediaStore.Downloads` (API 29+) afin de garantir la compatibilité totale avec le *Scoped Storage* d'Android 17 sans demander de permission de stockage.
   * **Relais CDP sans ADB :** `ServerSocket` écoutant exclusivement sur `127.0.0.1:9222` et relayant bidirectionnellement les octets sur des threads dédiés vers le `LocalSocket` abstrait `webview_devtools_remote_${android.os.Process.myPid()}`.

---

## 5. LOGS DE DIAGNOSTIC OBLIGATOIRES (POUR LECTURE LOGCAT PAR L'UTILISATEUR)

Toutes les traces doivent utiliser le tag unique **`HyperframePoC`** afin que l'utilisateur puisse filtrer facilement avec `logcat -s HyperframePoC` :
* `Log.i("HyperframePoC", "[INIT] MediaCodec configuré (${width}x${height} @ ${fps}fps), inputSurface valide=${inputSurface.isValid}")`
* `Log.i("HyperframePoC", "[VD] VirtualDisplay créé, displayId=${virtualDisplay.display.displayId}")`
* `Log.i("HyperframePoC", "[WEBVIEW] Presentation affichée, isHardwareAccelerated=${webView.isHardwareAccelerated}, url=$targetUrl")`
* `Log.i("HyperframePoC", "[CDP] Proxy TCP 127.0.0.1:9222 actif -> socket unix webview_devtools_remote_$pid")`
* `Log.i("HyperframePoC", "[MUXER] Piste vidéo démarrée, écriture vers $outputUriOrPath")`
* `Log.i("HyperframePoC", "[STATS] Frames encodées: $frameCount")` (toutes les 120 frames)
* `Log.i("HyperframePoC", "[STOP] MediaMuxer et MediaCodec fermés proprement. Fichier finalisé.")`

---

## 6. PROTOCOLE DE TEST MANUEL (EXÉCUTÉ PAR L'UTILISATEUR SUR ANDROID 17)
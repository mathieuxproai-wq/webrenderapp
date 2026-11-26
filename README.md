# webrenderapp — Hyperframe PoC

Preuve de concept de **rendu vidéo headless** : une `WebView` Android rendue sur un `VirtualDisplay` **privé**, encodée en temps réel en MP4 via `MediaCodec`/`MediaMuxer`, pilotable à distance via le protocole **CDP** (Chrome DevTools Protocol).

## Architecture

- `HeadlessRenderService` (service foreground `specialUse`) orchestre le pipeline :
  1. `MediaCodec` (H.264, 1280×720 @ 60 fps) + son `inputSurface`
  2. `VirtualDisplay` **privé** (`OWN_CONTENT_ONLY`) attaché à la surface de l'encodeur
  3. `Presentation` contenant une `WebView` **accélération matérielle** (`LAYER_TYPE_HARDWARE`)
  4. `MediaMuxer` écrivant dans `MediaStore.Downloads` (scoped storage, zéro permission stockage)
  5. Relais TCP `127.0.0.1:9222` → socket Unix `webview_devtools_remote_<pid>` (Puppeteer/CDP)
- `MainActivity` est un **tableau de bord de contrôle** (Phase 2) : badge ACTIF/ARRÊTÉ, compteur de frames en direct, chemin du MP4, URL en cours, choix de résolution 720p/1080p — elle pilote le service **exclusivement par Intents** et ne touche jamais à la WebView ni au VirtualDisplay.

## API de contrôle locale (Phase 2)

`LocalControlHttpServer` écoute **exclusivement sur `127.0.0.1:8765`** (jamais `0.0.0.0`), compatible avec le cloisonnement réseau local d'Android 17. Authentification : jeton Bearer généré au démarrage du service (`filesDir/auth_token.txt`, copiable depuis le tableau de bord) ; seul `GET /health` est ouvert.

| Endpoint | Corps | Effet |
|---|---|---|
| `GET /health` | — | `{"status":"ok","service_running":bool}` (ouvert, sans jeton) |
| `GET /status` | — | état du pipeline : `running`, `url`, `fps`, `frames_encoded`, `cdp_port`, `output_file` |
| `POST /start` | `{"url","width","height","fps"}` | démarre le rendu (finalise proprement l'ancien MP4 si besoin — garde-fou 4) |
| `POST /load` | `{"url"}` | chargement à chaud dans la WebView active, sans couper le MediaMuxer |
| `POST /stop` | — | arrêt **bloquant** : finalise le MP4 avant de répondre |

```bash
TOKEN=$(cat /data/data/com.hyperframe.render/files/auth_token.txt)
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/status
```

## Garde-fous respectés (Section 3 de la spec)

- Zéro readback CPU : aucun `Bitmap`, `PixelCopy`, `glReadPixels` ni `MediaProjection` — le flux reste 100 % matériel.
- Zéro ADB : tout fonctionne sur l'appareil, seul.
- `compileSdk` / `targetSdk` = 36, `minSdk` = 29.
- `network_security_config` limité à `127.0.0.1` / `localhost` (cleartext uniquement vers ces hôtes).
- Enregistrement dans `MediaStore.Downloads` uniquement.

## Build

```bash
./gradlew assembleDebug
```

APK produit : `app/build/outputs/apk/debug/app-debug.apk` (~3.8 Mo).

Prérequis : JDK 17, Android SDK 36, AGP 8.11.0, Kotlin 1.9.22.

## Observation des logs

```bash
logcat -s HyperframePoC
```

Les 7 logs obligatoires de la Section 5 de la spec : `[MAIN]`, `[INIT]`, `[VD]`, `[WEBVIEW]`, `[MUXER]`, `[CDP]`, `[STATS]`, plus `[STOP]` à l'arrêt propre.

## Test manuel

Voir [`docs/MANUAL_TEST_CHECKLIST.md`](docs/MANUAL_TEST_CHECKLIST.md) et la spec complète ([`docs/AGENT_SPEC_PHASE1.md`](docs/AGENT_SPEC_PHASE1.md)).

## Avertissement

PoC expérimental : non destiné à la production. L'encodage 1080p60 en permanence consomme batterie et thermique — prévoir un appareil alimenté et un arrêt via le bouton **ARRÊTER & FINALISER MP4** du tableau de bord, l'endpoint `POST /stop`, ou l'action `STOP` du service (finalisation propre du MP4).

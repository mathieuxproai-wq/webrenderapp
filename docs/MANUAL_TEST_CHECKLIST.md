# Checklist de test manuel (protocole Section 6)

À exécuter sur un appareil Android physique. Filtrez logcat avec :

```bash
logcat -s HyperframePoC
```

## A. Installation & premiers pas
- [ ] **A1** — `adb install app-debug.apk` (ou sideload via gestionnaire de fichiers).
- [ ] **A2** — Lancer l'app « Hyperframe PoC » → la notification système de `POST_NOTIFICATIONS` apparaît → accepter.
- [ ] **A3** — L'app **se ferme immédiatement** (Activity `finish()`) mais une **notification foreground** persiste : c'est le service.
- [ ] **A4** — Vérifier dans logcat : `[MAIN] HeadlessRenderService démarré via startForegroundService, url=file:///android_asset/test.html`

## B. Pipeline vidéo (les 7 logs)
- [ ] **B1** — `[INIT] MediaCodec configuré (1280x720 @ 60fps), inputSurface valide=true`
- [ ] **B2** — `[VD] VirtualDisplay créé, displayId=<non-nul>`
- [ ] **B3** — `[WEBVIEW] Presentation affichée, isHardwareAccelerated=true, url=file:///android_asset/test.html`
- [ ] **B4** — `[MUXER] Piste vidéo démarrée, écriture vers content://...` (URI MediaStore)
- [ ] **B5** — `[CDP] Proxy TCP 127.0.0.1:9222 actif -> socket unix webview_devtools_remote_<pid>`
- [ ] **B6** — Attendre ~2 min → `[STATS] Frames encodées: 120` puis 240, 360… (**cadence régulière = pas de drop**)

## C. Test du contenu rendu (test.html embarqué)
- [ ] **C1** — Le HTML de test (compteur de frames + chronomètre à 60 FPS) est bien rendu dans le flux vidéo (constatable à la lecture du MP4).

## D. Relais CDP (depuis Termux/PRoot sur l'appareil)
- [ ] **D1** — `curl http://127.0.0.1:9222/json` → renvoie la liste des targets WebView (le proxy TCP → socket Unix fonctionne).
- [ ] **D2** — Connecter Puppeteer sur `ws://127.0.0.1:9222` et évaluer `document.title` → `"Hyperframe PoC Ready"`.

## E. Fichier MP4 final
- [ ] **E1** — Le fichier existe dans `Téléchargements/hyperframe_poc.mp4` (via `MediaStore.Downloads`).
- [ ] **E2** — Le MP4 est **lisible** (pas de « fin non finalisée ») : la durée correspond au temps d'enregistrement.
- [ ] **E3** — Envoyer le MP4 sur un PC et vérifier que la vidéo est fluide à 60 FPS (pas de cadre gelé).

## F. Action STOP & nettoyage
- [ ] **F1** — Envoyer un `Intent` avec action `STOP` (via `am startservice` ou depuis Termux) → dans logcat : `[STOP] MediaMuxer et MediaCodec fermés proprement. Fichier finalisé.`
- [ ] **F2** — Le WakeLock est relâché (pas de flag `WAKE_LOCK` résiduel dans `dumpsys power`).
- [ ] **F3** — Le service s'arrête (`stopSelf`) → la notification foreground disparaît.

## G. Anti-gel écran éteint (test critique)
- [ ] **G1** — Lancer l'enregistrement, puis **éteindre l'écran physique** pendant 1 min.
- [ ] **G2** — Revenir dans logcat : les `[STATS]` continuent d'incrémenter toutes les 120 frames (le `PARTIAL_WAKE_LOCK` + `postInvalidateOnAnimation` maintiennent les `requestAnimationFrame`).
- [ ] **G3** — Arrêter et relire le MP4 : la portion « écran éteint » n'est ni gelée ni accélérée.

## Dépannage

- **Pas de `[VD]`** → le pipeline ne s'est pas lancé : vérifier que le service foreground est bien démarré (étape A3).
- **`[STATS]` gelé** → écran éteint mais rAF suspendu : vérifier le WakeLock (`dumpsys power`).
- **MP4 illisible** → le signal EOS n'a pas été drainé : vérifier que l'arrêt a bien été fait via l'action `STOP` (pas un kill direct du service).

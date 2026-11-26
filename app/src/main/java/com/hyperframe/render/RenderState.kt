package com.hyperframe.render

/**
 * Phase 2 (Checkpoint 3) — état partagé service ↔ UI.
 *
 * HeadlessRenderService et MainActivity vivent dans le même processus :
 * ce simple objet volatile sert de source de vérité pour le tableau de
 * bord, rafraîchi toutes les 1 s par le Handler de MainActivity (aucun
 * réseau, aucun Binder, aucune lecture du pipeline zero-copy).
 */
object RenderState {

    /** Pipeline zero-copy actif (WebView + VirtualDisplay + encodeur). */
    @Volatile var running: Boolean = false

    /** URL actuellement chargée dans la WebView. */
    @Volatile var url: String = ""

    /** FPS configurés du pipeline en cours. */
    @Volatile var fps: Int = 0

    /** Compteur de frames encodées en direct (mis à jour par le drain). */
    @Volatile var framesEncoded: Long = 0

    /** MP4 en cours d'écriture (uri MediaStore IS_PENDING=1). */
    @Volatile var outputFile: String? = null

    /** Dernier MP4 proprement finalisé (IS_PENDING=0). */
    @Volatile var lastFinalizedFile: String? = null
}

package org.jetbrains.skiko.context

import java.awt.Graphics

/**
 * The AWT destination a software render context blits its CPU-rendered frame onto.
 *
 * This captures exactly the presentation-side state the software path used to read off `SkiaLayer`
 * (`backedLayer.getGraphics()`, `width`/`height`, `fullscreen`, `transparency`). Extracting it behind
 * this interface decouples the handler from the view; the redrawer that owns the layer supplies
 * an implementation. Properties are read each frame, so they track the live layer state.
 */
internal interface SoftwareDrawTarget {
    /** The AWT graphics to draw the rendered image onto, or `null` when unavailable. */
    val graphics: Graphics?

    /** Destination width in (unscaled) component pixels. */
    val width: Int

    /** Destination height in (unscaled) component pixels. */
    val height: Int

    /** Whether the target is currently in fullscreen mode. */
    val isFullscreen: Boolean

    /** Whether the target requests a transparent background. */
    val isTransparent: Boolean
}

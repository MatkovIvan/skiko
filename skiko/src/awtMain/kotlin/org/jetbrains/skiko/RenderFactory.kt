package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.Redrawer

/**
 * Creates the on-screen [Redrawer] for a [SkiaPanel] / [GraphicsApi]. Used by [SkiaPanel]'s
 * render-API fallback chain and as the injection point for tests.
 *
 * Keyed to [SkiaPanel] (the AWT engine base) rather than the deprecated `SkiaLayer`, so the engine is
 * shared by `SkiaPanel` and any subclass (e.g. the deprecated `SkiaLayer`). AWT-only — other JVM
 * targets (Android) drive rendering through their own view, not this factory.
 */
internal fun interface RenderFactory {
    fun createRedrawer(
        layer: SkiaPanel,
        renderApi: GraphicsApi,
        analytics: SkiaLayerAnalytics,
        properties: SkiaLayerProperties
    ): Redrawer

    companion object {
        val Default = makeDefaultRenderFactory()
    }
}

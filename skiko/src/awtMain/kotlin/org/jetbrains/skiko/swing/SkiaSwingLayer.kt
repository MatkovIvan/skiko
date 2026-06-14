package org.jetbrains.skiko.swing

import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.RenderFactory
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.SkiaRenderMode
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Component
import javax.accessibility.AccessibleContext

/**
 * Swing component that draws content provided by [renderDelegate] with GPU acceleration, composited by
 * Swing (z-order, double-buffering, interop).
 *
 * Deprecated: it is now a thin shim that just pins [SkiaPanel] to
 * [SkiaRenderMode.SwingComposited][SkiaRenderMode.SwingComposited] — the SwingComposited render mode is a
 * parameter of the one [SkiaPanel] component, not a separate component. Everything (push `present`,
 * `clipComponents`, `renderApi`, `eventSurface`, …) is inherited unchanged.
 */
@Suppress("unused") // used in Compose Multiplatform
@ExperimentalSkikoApi
@Deprecated(
    message = "Deprecated in favor of SkiaPanel(renderMode = SkiaRenderMode.SwingComposited).",
    level = DeprecationLevel.WARNING,
)
open class SkiaSwingLayer(
    renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
    accessibleContextProvider: ((Component) -> AccessibleContext)? = null,
    properties: SkiaLayerProperties = SkiaLayerProperties()
) : SkiaPanel(
    accessibleContextProvider,
    properties,
    RenderFactory.Default,
    analytics,
    PixelGeometry.UNKNOWN,
    SkiaRenderMode.SwingComposited,
) {
    init {
        this.renderDelegate = renderDelegate
    }
}

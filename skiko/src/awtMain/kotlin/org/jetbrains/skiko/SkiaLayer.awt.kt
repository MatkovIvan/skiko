package org.jetbrains.skiko

import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.redrawer.RenderContextProvider
import java.awt.Component
import javax.accessibility.AccessibleContext

/**
 * Deprecated heavyweight on-screen skiko component. It is now a thin shim over [SkiaPanel], which owns
 * the GPU surface + render engine; everything except [needRedraw] is inherited. Prefer [SkiaPanel] and
 * its push [SkiaPanel.present] API.
 */
@Deprecated(
    message = "Deprecated in favor of the render-context API. On AWT use SkiaPanel; on other platforms use RenderContext with a caller-owned view.",
    level = DeprecationLevel.WARNING,
)
@Suppress("DEPRECATION")
actual open class SkiaLayer internal constructor(
    accessibleContextProvider: ((Component) -> AccessibleContext)? = null,
    properties: SkiaLayerProperties,
    renderContextProvider: RenderContextProvider = RenderContextProvider.Default,
    analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
    pixelGeometry: PixelGeometry = PixelGeometry.UNKNOWN,
) : SkiaPanel(accessibleContextProvider, properties, renderContextProvider, analytics, pixelGeometry) {

    constructor(
        accessibleContextProvider: ((Component) -> AccessibleContext)? = null,
        isVsyncEnabled: Boolean = SkikoProperties.vsyncEnabled,
        isVsyncFramelimitFallbackEnabled: Boolean = SkikoProperties.vsyncFramelimitFallbackEnabled,
        frameBuffering: FrameBuffering = SkikoProperties.frameBuffering,
        renderApi: GraphicsApi = SkikoProperties.renderApi,
        analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
        pixelGeometry: PixelGeometry = PixelGeometry.UNKNOWN,
    ) : this(
        accessibleContextProvider,
        SkiaLayerProperties(
            isVsyncEnabled,
            isVsyncFramelimitFallbackEnabled,
            frameBuffering,
            renderApi
        ),
        RenderContextProvider.Default,
        analytics,
        pixelGeometry
    )

    constructor(
        accessibleContextProvider: ((Component) -> AccessibleContext)? = null,
        properties: SkiaLayerProperties,
        analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
        pixelGeometry: PixelGeometry = PixelGeometry.UNKNOWN,
    ) : this(
        accessibleContextProvider,
        properties,
        RenderContextProvider.Default,
        analytics,
        pixelGeometry
    )

    // Explicit override (not a fake override): the expect's needRender has a default argument, which
    // Kotlin disallows actualizing via inheritance.
    actual override fun needRender(throttledToVsync: Boolean) {
        super.needRender(throttledToVsync)
    }

    @Deprecated(
        message = "Use needRender() instead",
        replaceWith = ReplaceWith("needRender()")
    )
    actual fun needRedraw() = needRender()
}

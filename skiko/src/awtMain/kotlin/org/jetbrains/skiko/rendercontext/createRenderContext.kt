package org.jetbrains.skiko.rendercontext

import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.hostOs

/**
 * Builds the [AwtRenderContext] for [renderApi] on the current OS, or throws
 * [org.jetbrains.skiko.RenderException] if that API cannot be initialised. Delegates to the view-decoupled
 * [createRenderContext] overload through the layer's [SkiaLayer.surfaceHost]. [SkiaLayer] reaches this through its
 * injectable [org.jetbrains.skiko.RenderFactory] so tests can force a backend.
 */
internal fun createRenderContext(
    layer: SkiaLayer,
    renderApi: GraphicsApi,
    properties: SkiaLayerProperties,
): AwtRenderContext = createRenderContext(layer.surfaceHost, renderApi, properties)

/**
 * The genuinely view-decoupled construction seam: build exactly [renderApi] for the decoupled [host], or throw
 * [org.jetbrains.skiko.RenderException]. The single place the per-OS/API backend table lives; the per-API
 * contexts depend only on [AwtSurfaceHost], so this is what both the on-screen [SkiaLayer] pull loop and the
 * push-only `SkiaPanel` presenter build on — neither needs a concrete [SkiaLayer]. The per-API constructors
 * load their own native libraries.
 */
internal fun createRenderContext(
    host: AwtSurfaceHost,
    renderApi: GraphicsApi,
    properties: SkiaLayerProperties,
): AwtRenderContext = when (hostOs) {
    OS.MacOS -> when (renderApi) {
        GraphicsApi.SOFTWARE_COMPAT, GraphicsApi.SOFTWARE_FAST -> SoftwareRenderContext(host, properties)
        else -> MetalRenderContext(host, properties)
    }
    OS.Windows -> when (renderApi) {
        GraphicsApi.SOFTWARE_COMPAT -> SoftwareRenderContext(host, properties)
        GraphicsApi.SOFTWARE_FAST -> WindowsSoftwareRenderContext(host, properties)
        GraphicsApi.OPENGL -> WindowsOpenGLRenderContext(host, properties)
        GraphicsApi.ANGLE -> AngleRenderContext(host, properties)
        else -> Direct3DRenderContext(host, properties)
    }
    OS.Linux -> when (renderApi) {
        GraphicsApi.SOFTWARE_COMPAT -> SoftwareRenderContext(host, properties)
        GraphicsApi.SOFTWARE_FAST -> LinuxSoftwareRenderContext(host, properties)
        else -> LinuxOpenGLRenderContext(host, properties)
    }
    else -> throw UnsupportedOperationException("AWT doesn't support $hostOs")
}

package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.hostOs

/**
 * Builds the [AWTRedrawer] for [renderApi] on the current OS, or throws
 * [org.jetbrains.skiko.RenderException] if that API cannot be initialised. Delegates to the view-decoupled
 * [createRedrawer] overload through the layer's [SkiaLayer.surfaceHost]. [SkiaLayer] reaches this through its
 * injectable [org.jetbrains.skiko.RenderFactory] so tests can force a backend.
 */
internal fun createRedrawer(
    layer: SkiaLayer,
    renderApi: GraphicsApi,
    properties: SkiaLayerProperties,
): AWTRedrawer = createRedrawer(layer.surfaceHost, renderApi, properties)

/**
 * The genuinely view-decoupled construction seam: build exactly [renderApi] for the decoupled [host], or throw
 * [org.jetbrains.skiko.RenderException]. The single place the per-OS/API backend table lives; the per-API
 * contexts depend only on [AwtSurfaceHost], so this is what both the on-screen [SkiaLayer] pull loop and the
 * push-only `SkiaPanel` presenter build on — neither needs a concrete [SkiaLayer]. The per-API constructors
 * load their own native libraries.
 */
internal fun createRedrawer(
    host: AwtSurfaceHost,
    renderApi: GraphicsApi,
    properties: SkiaLayerProperties,
): AWTRedrawer = when (hostOs) {
    OS.MacOS -> when (renderApi) {
        GraphicsApi.SOFTWARE_COMPAT, GraphicsApi.SOFTWARE_FAST -> SoftwareRedrawer(host, properties)
        else -> MetalRedrawer(host, properties)
    }
    OS.Windows -> when (renderApi) {
        GraphicsApi.SOFTWARE_COMPAT -> SoftwareRedrawer(host, properties)
        GraphicsApi.SOFTWARE_FAST -> WindowsSoftwareRedrawer(host, properties)
        GraphicsApi.OPENGL -> WindowsOpenGLRedrawer(host, properties)
        GraphicsApi.ANGLE -> AngleRedrawer(host, properties)
        else -> Direct3DRedrawer(host, properties)
    }
    OS.Linux -> when (renderApi) {
        GraphicsApi.SOFTWARE_COMPAT -> SoftwareRedrawer(host, properties)
        GraphicsApi.SOFTWARE_FAST -> LinuxSoftwareRedrawer(host, properties)
        else -> LinuxOpenGLRedrawer(host, properties)
    }
    else -> throw UnsupportedOperationException("AWT doesn't support $hostOs")
}

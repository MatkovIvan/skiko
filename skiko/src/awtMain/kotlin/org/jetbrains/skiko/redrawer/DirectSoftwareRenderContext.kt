@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.context.rasterizeFrame
import org.jetbrains.skiko.hostArch
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.layerFrameLimiter
import java.lang.ref.Reference

/**
 * The direct-software (CPU, blit-straight-to-window) on-screen [AwtRenderContext] for Linux/Windows. It
 * **owns** the native device (a window-backed raster target) created by the platform subclass
 * ([LinuxDirectSoftwareRenderContext]/[WindowsDirectSoftwareRenderContext]); [acquireSurface] resizes and
 * hands back the device's raster [Surface], and [present] blits it straight to the window. No GPU context
 * ([directContext] is `null`).
 *
 * On Linux every native call runs inside `lockLinuxDrawingSurface { }`; the Linux subclass wraps
 * [renderFrame]/[close] in that lock. Former `DirectSoftwareContextHandler` + `AbstractDirectSoftwareRedrawer`,
 * folded together.
 */
internal abstract class DirectSoftwareRenderContext(
    protected val layer: SkiaPanel,
    private val properties: SkiaLayerProperties,
) : AwtRenderContext {
    /** The native window-backed software device; set by the platform subclass during construction. */
    protected var device = 0L

    private var surface: Surface? = null
    private var currentWidth = 0
    private var currentHeight = 0

    private val frameJob = Job()
    private val frameLimiter = layerFrameLimiter(CoroutineScope(frameJob), layer.requireBackedLayer)

    final override val graphicsApi: GraphicsApi get() = GraphicsApi.SOFTWARE_FAST
    final override val directContext: DirectContext? get() = null
    final override val deviceName: String get() = "Software"
    final override val renderInfo: String get() = rendererInfo()

    override fun isTransparentBackgroundSupported(): Boolean = defaultIsTransparentBackgroundSupported(layer.fullscreen)

    override suspend fun paceBeforeFrame() {
        if (properties.isVsyncEnabled && properties.isVsyncFramelimitFallbackEnabled) {
            frameLimiter.awaitNextFrame()
        }
    }

    override suspend fun renderFrame(width: Int, height: Int, immediate: Boolean, render: (Canvas) -> Unit) {
        if (width > 0 && height > 0) {
            val surface = acquireSurface(width, height)
            surface.canvas.rasterizeFrame { render(this) }
            present()
        }
    }

    final override fun acquireSurface(width: Int, height: Int): Surface {
        if (width <= 0 || height <= 0) throw RenderException("Direct software surface needs a positive size (${width}x$height)")
        if (surface == null || width != currentWidth || height != currentHeight) {
            disposeSurface()
            currentWidth = width
            currentHeight = height
            resize(device, width, height)
            val surfacePtr = acquireSurface(device)
            if (surfacePtr == 0L) throw RenderException("Failed to create Surface")
            surface = Surface(surfacePtr)
        }
        return surface!!
    }

    final override fun present() {
        val surface = surface ?: return
        try {
            finishFrame(device, getPtr(surface))
        } finally {
            Reference.reachabilityFence(surface)
        }
    }

    override fun close() {
        frameJob.cancel()
        disposeSurface()
        disposeDevice(device)
        device = 0L
    }

    fun rendererInfo(): String =
        "GraphicsApi: ${GraphicsApi.SOFTWARE_FAST}\n" +
            "OS: ${hostOs.id} ${hostArch.id}\n"

    private fun disposeSurface() {
        surface?.close()
        surface = null
    }

    private external fun resize(devicePtr: Long, width: Int, height: Int)
    private external fun acquireSurface(devicePtr: Long): Long
    private external fun finishFrame(devicePtr: Long, surfacePtr: Long)
    private external fun disposeDevice(devicePtr: Long)
}

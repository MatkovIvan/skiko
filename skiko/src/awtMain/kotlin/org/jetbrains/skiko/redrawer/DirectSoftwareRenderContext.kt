@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.hostArch
import org.jetbrains.skiko.hostOs
import java.lang.ref.Reference

/**
 * The direct-software (CPU, blit-straight-to-window) [RenderContext] for AWT on-screen rendering on
 * Linux/Windows. It **owns** the native device (a window-backed raster target) created by the platform
 * subclass ([LinuxDirectSoftwareRenderContext]/[WindowsDirectSoftwareRenderContext]); [acquireSurface]
 * resizes and hands back the device's raster [Surface], and [present] blits it straight to the window.
 * No GPU context ([directContext] is `null`).
 *
 * Standalone — once the platform subclass has created the device, the factory can drive it directly. On
 * Linux every native call must run inside `lockLinuxDrawingSurface { }`; that scope is provided by the
 * driver/`SkiaPanel` frame loop ([org.jetbrains.skiko.redrawer.LinuxSoftwareRedrawer]).
 *
 * Former `DirectSoftwareContextHandler` + the device half of `AbstractDirectSoftwareRedrawer`, folded
 * together.
 */
internal abstract class DirectSoftwareRenderContext : RenderContext {
    /** The native window-backed software device; set by the platform subclass during construction. */
    protected var device = 0L

    private var surface: Surface? = null
    private var currentWidth = 0
    private var currentHeight = 0

    final override val graphicsApi: GraphicsApi get() = GraphicsApi.SOFTWARE_FAST
    final override val directContext: DirectContext? get() = null

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

    final override fun close() {
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

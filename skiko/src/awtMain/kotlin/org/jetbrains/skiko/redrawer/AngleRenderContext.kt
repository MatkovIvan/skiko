@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame

/**
 * The ANGLE (GL-on-Direct3D) [RenderContext] for AWT on-screen rendering. It **owns** the ANGLE device
 * created from the window handle, makes its GL context current, holds the Skia [DirectContext]/[Surface],
 * and swaps. Standalone — creatable from `(layer, properties)` (the factory prerequisite). The ANGLE
 * library must already be loaded ([loadAngleLibrary], done by the driver before construction).
 *
 * Former `AngleContextHandler`, with the device lifecycle folded in from `AngleRedrawer`.
 */
internal class AngleRenderContext(
    private val layer: SkiaPanel,
    properties: SkiaLayerProperties,
    private val pixelGeometry: PixelGeometry = layer.pixelGeometry,
) : AwtRenderContext {
    private val isVsyncEnabled = properties.isVsyncEnabled
    private val drawLock = Any()
    private var _device: Long = layer.requireBackedLayer.useDrawingSurfacePlatformInfo { platformInfo ->
        createAngleDevice(platformInfo, layer.transparency).takeIf { it != 0L }
            ?: throw RenderException("Failed to create ANGLE device.")
    }
    private val device: Long
        get() = _device.takeIf { it != 0L } ?: throw RenderException("ANGLE device is disposed")

    private val gpuResourceCacheLimit = properties.gpuResourceCacheLimit
    private var context: DirectContext? = null
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var currentWidth = 0
    private var currentHeight = 0

    init {
        makeCurrent(device)
        adapterName.also {
            if (it != null && !isVideoCardSupported(GraphicsApi.ANGLE, hostOs, it)) {
                throw RenderException("Cannot create ANGLE redrawer.")
            }
        }
    }

    val adapterName: String? get() = AngleApi.glGetString(AngleApi.GL_RENDERER)

    override val graphicsApi: GraphicsApi get() = GraphicsApi.ANGLE
    override val directContext: DirectContext? get() = context
    override val deviceName: String? get() = adapterName
    override val renderInfo: String get() = rendererInfo()
    override fun isTransparentBackgroundSupported(): Boolean = defaultIsTransparentBackgroundSupported(layer.fullscreen)

    override suspend fun renderFrame(width: Int, height: Int, immediate: Boolean, render: (Canvas) -> Unit) = synchronized(drawLock) {
        if (width > 0 && height > 0) {
            val surface = acquireSurface(width, height)
            surface.canvas.rasterizeFrame { render(this) }
            present()
            swap(if (immediate) SkikoProperties.windowsWaitForVsyncOnRedrawImmediately else isVsyncEnabled)
        }
    }

    /** Make the ANGLE GL context current; used by the driver around a frame. */
    fun makeCurrent() = makeCurrent(device)

    /** Present the swap chain (the GL flush is [present]); paced per-window. */
    fun swap(withVsync: Boolean) = swapBuffers(device, withVsync)

    override fun acquireSurface(width: Int, height: Int): Surface {
        makeCurrent(device)
        val context = context ?: DirectContext(
            makeAngleContext(device).takeIf { it != 0L } ?: throw RenderException("Failed to make GL context.")
        ).also {
            context = it
            if (gpuResourceCacheLimit >= 0) it.resourceCacheLimit = gpuResourceCacheLimit
        }
        if (surface == null || width != currentWidth || height != currentHeight) {
            disposeSurface()
            context.flush()
            currentWidth = width
            currentHeight = height
            renderTarget = BackendRenderTarget(
                makeAngleRenderTarget(device, width, height).takeIf { it != 0L }
                    ?: throw RenderException("Failed to make ANGLE render target.")
            )
            surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = pixelGeometry)
            ) ?: throw RenderException("Cannot create ANGLE surface")
        }
        return surface!!
    }

    override fun present() {
        context?.flush()
    }

    override fun close() {
        makeCurrent(device)
        disposeSurface()
        context?.close()
        context = null
        disposeDevice(device)
        _device = 0L
    }

    fun rendererInfo(): String =
        "GraphicsApi: ${GraphicsApi.ANGLE}\n" +
            "OS: ${hostOs.id} ${hostArch.id}\n" +
            "Vendor: ${AngleApi.glGetString(AngleApi.GL_VENDOR)}\n" +
            "Model: ${AngleApi.glGetString(AngleApi.GL_RENDERER)}\n" +
            "Version: ${AngleApi.glGetString(AngleApi.GL_VERSION)}\n"

    private fun disposeSurface() {
        surface?.close()
        surface = null
        renderTarget?.close()
        renderTarget = null
    }

    private external fun createAngleDevice(platformInfo: Long, transparency: Boolean): Long
    private external fun makeCurrent(device: Long)
    private external fun makeAngleContext(device: Long): Long
    private external fun makeAngleRenderTarget(device: Long, width: Int, height: Int): Long
    private external fun swapBuffers(device: Long, waitForVsync: Boolean)
    private external fun disposeDevice(device: Long)
}

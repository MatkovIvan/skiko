@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame
import javax.swing.SwingUtilities.convertPoint
import javax.swing.SwingUtilities.getRootPane
import javax.swing.SwingUtilities.isEventDispatchThread

/**
 * Holder for the pointer to a native MetalDevice (see "MetalDevice.h"): the [CAMetalLayer], [MTLDevice],
 * command queue, etc.
 *
 * @see "src/awtMain/objectiveC/macos/MetalDevice.h"
 */
@JvmInline
internal value class MetalDevice(val ptr: Long)

/**
 * The on-screen Metal [RenderContext] for AWT: a per-API GPU surface bound to [layer]'s heavyweight window.
 * It **owns** the `MetalDevice` created from the window handle, the Skia [DirectContext]/[Surface], the
 * per-frame drawable + swap/present, and the window-occlusion signal. Geometry/visibility are pushed in via
 * [resize]/[setVisible]; the frame loop + vsync pacing live in the driver.
 *
 * @see "src/awtMain/objectiveC/macos/MetalRenderContext.mm" / "MetalRedrawer.mm" — native implementation
 */
internal class MetalRenderContext(
    private val layer: SkiaPanel,
    properties: SkiaLayerProperties,
    private val pixelGeometry: PixelGeometry = layer.pixelGeometry,
) : AwtRenderContext {

    internal val adapter: MetalAdapter = chooseMetalAdapter(properties.adapterPriority)

    private val vSyncer = if (properties.isVsyncEnabled) MetalVSyncer(layer.windowHandle) else null
    private val drawLock = Any()

    private var _device: MetalDevice? = run {
        val numberOfBuffers = properties.frameBuffering.numberOfBuffers() ?: 0 // zero means default for system
        val device = layer.requireBackedLayer.useDrawingSurfacePlatformInfo {
            MetalDevice(createMetalDevice(layer.windowHandle, layer.transparency, numberOfBuffers, adapter.ptr, it))
        }
        setDisplaySyncEnabled(device.ptr, properties.isVsyncEnabled)
        device
    }
    private val device: MetalDevice
        get() = _device ?: throw RenderException("Metal device is disposed")

    private val gpuResourceCacheLimit = properties.gpuResourceCacheLimit
    private var context: DirectContext? = null
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null

    // Window occlusion: the native layer signals it here (see onOcclusionStateChanged); the driver reads
    // [isWindowOccluded] / drains [occlusionChannel] to back off when the window is hidden.
    internal val occlusionChannel = Channel<Boolean>(Channel.CONFLATED)
    @Volatile internal var isWindowOccluded = false
        private set

    override val graphicsApi: GraphicsApi get() = GraphicsApi.METAL
    override val directContext: DirectContext? get() = context
    override val deviceName: String? get() = adapter.name
    override val renderInfo: String get() = rendererInfo()
    override fun isTransparentBackgroundSupported(): Boolean = defaultIsTransparentBackgroundSupported(layer.fullscreen)

    // Metal splits non-vsync-throttled updates onto a separate executor for input latency.
    override val separatesUpdateAndDraw: Boolean get() = true

    internal val metalDeviceObjcPtr: Long get() = getMetalDevicePointer(device.ptr)
    internal val metalCommandQueueObjcPtr: Long get() = getMetalCommandQueuePointer(device.ptr)

    override suspend fun renderFrame(width: Int, height: Int, immediate: Boolean, render: (Canvas) -> Unit) {
        if (immediate) {
            performFrame(width, height, render)
            // Drawing immediately in Metal loses frames if there are >2 between consecutive vsyncs.
            if (SkikoProperties.macOSWaitForPreviousFrameVsyncOnRedrawImmediately) {
                vSyncer?.waitForVSync()
            }
        } else {
            // Move drawing off the EDT to keep FPS stable (see SkiaLayerPerformanceTest).
            withContext(dispatcherToBlockOn) {
                performFrame(width, height, render)
            }
            // When the window is occluded, don't redraw fast (battery).
            if (isWindowOccluded) {
                withTimeoutOrNull(300) {
                    @Suppress("ControlFlowWithEmptyBody")
                    while (occlusionChannel.receive()) { }
                }
            }
        }
    }

    private fun performFrame(width: Int, height: Int, render: (Canvas) -> Unit) = synchronized(drawLock) {
        if (width > 0 && height > 0) {
            autoreleasepool {
                val surface = acquireSurface(width, height)
                surface.canvas.rasterizeFrame { render(this) }
                present()
            }
        }
    }

    override suspend fun paceAfterFrame() {
        vSyncer?.waitForVSync()
    }

    override fun acquireSurface(width: Int, height: Int): Surface {
        val context = context ?: DirectContext(makeMetalContext(device.ptr)).also {
            context = it
            if (gpuResourceCacheLimit >= 0) it.resourceCacheLimit = gpuResourceCacheLimit
        }
        disposeSurface()
        if (width > 0 && height > 0) {
            val target = BackendRenderTarget(makeMetalRenderTarget(device.ptr, width, height))
            renderTarget = target
            surface = Surface.makeFromBackendRenderTarget(
                context,
                target,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = pixelGeometry)
            ) ?: throw RenderException("Cannot create Metal surface")
        }
        return surface ?: throw RenderException("Cannot create Metal surface (${width}x$height)")
    }

    override fun present() {
        context?.flush()
        surface?.flushAndSubmit()
        finishFrame(device.ptr)
        Logger.debug { "MetalRenderContext finished drawing frame" }
    }

    /** Position+size the heavyweight Metal drawable in the window from the layer's current AWT bounds. */
    override fun syncBounds() = synchronized(drawLock) {
        check(isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        val rootPane = getRootPane(layer)
        val globalPosition = convertPoint(layer.requireBackedLayer, 0, 0, rootPane)
        val x = globalPosition.x
        val y = rootPane.height - globalPosition.y - layer.height
        val width = layer.requireBackedLayer.width.coerceAtLeast(0)
        val height = layer.requireBackedLayer.height.coerceAtLeast(0)
        Logger.debug { "MetalRenderContext#syncBounds $this {x: $x y: $y width: $width height: $height} rootPane: ${rootPane.size}" }
        setContentScale(device.ptr, layer.contentScale)
        resizeLayers(device.ptr, x, y, width, height)
    }

    override fun setVisible(isVisible: Boolean) {
        Logger.debug { "MetalRenderContext#setVisible($isVisible)" }
        setLayerVisible(device.ptr, isVisible)
    }

    // Called from MetalRenderContext.mm / MetalRedrawer.mm
    @Suppress("unused")
    fun onOcclusionStateChanged(isOccluded: Boolean) {
        isWindowOccluded = isOccluded
        occlusionChannel.trySend(isOccluded)
    }

    fun rendererInfo(): String =
        "GraphicsApi: ${GraphicsApi.METAL}\n" +
            "OS: ${hostOs.id} ${hostArch.id}\n" +
            "Video card: ${adapter.name}\n" +
            "Total VRAM: ${adapter.memorySize / 1024 / 1024} MB\n"

    override fun close() {
        disposeSurface()
        context?.close()
        context = null
        _device?.let { disposeDevice(it.ptr) }
        adapter.dispose()
        _device = null
        vSyncer?.dispose()
    }

    private fun disposeSurface() {
        surface?.close()
        surface = null
        renderTarget?.close()
        renderTarget = null
    }

    private external fun createMetalDevice(window: Long, transparency: Boolean, frameBuffering: Int, adapter: Long, platformInfo: Long): Long
    private external fun disposeDevice(device: Long)
    private external fun resizeLayers(device: Long, x: Int, y: Int, width: Int, height: Int)
    private external fun setLayerVisible(device: Long, isVisible: Boolean)
    private external fun setContentScale(device: Long, contentScale: Float)
    private external fun setDisplaySyncEnabled(device: Long, enabled: Boolean)
    private external fun makeMetalContext(device: Long): Long
    private external fun makeMetalRenderTarget(device: Long, width: Int, height: Int): Long
    private external fun finishFrame(device: Long)
    private external fun getMetalDevicePointer(device: Long): Long
    private external fun getMetalCommandQueuePointer(device: Long): Long
}

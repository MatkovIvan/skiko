@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.withContext
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame
import java.lang.ref.Reference

/**
 * The Direct3D 12 [RenderContext] for AWT on-screen rendering (Windows only). It **owns** the D3D device +
 * swap chain (created from the window content handle), the Skia [DirectContext], the double-buffered
 * [Surface]s, and the present/swap. Standalone — creatable from `(layer, properties)` (the factory
 * prerequisite). D3D can't render at zero size, so [acquireSurface] coerces to 1×1.
 *
 * Former `Direct3DContextHandler` + the device half of `Direct3DRedrawer`, folded together.
 */
internal class Direct3DRenderContext(
    private val layer: SkiaPanel,
    private val properties: SkiaLayerProperties,
    private val pixelGeometry: PixelGeometry = layer.pixelGeometry,
) : AwtRenderContext {
    private val drawLock = Any()
    val adapterName: String
    val adapterMemorySize: Long
    private var _device: Long

    init {
        val adapter = chooseAdapter(properties.adapterPriority.ordinal)
        if (adapter == 0L) throw RenderException("Failed to choose DirectX12 adapter.")
        adapterName = getAdapterName(adapter)
        adapterMemorySize = getAdapterMemorySize(adapter)
        _device = createDirectXDevice(adapter, layer.contentHandle, layer.transparency)
            .takeIf { it != 0L } ?: throw RenderException("Failed to create DirectX12 device.")
    }

    private val device: Long
        get() = _device.takeIf { it != 0L } ?: throw RenderException("DirectX12 device is disposed")

    private var isSwapChainInitialized = false
    private var context: DirectContext? = null
    private val bufferCount = 2
    private val surfaces: Array<Surface?> = arrayOfNulls(bufferCount)
    private var currentWidth = 0
    private var currentHeight = 0

    override val graphicsApi: GraphicsApi get() = GraphicsApi.DIRECT3D
    override val directContext: DirectContext? get() = context
    override val deviceName: String get() = adapterName
    override val renderInfo: String get() = rendererInfo()
    override fun isTransparentBackgroundSupported(): Boolean = defaultIsTransparentBackgroundSupported(layer.fullscreen)

    override suspend fun renderFrame(width: Int, height: Int, immediate: Boolean, render: (Canvas) -> Unit) {
        if (immediate) {
            performFrame(width, height, render, SkikoProperties.windowsWaitForVsyncOnRedrawImmediately)
        } else {
            // Move drawing off the EDT to keep FPS stable.
            withContext(dispatcherToBlockOn) {
                performFrame(width, height, render, properties.isVsyncEnabled)
            }
        }
    }

    private fun performFrame(width: Int, height: Int, render: (Canvas) -> Unit, withVsync: Boolean) = synchronized(drawLock) {
        // No size guard: D3D coerces to 1×1 in acquireSurface and runs the whole pipeline.
        val surface = acquireSurface(width, height)
        surface.canvas.rasterizeFrame { render(this) }
        present()
        swap(withVsync)
    }

    internal val direct3DAdapterPtr: Long get() = getDirectXAdapterPointer(device)
    internal val direct3DDevicePtr: Long get() = getDirectXDevicePointer(device)
    internal val direct3DQueuePtr: Long get() = getDirectXQueuePointer(device)

    private fun isSurfacesNull() = surfaces.all { it == null }

    override fun acquireSurface(width: Int, height: Int): Surface {
        val context = context ?: DirectContext(makeDirectXContext(device)).also {
            context = it
            if (properties.gpuResourceCacheLimit >= 0) it.resourceCacheLimit = properties.gpuResourceCacheLimit
        }
        // Direct3D can't work with zero size; run the whole pipeline at 1×1 instead of skipping.
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (w != currentWidth || h != currentHeight || isSurfacesNull()) {
            currentWidth = w
            currentHeight = h
            disposeSurfaces()
            context.flush()
            val justInitialized = changeSize(w, h)
            try {
                val surfaceProps = SurfaceProps(pixelGeometry = pixelGeometry)
                for (bufferIndex in 0 until bufferCount) {
                    surfaces[bufferIndex] = makeSurface(getPtr(context), w, h, surfaceProps, bufferIndex)
                }
            } finally {
                Reference.reachabilityFence(context)
            }
            if (justInitialized) initFence(device)
        }
        return surfaces[getBufferIndex(device)]!!
    }

    override fun present() {
        val context = context ?: return
        val surface = surfaces[getBufferIndex(device)] ?: return
        try {
            flush(getPtr(context), getPtr(surface))
        } finally {
            Reference.reachabilityFence(context)
            Reference.reachabilityFence(surface)
        }
    }

    /** Present the swap chain (the GPU flush is [present]); paced per-window. */
    fun swap(withVsync: Boolean) {
        if (isSwapChainInitialized) swap(device, withVsync)
    }

    override fun close() {
        disposeSurfaces()
        context?.close()
        context = null
        disposeDevice(device)
        _device = 0L
    }

    fun rendererInfo(): String =
        "GraphicsApi: ${GraphicsApi.DIRECT3D}\n" +
            "OS: ${hostOs.id} ${hostArch.id}\n" +
            "Video card: $adapterName\n" +
            "Total VRAM: ${adapterMemorySize / 1024 / 1024} MB\n"

    private fun changeSize(width: Int, height: Int): Boolean {
        return if (!isSwapChainInitialized) {
            initSwapChain(device, width, height, layer.transparency)
            isSwapChainInitialized = true
            true
        } else {
            resizeBuffers(device, width, height)
            false
        }
    }

    private fun makeSurface(context: Long, width: Int, height: Int, surfaceProps: SurfaceProps, index: Int): Surface {
        return interopScope {
            Surface(makeDirectXSurface(device, context, width, height, toInterop(surfaceProps.packToIntArray()), index))
        }
    }

    private fun disposeSurfaces() {
        for (bufferIndex in 0 until bufferCount) {
            surfaces[bufferIndex]?.close()
            surfaces[bufferIndex] = null
        }
    }

    // Called from native code
    @Suppress("unused")
    private fun isAdapterSupported(name: String) = isVideoCardSupported(GraphicsApi.DIRECT3D, hostOs, name)

    private external fun getDirectXAdapterPointer(device: Long): Long
    private external fun getDirectXDevicePointer(device: Long): Long
    private external fun getDirectXQueuePointer(device: Long): Long
    private external fun chooseAdapter(adapterPriority: Int): Long
    private external fun createDirectXDevice(adapter: Long, contentHandle: Long, transparency: Boolean): Long
    private external fun makeDirectXContext(device: Long): Long
    private external fun makeDirectXSurface(device: Long, context: Long, width: Int, height: Int, surfacePropsIntArray: InteropPointer, index: Int): Long
    private external fun resizeBuffers(device: Long, width: Int, height: Int)
    private external fun swap(device: Long, isVsyncEnabled: Boolean)
    private external fun disposeDevice(device: Long)
    private external fun getBufferIndex(device: Long): Int
    private external fun initSwapChain(device: Long, width: Int, height: Int, transparency: Boolean)
    private external fun initFence(device: Long)
    private external fun getAdapterName(adapter: Long): String
    private external fun getAdapterMemorySize(adapter: Long): Long
    private external fun flush(context: Long, surface: Long)
}

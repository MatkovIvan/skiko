package org.jetbrains.skiko.redrawer

import kotlinx.coroutines.withContext
import org.jetbrains.skia.*
import org.jetbrains.skia.impl.InteropPointer
import org.jetbrains.skia.impl.getPtr
import org.jetbrains.skia.impl.interopScope
import org.jetbrains.skiko.*
import java.lang.ref.Reference

/**
 * The single per-window Direct3D on-screen render context ([AWTRedrawer]): it owns the native DirectX12
 * device/adapter and swap chain lifecycle, the Skia [DirectContext] and double-buffered on-screen GPU
 * surfaces for the current frame, and the present/swap (with vsync fused into the swap). The frame loop
 * itself lives in the generic [OnScreenRedrawer].
 *
 * This class name is part of its statically name-mangled JNI symbols. The Kotlin class name and the exported native symbols are one unit: renaming either alone unbinds
 * them, and the failure surfaces as an UnsatisfiedLinkError at the first native call, not as a
 * compile error.
 *
 * Content to draw is provided by [AwtSurfaceHost.draw].
 *
 * @see "src/awtMain/cpp/windows/direct3DContext.cc" -- native GPU surface implementation
 * @see "src/awtMain/cpp/windows/directXRedrawer.cc" -- native device/swap chain implementation
 */
internal class Direct3DRedrawer(
    private val host: AwtSurfaceHost,
    private val properties: SkiaLayerProperties
) : AWTRedrawer {

    /**
     * Guards every native/JNI touch point: device+adapter lifetime, the swap chain, the Skia
     * [DirectContext]/surfaces, and presentation. [dispose] takes this lock before releasing any native
     * resource, and the per-frame render path ([drawAndSwap]) takes the *same* lock and re-checks
     * [isDisposed] *inside* it before making any native call. Frames render off the EDT (see [renderFrame],
     * which hops onto [dispatcherToBlockOn]) while [dispose] can be invoked from the EDT concurrently, so
     * without this the two could interleave and [dispose] could free the native device out from under an
     * in-flight JNI call (native use-after-free). Because both sides serialize on [drawLock], whichever runs
     * first fully completes before the other proceeds, and the loser always observes the up-to-date
     * [isDisposed] state.
     */
    private val drawLock = Any()

    @Volatile
    private var isDisposed = false
    private var isSwapChainInitialized = false

    private var device: Long = 0L
        get() {
            if (field == 0L) {
                throw RenderException("DirectX12 device is not initialized or already disposed")
            }
            return field
        }

    val adapterName: String
    val adapterMemorySize: Long

    override val graphicsApi: GraphicsApi get() = GraphicsApi.DIRECT3D
    override val deviceName: String?
    override val directContext: DirectContext? get() = context

    /**
     * The `IDXGIAdapter1` skiko renders on, as a native pointer. Backs the public
     * [org.jetbrains.skiko.direct3DAdapterPointer] GPU-interop accessor. Read it under [drawLock] and after
     * re-checking [isDisposed], so it can never race [dispose] freeing the native device.
     *
     * @throws IllegalStateException if this context has been disposed.
     */
    internal val direct3DAdapterPtr: Long
        get() = synchronized(drawLock) {
            check(!isDisposed) { "Direct3DRedrawer is disposed" }
            getDirectXAdapterPointer(device)
        }

    /**
     * The `ID3D12Device` skiko renders on, as a native pointer. Backs the public
     * [org.jetbrains.skiko.direct3DDevicePointer] GPU-interop accessor. Same locking/lifetime discipline as
     * [direct3DAdapterPtr].
     *
     * @throws IllegalStateException if this context has been disposed.
     */
    internal val direct3DDevicePtr: Long
        get() = synchronized(drawLock) {
            check(!isDisposed) { "Direct3DRedrawer is disposed" }
            getDirectXDevicePointer(device)
        }

    /**
     * The `ID3D12CommandQueue` skiko submits its frames on, as a native pointer. Backs the public
     * [org.jetbrains.skiko.direct3DQueuePointer] GPU-interop accessor. Same locking/lifetime discipline as
     * [direct3DAdapterPtr].
     *
     * @throws IllegalStateException if this context has been disposed.
     */
    internal val direct3DQueuePtr: Long
        get() = synchronized(drawLock) {
            check(!isDisposed) { "Direct3DRedrawer is disposed" }
            getDirectXQueuePointer(device)
        }

    init {
        val adapter = chooseAdapter(properties.adapterPriority.ordinal)
        if (adapter == 0L) {
            throw RenderException("Failed to choose DirectX12 adapter.")
        }
        adapterName = getAdapterName(adapter)
        adapterMemorySize = getAdapterMemorySize(adapter)
        deviceName = adapterName
        device = createDirectXDevice(adapter, host.contentHandle, host.transparency)
            .takeIf { it != 0L } ?: throw RenderException("Failed to create DirectX12 device.")
    }

    override val renderInfo: String
        get() = renderInfoHeader(host.renderApi) +
                "Video card: $adapterName\n" +
                "Total VRAM: ${adapterMemorySize / 1024 / 1024} MB\n"

    override fun isTransparentBackgroundSupported(): Boolean = defaultIsTransparentBackgroundSupported(host)

    // GPU surfaces for the current frame.
    // Only ever touched under `drawLock`.
    private var context: DirectContext? = null
    private val bufferCount = 2
    private val surfaces: Array<Surface?> = arrayOfNulls(bufferCount)
    /** The back buffer the current frame draws into and flushes; picked once per frame by [initSurface]. */
    private var surface: Surface? = null
    private var canvas: Canvas? = null
    private var currentWidth = 0
    private var currentHeight = 0
    private fun isSurfacesNull() = surfaces.all { it == null }

    override fun dispose() = synchronized(drawLock) {
        isDisposed = true
        disposeSurfaces()
        context?.close()
        context = null
        disposeDevice(device)
        device = 0L
    }

    override suspend fun renderFrame(scope: LayerDrawScope, immediate: Boolean) {
        if (immediate) {
            drawAndSwap(scope, withVsync = SkikoProperties.windowsWaitForVsyncOnRedrawImmediately)
        } else {
            withContext(dispatcherToBlockOn) {
                drawAndSwap(scope, withVsync = properties.isVsyncEnabled)
            }
        }
    }

    private fun drawAndSwap(scope: LayerDrawScope, withVsync: Boolean) = synchronized(drawLock) {
        // Re-check inside the lock (not just at the call site): this is what makes `dispose` and an
        // in-flight frame mutually exclusive rather than merely racing on `isDisposed`.
        if (isDisposed) {
            return
        }
        with(scope) { drawFrame() }
        swap(withVsync)
    }

    override fun acquireSurface(width: Int, height: Int): Surface = synchronized(drawLock) {
        check(!isDisposed) { "Direct3DRedrawer is disposed" }
        if (!ensureContext()) {
            throw RenderException("Cannot init graphic Direct3D context")
        }
        createSurface(width, height, host.pixelGeometry)
        // Capture the frame's back buffer, exactly as the on-screen path does in `initSurface` and for the
        // same reason: `getBufferIndex` advances the swap chain and waits on the buffer's fence, so it runs
        // once per frame and [present] must flush the surface it returned, not call it again.
        surface = surfaces[getBufferIndex(device)]
        surface ?: throw RenderException("Cannot create surface for ${width}x$height")
    }

    override fun present() = synchronized(drawLock) {
        if (!isDisposed) {
            flushFrame()
            swap(properties.isVsyncEnabled)
        }
    }

    private fun LayerDrawScope.drawFrame() {
        if (!ensureContext()) {
            throw RenderException("Cannot init graphic Direct3D context")
        }
        initSurface()
        canvas?.runRestoringState {
            clear(Color.TRANSPARENT)
            host.draw(this)
        }
        flushFrame()
    }

    private fun ensureContext(): Boolean {
        if (context == null) {
            try {
                val newContext = DirectContext(makeDirectXContext(device))
                context = newContext
                onContextInitialized(newContext, properties.gpuResourceCacheLimit) { renderInfo }
            } catch (e: Exception) {
                Logger.warn(e) { "Failed to create Skia Direct3D context!" }
                return false
            }
        }
        return true
    }

    private fun LayerDrawScope.initSurface() = createSurface(scaledLayerWidth, scaledLayerHeight, pixelGeometry)

    private fun createSurface(rawWidth: Int, rawHeight: Int, pixelGeometry: PixelGeometry) {
        val context = context ?: return

        // Direct3D can't work with zero size.
        // Don't rewrite code to skipping, as we need the whole pipeline in zero case too
        // (drawing -> flushing -> swapping -> waiting for vsync)
        val width = rawWidth.coerceAtLeast(1)
        val height = rawHeight.coerceAtLeast(1)

        if (isSizeChanged(width, height) || isSurfacesNull()) {
            disposeSurfaces()
            context.flush()

            val justInitialized = changeSize(width, height)
            try {
                val surfaceProps = SurfaceProps(pixelGeometry = pixelGeometry)
                for (bufferIndex in 0 until bufferCount) {
                    surfaces[bufferIndex] = makeSurface(
                        context = getPtr(context),
                        width = width,
                        height = height,
                        surfaceProps = surfaceProps,
                        index = bufferIndex
                    )
                }
            } finally {
                Reference.reachabilityFence(context)
            }

            if (justInitialized) {
                initFence(device)
            }
        }
        // Capture the frame's back buffer once. `getBufferIndex` is not a getter: each call advances the
        // swap chain's buffer index, blocks until that buffer's GPU fence completes, and then bumps the
        // fence value. Exactly one call per frame is what `swap`'s matching Signal balances, so a second
        // call would wait on a fence value nothing has signalled yet and block forever.
        surface = surfaces[getBufferIndex(device)]
        canvas = surface!!.canvas
    }

    private fun isSizeChanged(width: Int, height: Int): Boolean {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            return true
        }
        return false
    }

    private fun flushFrame() {
        val context = context ?: return
        val surface = surface ?: return
        try {
            flush(getPtr(context), getPtr(surface))
        } finally {
            Reference.reachabilityFence(context)
            Reference.reachabilityFence(surface)
        }
    }

    private fun disposeSurfaces() {
        for (bufferIndex in 0 until bufferCount) {
            surfaces[bufferIndex]?.close()
            surfaces[bufferIndex] = null
        }
        surface = null
        canvas = null
    }

    private fun makeSurface(context: Long, width: Int, height: Int, surfaceProps: SurfaceProps, index: Int): Surface {
        return interopScope {
            Surface(makeDirectXSurface(device, context, width, height, toInterop(surfaceProps.packToIntArray()), index))
        }
    }

    private fun changeSize(width: Int, height: Int): Boolean {
        return if (!isSwapChainInitialized) {
            initSwapChain(device, width, height, host.transparency)
            isSwapChainInitialized = true
            true
        } else {
            resizeBuffers(device, width, height)
            false
        }
    }

    private fun swap(withVsync: Boolean) {
        if (!isSwapChainInitialized) {
            return
        }
        swap(device, withVsync)
    }

    // Called from native code
    private fun isAdapterSupported(name: String) = isVideoCardSupported(GraphicsApi.DIRECT3D, hostOs, name)

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

    // Native GPU surface flush; implemented in direct3DContext.cc.
    private external fun flush(context: Long, surface: Long)

    // GPU-interop handle getters: read the IDXGIAdapter1/ID3D12Device/ID3D12CommandQueue address out of the
    // native DirectXDevice struct. Implemented in directXRedrawer.cc.
    private external fun getDirectXAdapterPointer(device: Long): Long
    private external fun getDirectXDevicePointer(device: Long): Long
    private external fun getDirectXQueuePointer(device: Long): Long
}

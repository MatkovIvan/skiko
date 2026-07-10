package org.jetbrains.skiko.rendercontext

import org.jetbrains.skia.*
import org.jetbrains.skiko.*

/**
 * The single per-window ANGLE (EGL-over-D3D11) on-screen render context ([AwtRenderContext]): it owns the
 * native ANGLE device lifecycle, the Skia [DirectContext] and on-screen GPU surface for the current frame,
 * and the present/swap (with vsync in the swap). The frame loop itself lives in the generic
 * [OnScreenRenderer].
 *
 * Its native methods are top-level `external fun`s, so their JNI symbols live on the file facade
 * (`Java_org_jetbrains_skiko_rendercontext_AngleRenderContextKt_*`); the file name must match those symbols.
 *
 * Content to draw is provided by [AwtSurfaceHost.draw].
 *
 * @see "src/awtMain/cpp/windows/AngleRenderContext.cc" -- native implementation
 */
internal class AngleRenderContext(
    private val host: AwtSurfaceHost,
    private val properties: SkiaLayerProperties
) : AwtRenderContext {
    init {
        try {
            loadAngleLibrary()
        } catch (e: Exception) {
            throw RenderException("Failed to load ANGLE library", cause = e)
        }
    }

    /**
     * Guards every native/JNI touch point: device lifetime, the Skia [DirectContext]/surface, and
     * presentation. [dispose] takes this lock before releasing any native resource, and the per-frame render
     * path ([drawAndSwap]) takes the *same* lock and re-checks [isDisposed] *inside* it before making any
     * native call, mirroring [MetalRenderContext]'s and [Direct3DRenderContext]'s discipline.
     */
    private val drawLock = Any()

    @Volatile
    private var isDisposed = false

    private var device: Long = 0L
        get() {
            if (field == 0L) {
                throw RenderException("ANGLE device is not initialized or already disposed")
            }
            return field
        }

    private val adapterName get() = AngleApi.glGetString(AngleApi.GL_RENDERER)

    override val graphicsApi: GraphicsApi get() = GraphicsApi.ANGLE
    override val deviceName: String?
    override val directContext: DirectContext? get() = context

    init {
        device = host.backedLayer.useDrawingSurfacePlatformInfo { platformInfo ->
            createAngleDevice(platformInfo, host.transparency).takeIf { it != 0L }
                ?: throw RenderException("Failed to create ANGLE device.")
        }
        deviceName = adapterName.also { adapterName ->
            if (adapterName != null && !isVideoCardSupported(GraphicsApi.ANGLE, hostOs, adapterName)) {
                throw RenderException("Cannot create ANGLE render context.")
            }
        }
    }

    // GPU surface for the current frame.
    // Only ever touched under `drawLock`.
    private var context: DirectContext? = null
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var canvas: Canvas? = null
    private var currentWidth = 0
    private var currentHeight = 0

    override val renderInfo: String
        get() = renderInfoHeader(host.renderApi) +
                "Vendor: ${AngleApi.glGetString(AngleApi.GL_VENDOR)}\n" +
                "Model: ${AngleApi.glGetString(AngleApi.GL_RENDERER)}\n" +
                "Version: ${AngleApi.glGetString(AngleApi.GL_VERSION)}\n"

    override fun isTransparentBackgroundSupported(): Boolean = defaultIsTransparentBackgroundSupported(host)

    override fun dispose() = synchronized(drawLock) {
        isDisposed = true
        makeCurrent(device)
        disposeSurface()
        context?.close()
        context = null
        disposeDevice(device)
        device = 0L
    }

    override suspend fun renderFrame(scope: LayerDrawScope, immediate: Boolean) {
        // ANGLE renders on the EDT (there is no off-EDT hand-off); vsync is applied inside the swap.
        val withVsync = if (immediate) SkikoProperties.windowsWaitForVsyncOnRedrawImmediately else properties.isVsyncEnabled
        drawAndSwap(scope, withVsync)
    }

    private fun drawAndSwap(scope: LayerDrawScope, withVsync: Boolean) = synchronized(drawLock) {
        // Re-check inside the lock (not just at the call site): this is what makes `dispose` and an
        // in-flight frame mutually exclusive rather than merely racing on `isDisposed`.
        if (isDisposed) {
            return
        }
        makeCurrent(device)
        with(scope) { drawFrame() }
        swapBuffers(device, withVsync)
    }

    override fun acquireSurface(width: Int, height: Int): Surface = synchronized(drawLock) {
        check(!isDisposed) { "AngleRenderContext is disposed" }
        makeCurrent(device)
        if (!ensureContext()) {
            throw RenderException("Cannot init graphic context")
        }
        createSurface(width, height, host.pixelGeometry)
        surface ?: throw RenderException("Cannot create surface for ${width}x$height")
    }

    override fun present() = synchronized(drawLock) {
        if (!isDisposed) {
            makeCurrent(device)
            context?.flush()
            swapBuffers(device, properties.isVsyncEnabled)
        }
    }

    private fun LayerDrawScope.drawFrame() {
        if (!ensureContext()) {
            throw RenderException("Cannot init graphic context")
        }
        initSurface()
        canvas?.runRestoringState {
            clear(Color.TRANSPARENT)
            host.draw(this)
        }
        context?.flush()
    }

    private fun ensureContext(): Boolean {
        if (context == null) {
            try {
                val newContext = DirectContext(
                    makeAngleContext(device).takeIf { it != 0L }
                        ?: throw RenderException("Failed to make GL context.")
                )
                context = newContext
                onContextInitialized(newContext, properties.gpuResourceCacheLimit) { renderInfo }
            } catch (e: Exception) {
                Logger.warn(e) { "Failed to create Skia ANGLE context!" }
                return false
            }
        }
        return true
    }

    private fun LayerDrawScope.initSurface() = createSurface(scaledLayerWidth, scaledLayerHeight, pixelGeometry)

    private fun createSurface(w: Int, h: Int, pixelGeometry: PixelGeometry) {
        val context = context ?: return

        if (isSizeChanged(w, h) || surface == null) {
            disposeSurface()
            context.flush()

            renderTarget = BackendRenderTarget(
                makeAngleRenderTarget(device, w, h).takeIf { it != 0L }
                    ?: throw RenderException("Failed to make ANGLE render target.")
            )
            surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = pixelGeometry)
            ) ?: throw RenderException("Cannot create surface")
        }

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

    private fun disposeSurface() {
        surface?.close()
        renderTarget?.close()
        surface = null
        renderTarget = null
        canvas = null
    }
}

private external fun createAngleDevice(platformInfo: Long, transparency: Boolean): Long
private external fun makeCurrent(device: Long)
private external fun makeAngleContext(device: Long): Long
private external fun makeAngleRenderTarget(device: Long, width: Int, height: Int): Long
private external fun swapBuffers(device: Long, waitForVsync: Boolean)
private external fun disposeDevice(device: Long)

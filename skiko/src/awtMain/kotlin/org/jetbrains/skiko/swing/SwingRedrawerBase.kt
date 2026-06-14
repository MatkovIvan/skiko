package org.jetbrains.skiko.swing

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.*
import org.jetbrains.skiko.context.rasterizeFrame
import org.jetbrains.skiko.SkiaLayerAnalytics.DeviceAnalytics
import java.awt.Graphics2D
import java.util.concurrent.CancellationException
import javax.swing.SwingUtilities

/**
 * Base for the offscreen (Swing-composited) per-API render path.
 *
 * This base owns the draw orchestration — clear the offscreen surface, play the content in through the
 * shared draw atom ([rasterizeFrame], also used by the on-screen `ContextHandler`), flush, then blit onto
 * [Graphics2D] via [SwingPainter]. It records the content **straight into the offscreen surface canvas**:
 * there is no producer on another thread to hand a picture across, so a picture round-trip here would just
 * be an extra allocation. (A consumer that wants the picture boundary uses [RenderContext] directly.)
 * Subclasses contribute only the API-specific pieces:
 *  - [renderOffscreen]: acquire/reuse the per-API offscreen [Surface] (+ its native texture pointer) and
 *    invoke the supplied draw block within any API render scope it needs;
 *  - [flushSurface]: the API-specific submit/await;
 *  - [painter]: how the result is blitted onto [Graphics2D].
 */
@OptIn(ExperimentalSkikoApi::class)
internal abstract class SwingRedrawerBase(
    private val swingLayerProperties: SwingLayerProperties,
    private val renderDelegate: SkikoRenderDelegate,
    private val analytics: SkiaLayerAnalytics,
    private val graphicsApi: GraphicsApi
) : SwingRedrawer {
    private var isFirstFrameRendered = false

    private val rendererAnalytics = analytics.renderer(Version.skiko, hostOs, graphicsApi)
    private var deviceAnalytics: DeviceAnalytics? = null
    private var isDisposed = false

    init {
        rendererAnalytics.init()
    }

    /** How the rasterized offscreen [Surface] is blitted onto [Graphics2D]. */
    protected abstract val painter: SwingPainter

    /**
     * Acquire (or reuse) the per-API offscreen [Surface] of [width] x [height], then call [draw] with it
     * and the native texture pointer for the accelerated blit (0 when there is none). Any API render
     * scope / lifetime management (e.g. GL make-current brackets, autoclose of transient resources)
     * happens here, around [draw].
     */
    protected abstract fun renderOffscreen(width: Int, height: Int, draw: (surface: Surface, texturePtr: Long) -> Unit)

    /** API-specific flush/submit of [surface] (e.g. flushAndSubmit + completion wait). */
    protected abstract fun flushSurface(surface: Surface)

    override fun dispose() {
        require(!isDisposed) { "$javaClass is disposed" }
        isDisposed = true
    }

    final override fun redraw(g: Graphics2D) {
        require(!isDisposed) { "$javaClass is disposed" }

        inDrawScope {
            val scale = swingLayerProperties.scale
            val width = (swingLayerProperties.width * scale).toInt().coerceAtLeast(0)
            val height = (swingLayerProperties.height * scale).toInt().coerceAtLeast(0)
            if (width < 1 || height < 1) return@inDrawScope

            val nanoTime = System.nanoTime()
            renderOffscreen(width, height) { surface, texturePtr ->
                surface.canvas.rasterizeFrame {
                    renderDelegate.onRender(this, width, height, nanoTime)
                }
                flushSurface(surface)
                painter.paint(g, surface, texturePtr)
            }
        }
    }

    /**
     * Should be called when the device name is known as early, as possible.
     */
    protected fun onDeviceChosen(deviceName: String?) {
        require(!isDisposed) { "$javaClass is disposed" }
        require(deviceAnalytics == null) { "deviceAnalytics is not null" }
        rendererAnalytics.deviceChosen()
        deviceAnalytics = analytics.device(Version.skiko, hostOs, graphicsApi, deviceName)
        deviceAnalytics?.init()
    }

    protected open fun rendererInfo(): String {
        return "GraphicsApi: ${graphicsApi}\n" +
                "OS: ${hostOs.id} ${hostArch.id}\n"
    }

    protected fun onContextInit(context: DirectContext?) {
        require(!isDisposed) { "$javaClass is disposed" }
        requireNotNull(deviceAnalytics) { "deviceAnalytics is not null. Call onDeviceChosen after choosing the drawing device" }
        if (System.getProperty("skiko.hardwareInfo.enabled") == "true") {
            Logger.info { "Renderer info:\n ${rendererInfo()}" }
        }
        context?.configureContext()
        deviceAnalytics?.contextInit()
    }

    protected fun DirectContext.configureContext(
        gpuResourceCacheLimit: Long = swingLayerProperties.gpuResourceCacheLimit
    ): DirectContext = apply {
        if (gpuResourceCacheLimit >= 0) {
            resourceCacheLimit = gpuResourceCacheLimit
        }
    }

    private inline fun inDrawScope(body: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        requireNotNull(deviceAnalytics) { "deviceAnalytics is not null. Call onDeviceChosen after choosing the drawing device" }
        if (!isDisposed) {
            val isFirstFrame = !isFirstFrameRendered
            isFirstFrameRendered = true
            if (isFirstFrame) {
                deviceAnalytics?.beforeFirstFrameRender()
            }
            try {
                body()
            } catch (_: CancellationException) { }
            if (isFirstFrame && !isDisposed) {
                deviceAnalytics?.afterFirstFrameRender()
            }
        }
    }
}

package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skiko.*

/**
 * Per-API GPU surface owner — the internal implementation of the public [RenderContext].
 *
 * Holds the Skia [DirectContext]/[BackendRenderTarget]/[Surface]/[Canvas] for one graphics API and binds a
 * surface to a caller-supplied drawable. It is decoupled from any platform view: subclasses receive
 * their concrete drawable dependency (a redrawer, a Metal device, an AWT blit target) instead of a layer,
 * and the surface is sized by explicit pixel [width]/[height] rather than a view scope.
 *
 * It exposes two entry points over the same per-API surface:
 *  - the public [acquireSurface] + [present] — the [RenderContext] contract; the caller draws onto the
 *    returned [Surface] however it likes; and
 *  - the internal **pull** [draw] (a [LayerDrawScope] member-extension) used by the on-screen redrawers for
 *    the legacy `renderDelegate` path (it plays [drawContent], the layer's recorded picture).
 *
 * `ContextHandler` itself stays `internal`; consumers see only [RenderContext] + `createRenderContext`.
 *
 * @param graphicsApi the graphics API this handler draws with (used for [rendererInfo]).
 * @param pixelGeometry the pixel geometry of the target surface (was carried by the view scope).
 * @param drawContent the content to play for the pull path (today: playback of the recorded picture).
 */
@OptIn(ExperimentalSkikoApi::class)
internal abstract class ContextHandler(
    final override val graphicsApi: GraphicsApi,
    protected val pixelGeometry: PixelGeometry,
    private val drawContent: Canvas.() -> Unit
) : RenderContext {
    protected var context: DirectContext? = null
    protected var renderTarget: BackendRenderTarget? = null
    protected var surface: Surface? = null
    protected var canvas: Canvas? = null

    final override val directContext: DirectContext? get() = context

    protected abstract fun initContext(): Boolean

    /** Acquire (or reuse) the per-API [surface]/[canvas] for a frame of [width] x [height] device pixels. */
    protected abstract fun initCanvas(width: Int, height: Int)

    /**
     * Finish the frame: submit GPU work and present it. Overridden per API — Metal flushes the surface and
     * presents the drawable, the software paths blit, OpenGL/Direct3D/ANGLE submit here and swap per-display
     * in their redrawer. The base just flushes the context.
     */
    override fun present() {
        context?.flush()
    }

    open fun dispose() {
        disposeCanvas()
        context?.close()
    }

    protected open fun disposeCanvas() {
        surface?.close()
        renderTarget?.close()
    }

    override fun close() = dispose()

    open fun rendererInfo(): String {
        return "GraphicsApi: $graphicsApi\n" +
                "OS: ${hostOs.id} ${hostArch.id}\n"
    }

    // throws RenderException if initialization of the graphic context was not successful
    private fun prepareCanvas(width: Int, height: Int) {
        if (!initContext()) {
            throw RenderException("Cannot init graphic context")
        }
        initCanvas(width, height)
    }

    /**
     * Public entry point ([RenderContext]): hand back the per-API [Surface] for a [width] x [height] frame.
     * The caller draws onto it directly and calls [present]. The surface is reused across frames where the
     * backing is stable (swap-chain APIs return the current drawable's surface).
     */
    override fun acquireSurface(width: Int, height: Int): Surface {
        prepareCanvas(width, height)
        return surface ?: throw RenderException("Cannot create $graphicsApi surface (${width}x$height)")
    }

    /**
     * Internal pull entry point used by the on-screen redrawers: rasterize the layer's recorded content for
     * the current [LayerDrawScope] bounds, then present. Tolerant of a null surface/canvas at zero size.
     */
    fun LayerDrawScope.draw() {
        prepareCanvas(scaledLayerWidth, scaledLayerHeight)
        canvas?.rasterizeFrame { drawContent() }
        present()
    }
}

/**
 * The single per-frame draw atom shared by every per-API surface: clear
 * the target to transparent and play [content] in, within a saved canvas state. The transparency-aware
 * background and interop cut-outs are carried by [content] (the recorded picture), not applied here.
 */
internal inline fun Canvas.rasterizeFrame(content: Canvas.() -> Unit) {
    runRestoringState {
        clear(Color.TRANSPARENT)
        content()
    }
}

@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko

import kotlinx.browser.window
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.PixelGeometry
import org.w3c.dom.HTMLCanvasElement

/**
 * Provides a way to render content into an [HTMLCanvasElement] and receive input events.
 *
 * Deprecated and now a thin shim over the public render-context primitives: it binds a [RenderContext] to the
 * attached `<canvas>` via [createRenderContext] and is driven by the shared [DisplayFrameTicker]
 * (`requestAnimationFrame`). Rendering is delegated to [renderDelegate].
 */
@Deprecated(
    message = "Deprecated in favor of the render-context API. On AWT use SkiaPanel; on other platforms use RenderContext with a caller-owned view.",
    level = DeprecationLevel.WARNING,
)
actual open class SkiaLayer {
    /** [GraphicsApi.WEBGL] is the only supported renderApi for the browser. */
    actual var renderApi: GraphicsApi = GraphicsApi.WEBGL

    /** See https://developer.mozilla.org/en-US/docs/Web/API/Window/devicePixelRatio */
    actual val contentScale: Float
        get() = window.devicePixelRatio.toFloat()

    /** Fullscreen is not supported. */
    actual var fullscreen: Boolean
        get() = false
        set(value) {
            if (value) throw Exception("Fullscreen is not supported!")
        }

    /** Rendering and event-processing logic. */
    actual var renderDelegate: SkikoRenderDelegate? = null

    actual val pixelGeometry: PixelGeometry
        get() = PixelGeometry.UNKNOWN

    private var htmlCanvas: HTMLCanvasElement? = null
    private var renderContext: RenderContext? = null
    private var frameTicker: DisplayFrameTicker? = null

    actual val component: Any?
        get() = this.htmlCanvas

    /** Schedules a frame at the next animation-frame tick. */
    actual fun needRender(throttledToVsync: Boolean) {
        frameTicker?.scheduleFrame()
    }

    @Deprecated(
        message = "Use needRender() instead",
        replaceWith = ReplaceWith("needRender()")
    )
    actual fun needRedraw() = needRender()

    /**
     * @param container should be an instance of [HTMLCanvasElement].
     */
    actual fun attachTo(container: Any) {
        attachTo(container as HTMLCanvasElement)
    }

    private fun attachTo(htmlCanvas: HTMLCanvasElement) {
        this.htmlCanvas = htmlCanvas
        renderContext = createRenderContext(htmlCanvas)
        frameTicker = DisplayFrameTicker().apply {
            subscribe { nanoTime -> renderFrame(nanoTime) }
        }
    }

    actual fun detach() {
        frameTicker?.close()
        frameTicker = null
        renderContext?.close()
        renderContext = null
    }

    private fun renderFrame(nanoTime: Long) {
        val context = renderContext ?: return
        val canvasElement = htmlCanvas ?: return
        val width = canvasElement.width
        val height = canvasElement.height
        if (width == 0 || height == 0) return
        val surface = context.acquireSurface(width, height)
        surface.canvas.clear(Color.WHITE)
        renderDelegate?.onRender(surface.canvas, width, height, nanoTime)
        context.present()
    }

    /** No-op: the render loop draws directly through [renderFrame]; kept to satisfy the expect declaration. */
    internal actual fun draw(canvas: Canvas) {}
}

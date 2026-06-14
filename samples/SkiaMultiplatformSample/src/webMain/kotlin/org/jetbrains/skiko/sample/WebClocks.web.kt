@file:OptIn(org.jetbrains.skiko.ExperimentalSkikoApi::class)

package org.jetbrains.skiko.sample

import kotlinx.browser.window
import org.jetbrains.skia.Color
import org.jetbrains.skiko.DisplayFrameTicker
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.createRenderContext
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.TouchEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.get

class WebClocks(canvas: HTMLCanvasElement) : Clocks({ GraphicsApi.WEBGL }) {
    init {
        val scale = window.devicePixelRatio.toFloat()
        val bounds = canvas.getBoundingClientRect()
        canvas.addTypedEvent<TouchEvent>("touchmove") { event ->
            event.preventDefault()
            event.touches[0]?.let {
                xpos = (it.clientX - bounds.left) / scale
                ypos = (it.clientY - bounds.top) / scale
            }
        }
        canvas.addTypedEvent<MouseEvent>("mousemove") { event ->
            xpos = event.offsetX / scale
            ypos = event.offsetY / scale
        }
    }
}

/**
 * Render [delegate] onto [canvas] through the public render-context API, driven by the shared
 * [DisplayFrameTicker] (`requestAnimationFrame`). Replaces the old SkiaLayer + SkiaLayerRenderDelegate
 * path: it binds a [createRenderContext] to the `<canvas>` and animates continuously, scaling the canvas
 * by the device pixel ratio so the delegate keeps drawing in CSS-pixel coordinates.
 */
internal fun startRendering(canvas: HTMLCanvasElement, delegate: SkikoRenderDelegate) {
    val renderContext = createRenderContext(canvas)
    val ticker = DisplayFrameTicker()
    ticker.subscribe { nanoTime ->
        val scale = window.devicePixelRatio.toFloat()
        val width = canvas.width
        val height = canvas.height
        if (width > 0 && height > 0) {
            val surface = renderContext.acquireSurface(width, height)
            surface.canvas.clear(Color.WHITE)
            surface.canvas.scale(scale, scale)
            delegate.onRender(surface.canvas, (width / scale).toInt(), (height / scale).toInt(), nanoTime)
            renderContext.present()
        }
        ticker.scheduleFrame()
    }
    ticker.scheduleFrame()
}

@Suppress("UNCHECKED_CAST")
private fun <T : Event> HTMLCanvasElement.addTypedEvent(
    type: String,
    handler: (event: T) -> Unit
) {
    this.addEventListener(type, { event -> handler(event as T) })
}

@file:OptIn(org.jetbrains.skiko.ExperimentalSkikoApi::class)

package org.jetbrains.skiko.sample.js

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skiko.DisplayFrameTicker
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.createRenderContext
import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement

private class DemoApp: SkikoRenderDelegate {
    private val paint = Paint()

    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        canvas.drawCircle(200f, 50f, 25f, paint)
        canvas.drawLine(100f, 100f, 200f, 200f, paint)

        canvas.drawRect(Rect(10f, 20f, 50f, 70f), paint)
        canvas.drawOval(Rect(110f, 220f, 50f, 70f), paint)
        canvas.drawOval(Rect(110f, 220f, 50f, 70f), paint)
    }
}

internal fun runApp() {
    for (index in 1 .. 3) {
        val canvas = document.getElementById("c$index") as HTMLCanvasElement
        val app: SkikoRenderDelegate = if (index == 3) DemoApp() else BouncingBalls()

        // The consumer owns its <canvas>; skiko binds a render context to it and the shared frame ticker
        // (requestAnimationFrame) drives acquireSurface -> draw -> present.
        val renderContext = createRenderContext(canvas)
        val ticker = DisplayFrameTicker()
        ticker.subscribe { nanoTime ->
            val width = canvas.width
            val height = canvas.height
            val surface = renderContext.acquireSurface(width, height)
            surface.canvas.clear(Color.WHITE)
            app.onRender(surface.canvas, width, height, nanoTime)
            renderContext.present()
            ticker.scheduleFrame()
        }
        ticker.scheduleFrame()
    }
}
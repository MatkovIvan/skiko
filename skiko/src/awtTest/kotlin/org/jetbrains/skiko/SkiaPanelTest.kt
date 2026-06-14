@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko

import kotlinx.coroutines.delay
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skiko.util.uiTest
import org.junit.Test
import java.awt.Robot
import javax.swing.JFrame
import kotlin.test.assertTrue

class SkiaPanelTest {

    private fun redFillPicture(): Picture {
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecording(Rect.makeWH(100_000f, 100_000f))
        canvas.drawRect(Rect.makeWH(100_000f, 100_000f), Paint().apply { color = Color.RED })
        return recorder.finishRecordingAsPicture()
    }

    private fun isRedish(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return r > 180 && g < 80 && b < 80
    }

    @Test(timeout = 60_000)
    fun `present pushes a ready picture to the screen`() = uiTest {
        val frame = JFrame("SkiaPanelTest")
        val panel = SkiaPanel(properties = SkiaLayerProperties(renderApi = renderApi))
        try {
            frame.contentPane.add(panel)
            frame.setSize(220, 220)
            frame.setLocation(60, 60)
            frame.isVisible = true
            delay(300)

            val picture = redFillPicture()
            try {
                repeat(40) {
                    panel.present(picture, panel.width, panel.height)
                    delay(50)
                    val origin = runCatching { panel.locationOnScreen }.getOrNull() ?: return@repeat
                    val rgb = Robot().getPixelColor(origin.x + panel.width / 2, origin.y + panel.height / 2).rgb
                    if (isRedish(rgb)) return@uiTest // success
                }
                throw AssertionError("SkiaPanel.present never produced a red frame on screen")
            } finally {
                picture.close()
            }
        } finally {
            panel.dispose()
            frame.isVisible = false
            frame.dispose()
        }
    }

    @Test(timeout = 60_000)
    fun `eventSurface is the heavyweight canvas`() = uiTest {
        val panel = SkiaPanel(properties = SkiaLayerProperties(renderApi = renderApi))
        try {
            assertTrue(panel.eventSurface is java.awt.Canvas, "DirectSurface eventSurface should be a heavyweight Canvas")
            assertTrue(panel.eventSurface === panel.canvas, "eventSurface should be the backing canvas")
        } finally {
            panel.dispose()
        }
    }

    @Test(timeout = 60_000)
    fun `SwingComposited present pushes a ready picture to the screen`() = uiTest {
        // Software offscreen blit doesn't go through the screen-readable path the same way; skip non-GPU.
        if (renderApi == GraphicsApi.SOFTWARE_COMPAT || renderApi == GraphicsApi.SOFTWARE_FAST) return@uiTest
        val frame = JFrame("SwingCompositedTest")
        val layer = SkiaPanel(
            properties = SkiaLayerProperties(renderApi = renderApi),
            renderMode = SkiaRenderMode.SwingComposited,
        )
        try {
            assertTrue(layer.eventSurface === layer, "SwingComposited eventSurface should be the panel itself")
            frame.contentPane.add(layer)
            frame.setSize(220, 220)
            frame.setLocation(60, 60)
            frame.isVisible = true
            delay(300)

            val picture = redFillPicture()
            try {
                repeat(40) {
                    layer.present(picture, layer.width, layer.height)
                    delay(50)
                    val origin = runCatching { layer.locationOnScreen }.getOrNull() ?: return@repeat
                    val rgb = Robot().getPixelColor(origin.x + layer.width / 2, origin.y + layer.height / 2).rgb
                    if (isRedish(rgb)) return@uiTest
                }
                throw AssertionError("SwingComposited present never produced a red frame on screen")
            } finally {
                picture.close()
            }
        } finally {
            layer.dispose()
            frame.isVisible = false
            frame.dispose()
        }
    }

    @Test(timeout = 60_000)
    fun `DirectSurface exposes a live render context with GPU interop handles`() = uiTest {
        // Software rendering has no GPU DirectContext to expose.
        if (renderApi == GraphicsApi.SOFTWARE_COMPAT || renderApi == GraphicsApi.SOFTWARE_FAST) return@uiTest
        val frame = JFrame("RenderContextInteropTest")
        val panel = SkiaPanel(properties = SkiaLayerProperties(renderApi = renderApi))
        try {
            frame.contentPane.add(panel)
            frame.setSize(220, 220)
            frame.setLocation(60, 60)
            frame.isVisible = true
            delay(300)

            val picture = redFillPicture()
            try {
                // Drive frames until the lazily-created GPU context is initialized.
                var ctx: RenderContext? = null
                for (i in 0 until 60) {
                    panel.present(picture, panel.width, panel.height)
                    delay(50)
                    val candidate = panel.renderContext
                    if (candidate?.directContext != null) {
                        ctx = candidate
                        break
                    }
                }
                val renderContext = ctx
                    ?: throw AssertionError("SkiaPanel.renderContext never exposed a live DirectContext")
                assertTrue(renderContext.graphicsApi == renderApi, "graphicsApi should match the panel's render API")
                if (renderApi == GraphicsApi.METAL) {
                    val device = renderContext.metalDevicePointer
                    assertTrue(device != null && device != 0L, "metalDevicePointer should be a real id<MTLDevice>")
                    val queue = renderContext.metalCommandQueuePointer
                    assertTrue(queue != null && queue != 0L, "metalCommandQueuePointer should be a real id<MTLCommandQueue>")
                }
            } finally {
                picture.close()
            }
        } finally {
            panel.dispose()
            frame.isVisible = false
            frame.dispose()
        }
    }

    @Test(timeout = 60_000)
    fun `SwingComposited exposes no on-screen render context`() = uiTest {
        val layer = SkiaPanel(
            properties = SkiaLayerProperties(renderApi = renderApi),
            renderMode = SkiaRenderMode.SwingComposited,
        )
        try {
            assertTrue(layer.renderContext == null, "SwingComposited mode should not expose an on-screen RenderContext")
        } finally {
            layer.dispose()
        }
    }

    @Test(timeout = 60_000)
    fun `deprecated SkiaLayer is a SkiaPanel`() = uiTest {
        @Suppress("DEPRECATION")
        val layer = SkiaLayer(properties = SkiaLayerProperties(renderApi = renderApi))
        try {
            assertTrue(layer is SkiaPanel, "SkiaLayer must extend the new SkiaPanel engine base")
        } finally {
            @Suppress("DEPRECATION")
            layer.dispose()
        }
    }
}

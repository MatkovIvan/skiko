package org.jetbrains.skiko.context

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.runRestoringState

/**
 * The single per-frame draw atom shared by every per-API [org.jetbrains.skiko.RenderContext]: clear the
 * target to transparent and play [content] in, within a saved canvas state. The transparency-aware
 * background and interop cut-outs are carried by [content] (the recorded picture), not applied here.
 */
internal inline fun Canvas.rasterizeFrame(content: Canvas.() -> Unit) {
    runRestoringState {
        clear(Color.TRANSPARENT)
        content()
    }
}

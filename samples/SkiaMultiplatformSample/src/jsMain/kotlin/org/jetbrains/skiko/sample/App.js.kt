package org.jetbrains.skiko.sample

import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.skiko.wasm.onWasmReady
import org.w3c.dom.HTMLCanvasElement

fun main() {
    window.addEventListener("DOMContentLoaded", {
        onWasmReady(::runApp)
    })
}

internal fun runApp() {
    val canvas = document.getElementById("SkikoTarget") as HTMLCanvasElement
    canvas.setAttribute("tabindex", "0")
    startRendering(canvas, WebClocks(canvas))
}

package org.jetbrains.skiko.context

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.Logger

internal abstract class JvmContextHandler(
    renderApi: GraphicsApi,
    pixelGeometry: PixelGeometry,
    private val gpuResourceCacheLimit: Long,
    drawContent: Canvas.() -> Unit
) : ContextHandler(renderApi, pixelGeometry, drawContent) {
    protected fun onContextInitialized() {
        if (System.getProperty("skiko.hardwareInfo.enabled") == "true") {
            Logger.info { "Renderer info:\n ${rendererInfo()}" }
        }
        context?.run {
            if (gpuResourceCacheLimit >= 0) {
                resourceCacheLimit = gpuResourceCacheLimit
            }
        }
    }
}

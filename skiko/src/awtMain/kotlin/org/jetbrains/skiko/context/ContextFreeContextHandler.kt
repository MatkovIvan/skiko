package org.jetbrains.skiko.context

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.GraphicsApi

internal abstract class ContextFreeContextHandler(
    renderApi: GraphicsApi,
    pixelGeometry: PixelGeometry,
    gpuResourceCacheLimit: Long,
    drawContent: Canvas.() -> Unit
) : JvmContextHandler(renderApi, pixelGeometry, gpuResourceCacheLimit, drawContent) {
    private var isInitialized = false

    override fun initContext(): Boolean {
        if (!isInitialized) {
            isInitialized = true
            onContextInitialized()
        }
        return isInitialized
    }
}

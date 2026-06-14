package org.jetbrains.skiko.context

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.Logger

internal abstract class ContextBasedContextHandler(
    renderApi: GraphicsApi,
    pixelGeometry: PixelGeometry,
    gpuResourceCacheLimit: Long,
    val name: String,
    drawContent: Canvas.() -> Unit
) : JvmContextHandler(renderApi, pixelGeometry, gpuResourceCacheLimit, drawContent) {

    protected abstract fun makeContext(): DirectContext

    override fun initContext(): Boolean {
        try {
            if (context == null) {
                context = makeContext()
                onContextInitialized()
            }
        } catch (e: Exception) {
            Logger.warn(e) { "Failed to create Skia $name context!" }
            return false
        }
        return true
    }
}

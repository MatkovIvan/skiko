package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.Surface
import kotlinx.coroutines.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.layerFrameLimiter
import org.jetbrains.skiko.context.DirectSoftwareContextHandler

internal abstract class AbstractDirectSoftwareRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    private val properties: SkiaLayerProperties
) : AWTRedrawer(layer, analytics, GraphicsApi.SOFTWARE_FAST) {
    private val contextHandler = DirectSoftwareContextHandler(this, properties.gpuResourceCacheLimit, layer.pixelGeometry, layer::draw)
    override val renderInfo: String get() = contextHandler.rendererInfo()

    @OptIn(ExperimentalSkikoApi::class)
    override val renderContext: RenderContext get() = contextHandler

    private val frameJob = Job()
    private val frameLimiter = layerFrameLimiter(CoroutineScope(frameJob), layer.requireBackedLayer)
    private val frameExecutor = RenderExecutor {
        if (properties.isVsyncEnabled && properties.isVsyncFramelimitFallbackEnabled) {
            frameLimiter.awaitNextFrame()
        }

        if (layer.isShowing) {
            update()
            draw()
        }
    }

    protected var device = 0L

    override fun needRender(throttledToVsync: Boolean) {
        frameExecutor.scheduleFrame()
    }

    protected open fun draw() = inDrawScope { contextHandler.draw() }

    override fun renderImmediately() {
        update()
        if (!isDisposed) { // Redrawer may be disposed in user code, during `update`
            draw()
        }
    }

    open fun resize(width: Int, height: Int) = resize(device, width, height)
    fun acquireSurface(): Surface {
        val surface = acquireSurface(device)
        if (surface == 0L) {
            throw RenderException("Failed to create Surface")
        }
        return Surface(surface)
    }
    open fun finishFrame(surface: Long) = finishFrame(device, surface)
    override fun dispose() {
        frameJob.cancel()
        frameExecutor.close()
        contextHandler.dispose()
        disposeDevice(device)
        super.dispose()
    }

    private external fun resize(devicePtr: Long, width: Int, height: Int)
    private external fun acquireSurface(devicePtr: Long): Long
    private external fun finishFrame(devicePtr: Long, surfacePtr: Long)
    private external fun disposeDevice(devicePtr: Long)
}
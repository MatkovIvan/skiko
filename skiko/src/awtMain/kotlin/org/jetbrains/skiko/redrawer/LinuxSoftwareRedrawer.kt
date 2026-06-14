package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.*

internal class LinuxSoftwareRedrawer(
    private val layer: SkiaPanel,
    analytics: SkiaLayerAnalytics,
    properties: SkiaLayerProperties
) : AbstractDirectSoftwareRedrawer(layer, analytics, properties) {

    init {
        onDeviceChosen("Software")
        val scale = layer.contentScale
        val w = (layer.width * scale).toInt().coerceAtLeast(0)
        val h = (layer.height * scale).toInt().coerceAtLeast(0)
        layer.requireBackedLayer.lockLinuxDrawingSurface {
            device = createDevice(it.display, it.window, w, h).also {
                if (it == 0L) {
                    throw RenderException("Failed to create Software device")
                }
            }
        }
        onContextInit()
    }

    override fun dispose() = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.dispose()
    }

    override fun draw() = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.draw()
    }

    override fun renderImmediately() = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.renderImmediately()
    }

    override fun resize(width: Int, height: Int) = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.resize(width, height)
    }

    override fun finishFrame(surface: Long) = layer.requireBackedLayer.lockLinuxDrawingSurface {
        super.finishFrame(surface)
    }

    private external fun createDevice(display: Long, window: Long, width: Int, height: Int): Long
}
package org.jetbrains.skiko.swing

import org.jetbrains.skia.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.alignedTextureWidth
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.createDirectXOffscreenDevice
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.disposeDirectXTexture
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.chooseAdapter
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.disposeDevice
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.makeDirectXContext
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.makeDirectXRenderTargetOffScreen
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.makeDirectXTexture
import org.jetbrains.skiko.graphicapi.InternalDirectXApi.waitForCompletion

/**
 * Offscreen Direct3D surface (Windows) for the Swing-composited path. Provides the per-API offscreen
 * surface; the shared draw/blit orchestration lives in [SwingRedrawerBase].
 */
// TODO reuse DirectXOffscreenContext
internal class Direct3DSwingRedrawer(
    swingLayerProperties: SwingLayerProperties,
    renderDelegate: SkikoRenderDelegate,
    analytics: SkiaLayerAnalytics
) : SwingRedrawerBase(swingLayerProperties, renderDelegate, analytics, GraphicsApi.DIRECT3D) {
    companion object {
        init {
            Library.load()
        }
    }

    private val adapter = chooseAdapter(swingLayerProperties.adapterPriority).also {
        onDeviceChosen("DirectX12") // TODO: properly get name
    }

    private val device = createDirectXOffscreenDevice(adapter)

    override val painter: SwingPainter = SoftwareSwingPainter(swingLayerProperties)

    private val context = if (device == 0L) {
        throw RenderException("Failed to create DirectX12 device.")
    } else {
        DirectContext(
            makeDirectXContext(device)
        )
    }

    private var texturePtr: Long = 0

    init {
        onContextInit(context)
    }

    override fun dispose() {
        context.close()
        disposeDirectXTexture(texturePtr)
        disposeDevice(device)
        painter.dispose()
        super.dispose()
    }

    override fun renderOffscreen(width: Int, height: Int, draw: (surface: Surface, texturePtr: Long) -> Unit) {
        autoCloseScope {
            // We will have [Surface] with width == [alignedWidth],
            // but imitate (for SkikoRenderDelegate and Swing) like it has width == [width].
            val alignedWidth = alignedTextureWidth(width)

            texturePtr = makeDirectXTexture(device, texturePtr, alignedWidth, height)
            if (texturePtr == 0L) {
                throw RenderException("Can't allocate DirectX resources")
            }
            val renderTarget = makeRenderTarget().autoClose()

            val surface = Surface.makeFromBackendRenderTarget(
                context,
                renderTarget,
                SurfaceOrigin.TOP_LEFT,
                SurfaceColorFormat.BGRA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN)
            )?.autoClose() ?: throw RenderException("Cannot create surface")

            draw(surface, texturePtr)
        }
    }

    override fun flushSurface(surface: Surface) {
        surface.flushAndSubmit(syncCpu = false)
        waitForCompletion(device, texturePtr)
    }

    private fun makeRenderTarget() = BackendRenderTarget(
        makeDirectXRenderTargetOffScreen(texturePtr)
    )
}

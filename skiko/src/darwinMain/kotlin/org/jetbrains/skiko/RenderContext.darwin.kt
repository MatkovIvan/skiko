@file:OptIn(BetaInteropApi::class, ExperimentalSkikoApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.objcPtr
import org.jetbrains.skia.*
import platform.Metal.MTLCommandQueueProtocol
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.QuartzCore.CAMetalDrawableProtocol
import platform.QuartzCore.CAMetalLayer

/**
 * Create a [RenderContext] that rasterizes onto a **caller-owned** [CAMetalLayer] (macOS).
 *
 * This is the non-AWT entry point of the render-context API: skiko does not own a view here — the
 * consumer provides its `CAMetalLayer` (e.g. the backing layer of its `NSView`), and skiko binds a Metal
 * GPU context + per-frame drawable to it. Each frame: [acquireSurface][RenderContext.acquireSurface], draw
 * onto it (a picture or direct ops), then [present][RenderContext.present]; [close][RenderContext.close]
 * releases the GPU resources. Drive it from a single render thread. Metal's swap-chain rotates the drawable
 * every frame, so a fresh surface per frame is expected here (not wasteful).
 */
@ExperimentalSkikoApi
fun createRenderContext(layer: CAMetalLayer): RenderContext = MetalRenderContext(layer)

internal class MetalRenderContext(private val metalLayer: CAMetalLayer) : RenderContext {
    internal val device = MTLCreateSystemDefaultDevice()
        ?: throw RenderException("Metal is not supported on this system")
    internal val queue = device.newCommandQueue()
        ?: throw RenderException("Couldn't create Metal command queue")
    private val context = DirectContext.makeMetal(device.objcPtr(), queue.objcPtr())

    override val graphicsApi: GraphicsApi get() = GraphicsApi.METAL
    override val directContext: DirectContext get() = context

    private var drawable: CAMetalDrawableProtocol? = null
    private var renderTarget: BackendRenderTarget? = null
    private var surface: Surface? = null

    init {
        @Suppress("UNCHECKED_CAST")
        metalLayer.device = device as objcnames.protocols.MTLDeviceProtocol?
        metalLayer.framebufferOnly = false
    }

    override fun acquireSurface(width: Int, height: Int): Surface {
        disposeSurface()
        val nextDrawable = metalLayer.nextDrawable()
            ?: throw RenderException("No Metal drawable available")
        drawable = nextDrawable
        val target = BackendRenderTarget.makeMetal(width, height, nextDrawable.texture.objcPtr())
        renderTarget = target
        val newSurface = Surface.makeFromBackendRenderTarget(
            context,
            target,
            SurfaceOrigin.TOP_LEFT,
            SurfaceColorFormat.BGRA_8888,
            ColorSpace.sRGB,
            SurfaceProps(pixelGeometry = PixelGeometry.UNKNOWN)
        ) ?: throw RenderException("Cannot create surface")
        surface = newSurface
        return newSurface
    }

    override fun present() {
        surface?.flushAndSubmit()
        autoreleasepool {
            drawable?.let {
                val commandBuffer = queue.commandBuffer()!!
                commandBuffer.label = "Present"
                commandBuffer.presentDrawable(it)
                commandBuffer.commit()
                drawable = null
            }
        }
    }

    override fun close() {
        disposeSurface()
        context.close()
    }

    private fun disposeSurface() {
        surface?.close()
        surface = null
        renderTarget?.close()
        renderTarget = null
    }
}

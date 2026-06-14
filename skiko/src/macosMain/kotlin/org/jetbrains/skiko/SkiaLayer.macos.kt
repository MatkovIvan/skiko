@file:OptIn(BetaInteropApi::class, ExperimentalSkikoApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.PixelGeometry
import platform.AppKit.*
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CAMetalLayer
import platform.QuartzCore.kCAGravityTopLeft
import platform.QuartzCore.kCALayerHeightSizable
import platform.QuartzCore.kCALayerWidthSizable
import platform.darwin.NSObject

/**
 * SkiaLayer implementation for macOS. Supports only [GraphicsApi.METAL].
 *
 * Deprecated and now a thin shim over the public render-context primitives: it owns a [CAMetalLayer] on the
 * attached [NSView], rasterizes through a [RenderContext] from [createRenderContext], and is driven by the
 * shared [DisplayFrameTicker]. There is no longer a macOS-specific redrawer/context-handler behind it.
 */
@Deprecated(
    message = "Deprecated in favor of the render-context API. On AWT use SkiaPanel; on other platforms use RenderContext with a caller-owned view.",
    level = DeprecationLevel.WARNING,
)
actual open class SkiaLayer {
    fun isShowing(): Boolean = true

    /**
     * [GraphicsApi.METAL] is the only GraphicsApi supported for macOS.
     * Setter throws an IllegalArgumentException if the value is not [GraphicsApi.METAL].
     */
    actual var renderApi: GraphicsApi = GraphicsApi.METAL
        set(value) {
            if (value != GraphicsApi.METAL) {
                throw IllegalArgumentException("$field is not supported in macOS")
            }
            field = value
        }

    /**
     * The scale factor of [NSWindow].
     * https://developer.apple.com/documentation/appkit/nswindow/1419459-backingscalefactor
     */
    actual val contentScale: Float
        get() = if (this::nsView.isInitialized) (nsView.window?.backingScaleFactor?.toFloat() ?: 1.0f) else 1.0f

    /** Fullscreen is not supported. */
    actual var fullscreen: Boolean
        get() = false
        set(value) {
            if (value) throw IllegalArgumentException("fullscreen unsupported")
        }

    /** Underlying [NSView]. */
    lateinit var nsView: NSView
        private set

    actual val component: Any?
        get() = this.nsView

    /** Implements rendering logic and events processing. */
    actual var renderDelegate: SkikoRenderDelegate? = null

    actual val pixelGeometry: PixelGeometry
        get() = PixelGeometry.UNKNOWN

    private var metalLayer: CAMetalLayer? = null
    private var renderContext: RenderContext? = null
    private var frameTicker: DisplayFrameTicker? = null

    private val nsViewObserver = object : NSObject() {
        @ObjCAction
        fun frameDidChange(notification: NSNotification) = needRender()

        @ObjCAction
        fun windowDidChangeBackingProperties(notification: NSNotification) = needRender()

        fun addObserver() {
            val center = NSNotificationCenter.defaultCenter()
            center.addObserver(
                observer = this,
                selector = NSSelectorFromString("frameDidChange:"),
                name = NSViewFrameDidChangeNotification,
                `object` = nsView,
            )
            center.addObserver(
                observer = this,
                selector = NSSelectorFromString("windowDidChangeBackingProperties:"),
                name = NSWindowDidChangeBackingPropertiesNotification,
                `object` = nsView.window,
            )
        }

        fun removeObserver() {
            NSNotificationCenter.defaultCenter().removeObserver(this)
        }
    }

    /**
     * @param container should be an instance of [NSView].
     */
    actual fun attachTo(container: Any) {
        check(!this::nsView.isInitialized) { "Already attached to another NSView" }
        check(container is NSView) { "container should be an instance of NSView" }
        nsView = container
        nsView.postsFrameChangedNotifications = true
        nsViewObserver.addObserver()

        val layer = CAMetalLayer().apply {
            contentsGravity = kCAGravityTopLeft
            setAutoresizingMask(kCALayerWidthSizable or kCALayerHeightSizable)
        }
        nsView.layer = layer
        nsView.wantsLayer = true
        metalLayer = layer

        renderContext = createRenderContext(layer)
        frameTicker = DisplayFrameTicker().apply {
            subscribe { nanoTime -> renderFrame(nanoTime) }
        }
        needRender()
    }

    actual fun detach() {
        nsViewObserver.removeObserver()
        frameTicker?.close()
        frameTicker = null
        renderContext?.close()
        renderContext = null
        metalLayer = null
    }

    /** Schedules a frame at the next vsync tick. */
    actual fun needRender(throttledToVsync: Boolean) {
        frameTicker?.scheduleFrame()
    }

    @Deprecated(
        message = "Use needRender() instead",
        replaceWith = ReplaceWith("needRender()")
    )
    actual fun needRedraw() = needRender()

    private fun renderFrame(nanoTime: Long) {
        val context = renderContext ?: return
        val layer = metalLayer ?: return
        val scale = contentScale
        val widthPoints = nsView.frame.useContents { size.width }
        val heightPoints = nsView.frame.useContents { size.height }
        val width = (widthPoints * scale).toInt().coerceAtLeast(0)
        val height = (heightPoints * scale).toInt().coerceAtLeast(0)
        if (width == 0 || height == 0) return

        layer.contentsScale = scale.toDouble()
        layer.drawableSize = CGSizeMake(width.toDouble(), height.toDouble())

        val surface = context.acquireSurface(width, height)
        surface.canvas.clear(Color.WHITE)
        renderDelegate?.onRender(surface.canvas, width, height, nanoTime)
        context.present()
    }

    /** No-op: the render loop draws directly through [renderFrame]; kept to satisfy the expect declaration. */
    internal actual fun draw(canvas: Canvas) {}
}

@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class, ExperimentalSkikoApi::class)

package org.jetbrains.skiko.sample

import kotlinx.cinterop.*
import org.jetbrains.skia.Color
import org.jetbrains.skiko.DisplayFrameTicker
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.createRenderContext
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectNull
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSCoder
import platform.QuartzCore.CAMetalLayer
import platform.UIKit.*

class IosClocks : Clocks({ GraphicsApi.METAL }) {
    val viewController: UIViewController

    init {
        val view = SampleMetalUIView(this)
        view.translatesAutoresizingMaskIntoConstraints = false
        viewController = UIViewController()
        viewController.view.addSubview(view)
        NSLayoutConstraint.activateConstraints(listOf(
            view.topAnchor.constraintEqualToAnchor(viewController.view.topAnchor),
            view.bottomAnchor.constraintEqualToAnchor(viewController.view.bottomAnchor),
            view.leadingAnchor.constraintEqualToAnchor(viewController.view.leadingAnchor),
            view.trailingAnchor.constraintEqualToAnchor(viewController.view.trailingAnchor)
        ))
    }
}

/**
 * A caller-owned [UIView] whose backing layer is a [CAMetalLayer], rendered through the public
 * render-context API. It binds a [createRenderContext] to its layer and animates continuously via the
 * shared [DisplayFrameTicker] (CADisplayLink) — the iOS counterpart of what `SkiaLayer.macos` does
 * internally, with no SkiaLayer or SkikoUIView in the path.
 */
@ExportObjCClass
private class SampleMetalUIView : UIView {
    companion object : UIViewMeta() {
        override fun layerClass() = CAMetalLayer
    }

    @Suppress("UNUSED") // required by the Objective-C runtime
    @OverrideInit
    constructor(coder: NSCoder) : super(coder) {
        throw UnsupportedOperationException("init(coder:) is not supported for SampleMetalUIView")
    }

    private lateinit var clocks: Clocks
    private val metalLayer: CAMetalLayer get() = layer as CAMetalLayer
    private var renderContext: RenderContext? = null
    private var ticker: DisplayFrameTicker? = null

    constructor(clocks: Clocks, frame: CValue<CGRect> = CGRectNull.readValue()) : super(frame) {
        this.clocks = clocks
        opaque = true
        contentScaleFactor = UIScreen.mainScreen.scale

        renderContext = createRenderContext(metalLayer)
        val t = DisplayFrameTicker()
        ticker = t
        t.subscribe { nanoTime ->
            renderFrame(nanoTime)
            t.scheduleFrame()
        }
        t.scheduleFrame()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val scale = contentScaleFactor
        metalLayer.contentsScale = scale
        bounds.useContents {
            metalLayer.drawableSize = CGSizeMake(size.width * scale, size.height * scale)
        }
    }

    private fun renderFrame(nanoTime: Long) {
        val context = renderContext ?: return
        val scale = contentScaleFactor.toFloat()
        val widthPoints = bounds.useContents { size.width }
        val heightPoints = bounds.useContents { size.height }
        val width = (widthPoints * scale).toInt()
        val height = (heightPoints * scale).toInt()
        if (width <= 0 || height <= 0) return
        val surface = context.acquireSurface(width, height)
        surface.canvas.clear(Color.WHITE)
        surface.canvas.scale(scale, scale)
        clocks.onRender(surface.canvas, widthPoints.toInt(), heightPoints.toInt(), nanoTime)
        context.present()
    }

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        val touch = touches.firstOrNull() as? UITouch ?: return
        touch.locationInView(this).useContents {
            clocks.xpos = x
            clocks.ypos = y
        }
    }
}

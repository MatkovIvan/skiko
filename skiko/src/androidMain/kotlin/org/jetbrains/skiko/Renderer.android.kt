@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko

import android.content.Context
import android.opengl.GLSurfaceView
import android.widget.LinearLayout
import org.jetbrains.skia.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A [GLSurfaceView] that renders a [SkikoRenderDelegate] through the public render-context API.
 *
 * It owns a GLES [RenderContext] (created lazily on the GL thread) and is driven by the shared
 * [DisplayFrameTicker] (Choreographer). Once started via [scheduleFrame] it animates continuously at the
 * display refresh rate until the view is detached: each vsync it records the delegate's frame on the main
 * thread and rasterizes it through the context on the GL thread. This is the non-AWT Android entry point of
 * the render-context API — it does not depend on the deprecated `SkiaLayer`.
 */
class SkikoSurfaceView internal constructor(
    context: Context,
    renderDelegateProvider: () -> SkikoRenderDelegate?,
) : GLSurfaceView(context) {

    /** Render [renderDelegate] into this view; drive it with [scheduleFrame]. */
    constructor(context: Context, renderDelegate: SkikoRenderDelegate) :
        this(context, { renderDelegate })

    private val renderer = SkikoSurfaceRender(renderDelegateProvider)
    init {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setEGLConfigChooser(8, 8, 8, 0, 24, 8)
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        setRenderMode(RENDERMODE_WHEN_DIRTY)
    }

    // The shared, vsync-aligned frame source (Choreographer). Each tick records the frame on the main
    // thread, asks the GL thread to draw it (GLSurfaceView coalesces requestRender to one per vsync), and
    // schedules the next tick so animation keeps running.
    private val frameTicker = DisplayFrameTicker().apply {
        subscribe {
            renderer.update()
            requestRender()
            scheduleFrame()
        }
    }

    fun scheduleFrame() {
        frameTicker.scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        frameTicker.close()
        renderer.dispose()
        super.onDetachedFromWindow()
    }
}

private class SkikoSurfaceRender(
    private val renderDelegateProvider: () -> SkikoRenderDelegate?,
) : GLSurfaceView.Renderer {
    private var width: Int = 0
    private var height: Int = 0

    @Volatile
    private var picture: PictureHolder? = null
    private var pictureRecorder: PictureRecorder = PictureRecorder()
    private val pictureLock = Any()

    private var renderContext: RenderContext? = null

    private fun <T : Any> lockPicture(action: (PictureHolder) -> T): T? {
        return synchronized(pictureLock) {
            val picture = picture
            if (picture != null) action(picture) else null
        }
    }

    // Called from the main thread: record the frame from the render delegate into a picture.
    fun update() {
        renderDelegateProvider()?.let {
            val bounds = Rect.makeWH(width.toFloat(), height.toFloat())
            val canvas = pictureRecorder.beginRecording(bounds)
            try {
                it.onRender(canvas, width, height, System.nanoTime())
            } finally {
                synchronized(pictureLock) {
                    picture?.instance?.close()
                    picture = PictureHolder(pictureRecorder.finishRecordingAsPicture(), width, height)
                }
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // The GLES surface is owned by the RenderContext, created lazily on the GL thread in onDrawFrame.
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    // Called from the GL rendering thread: rasterize the recorded picture through the public render context.
    override fun onDrawFrame(gl: GL10?) {
        if (width == 0 || height == 0) return
        val context = renderContext ?: createRenderContext().also { renderContext = it }
        val surface = context.acquireSurface(width, height)
        surface.canvas.clear(Color.WHITE)
        lockPicture { surface.canvas.drawPicture(it.instance) }
        context.present()
    }

    fun dispose() {
        renderContext?.close()
        renderContext = null
    }
}

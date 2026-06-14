package org.jetbrains.skiko.sample

import kotlinx.cinterop.useContents
import org.jetbrains.skiko.GraphicsApi
import platform.AppKit.NSEvent
import platform.AppKit.NSTrackingActiveInActiveApp
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSTrackingMouseMoved
import platform.AppKit.NSView
import platform.AppKit.NSViewHeightSizable
import platform.AppKit.NSViewWidthSizable
import platform.AppKit.NSWindow

/**
 * Hosts the clock animation in a caller-owned [NSView] and tracks mouse movement. The view's rendering is
 * driven by `main()` through the public render-context API (a [org.jetbrains.skiko.createRenderContext] over
 * a `CAMetalLayer` + a [org.jetbrains.skiko.DisplayFrameTicker]); this class no longer touches SkiaLayer.
 */
class MacosClocks(window: NSWindow) : Clocks({ GraphicsApi.METAL }) {
    val view: NSView

    init {
        view = object : NSView(window.frame) {
            private var trackingArea: NSTrackingArea? = null

            override fun updateTrackingAreas() {
                trackingArea?.let { removeTrackingArea(it) }
                trackingArea = NSTrackingArea(
                    rect = bounds,
                    options = NSTrackingActiveInActiveApp or NSTrackingMouseMoved,
                    owner = this,
                    userInfo = null
                )
                addTrackingArea(trackingArea!!)
            }

            override fun mouseMoved(event: NSEvent) {
                val height = frame.useContents { size.height }
                event.locationInWindow.useContents {
                    xpos = x
                    ypos = height - y
                }
            }
        }

        val contentView = window.contentView!!
        view.autoresizingMask = NSViewHeightSizable or NSViewWidthSizable
        contentView.addSubview(view)
    }
}

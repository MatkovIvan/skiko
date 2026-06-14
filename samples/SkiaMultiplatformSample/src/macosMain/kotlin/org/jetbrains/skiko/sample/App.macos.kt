@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.sample

import kotlinx.cinterop.useContents
import org.jetbrains.skia.Color
import org.jetbrains.skiko.DisplayFrameTicker
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.createRenderContext
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSApplicationDelegateProtocol
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSMenu
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSMakeRect
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CAMetalLayer
import platform.QuartzCore.kCAGravityTopLeft
import platform.QuartzCore.kCALayerHeightSizable
import platform.QuartzCore.kCALayerWidthSizable
import platform.darwin.NSObject

fun main() {
    val app = NSApplication.sharedApplication()
    app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    val appName = "SkikoNative"
    var bar = NSMenu()
    app.setMainMenu(bar)
    var appMenuItem = bar.addItemWithTitle(appName, null, "");
    var appMenu = NSMenu()
    appMenuItem.setSubmenu(appMenu)
    appMenu.addItemWithTitle("About $appName", NSSelectorFromString("orderFrontStandardAboutPanel:"), "a")
    appMenu.addItemWithTitle("Quit $appName", NSSelectorFromString("terminate:"), "q")

    app.delegate = object: NSObject(), NSApplicationDelegateProtocol {
        override fun applicationShouldTerminateAfterLastWindowClosed(sender: NSApplication): Boolean {
            return true
        }
    }
    val windowStyle = NSWindowStyleMaskTitled or
                NSWindowStyleMaskMiniaturizable or
                NSWindowStyleMaskClosable or
                NSWindowStyleMaskResizable
    val window = object : NSWindow(
        contentRect = NSMakeRect(0.0, 0.0, 640.0, 480.0),
        styleMask = windowStyle,
        backing = NSBackingStoreBuffered,
        defer = false
    ) {
        override fun canBecomeKeyWindow() = true
        override fun canBecomeMainWindow() = true
    }

    // Render through the public render-context API: own a CAMetalLayer on the clocks' NSView, bind a
    // RenderContext to it, and drive it with the shared DisplayFrameTicker. No SkiaLayer involved.
    val clocks = MacosClocks(window)
    val nsView = clocks.view
    val metalLayer = CAMetalLayer().apply {
        contentsGravity = kCAGravityTopLeft
        setAutoresizingMask(kCALayerWidthSizable or kCALayerHeightSizable)
    }
    nsView.layer = metalLayer
    nsView.wantsLayer = true

    val renderContext = createRenderContext(metalLayer)
    val ticker = DisplayFrameTicker()
    ticker.subscribe { nanoTime ->
        val scale = nsView.window?.backingScaleFactor?.toFloat() ?: 1.0f
        val widthPoints = nsView.frame.useContents { size.width }
        val heightPoints = nsView.frame.useContents { size.height }
        val width = (widthPoints * scale).toInt().coerceAtLeast(0)
        val height = (heightPoints * scale).toInt().coerceAtLeast(0)
        if (width > 0 && height > 0) {
            metalLayer.contentsScale = scale.toDouble()
            metalLayer.drawableSize = CGSizeMake(width.toDouble(), height.toDouble())
            val surface = renderContext.acquireSurface(width, height)
            surface.canvas.clear(Color.WHITE)
            surface.canvas.scale(scale, scale)
            clocks.onRender(surface.canvas, widthPoints.toInt(), heightPoints.toInt(), nanoTime)
            renderContext.present()
        }
        ticker.scheduleFrame()
    }
    ticker.scheduleFrame()

    window.makeKeyAndOrderFront(app)
    app.run()
}

package org.jetbrains.skiko

import org.jetbrains.skiko.rendercontext.OnScreenRenderer
import org.jetbrains.skiko.rendercontext.createRenderContext
import javax.swing.UIManager

actual fun setSystemLookAndFeel() = UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

// AWT builds a per-API render context ([createRenderContext]) and drives it through the single generic
// [OnScreenRenderer] loop, which is the [Redrawer] the SkiaLayer path selects and paces.
internal actual fun makeDefaultRenderFactory(): RenderFactory =
    RenderFactory { layer, renderApi, analytics, properties ->
        OnScreenRenderer(layer, createRenderContext(layer, renderApi, properties), analytics)
    }

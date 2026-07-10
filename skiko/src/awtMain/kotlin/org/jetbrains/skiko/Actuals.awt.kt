package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.OnScreenRedrawer
import org.jetbrains.skiko.redrawer.createRedrawer
import javax.swing.UIManager

actual fun setSystemLookAndFeel() = UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

// AWT builds a per-API render context ([createRedrawer]) and drives it through the single generic
// [OnScreenRedrawer] loop, which is the [Redrawer] the SkiaLayer path selects and paces.
internal actual fun makeDefaultRenderFactory(): RenderFactory =
    RenderFactory { layer, renderApi, analytics, properties ->
        OnScreenRedrawer(layer, createRedrawer(layer, renderApi, properties), analytics)
    }

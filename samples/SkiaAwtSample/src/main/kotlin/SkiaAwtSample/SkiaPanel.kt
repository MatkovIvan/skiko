@file:OptIn(org.jetbrains.skiko.ExperimentalSkikoApi::class)

package SkiaAwtSample

import org.jetbrains.skiko.ClipComponent
import java.awt.Color
import java.awt.Component
import javax.swing.JLayeredPane

// A JLayeredPane that hosts skiko's SkiaPanel and overlays Swing components clipped out of it.
open class SkiaPanel: JLayeredPane {
    val layer = org.jetbrains.skiko.SkiaPanel()

    /** The frame source driving [layer]'s animation; closed when this pane is removed. */
    var frameTicker: AutoCloseable? = null

    constructor() : super() {
        setLayout(null)
        setBackground(Color.white)
    }

    override fun add(component: Component): Component {
        layer.clipComponents.add(ClipComponent(component))
        return super.add(component, Integer.valueOf(0))
    }

    override fun doLayout() {
        layer.setBounds(0, 0, width, height)
    }

    override fun addNotify() {
        super.addNotify()
        super.add(layer, Integer.valueOf(10))
    }

     override fun removeNotify() {
        frameTicker?.close()
        layer.dispose()
        super.removeNotify()
     }
}

@file:OptIn(ExperimentalSkikoApi::class)

package SkiaAwtSample

import org.jetbrains.skiko.ClipComponent
import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.SkiaRenderMode
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Color
import java.awt.Component
import javax.swing.JLayeredPane

class SkiaSwingPanel(skikoView: SkikoRenderDelegate) : JLayeredPane() {
    val layer = SkiaPanel(renderMode = SkiaRenderMode.SwingComposited).apply { renderDelegate = skikoView }

    init {
        layout = null
        background = Color.white
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
        layer.dispose()
        super.removeNotify()
    }
}

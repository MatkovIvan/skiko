@file:OptIn(org.jetbrains.skiko.ExperimentalSkikoApi::class)

package org.jetbrains.skiko.sample

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import org.jetbrains.skiko.SkikoSurfaceView

class MainActivity : Activity() {
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val holder = LinearLayout(this)

        // Render directly through the public render-context primitives: a SkikoSurfaceView bound to a
        // SkikoRenderDelegate (Clocks), driven by the shared DisplayFrameTicker inside the view.
        val clocks = Clocks(contentScale = { resources.displayMetrics.density })
        val view = SkikoSurfaceView(this, clocks)
        view.isFocusableInTouchMode = true
        view.setOnTouchListener { _, event ->
            clocks.onPointerMove(event.x, event.y)
            true
        }
        holder.addView(view)
        view.scheduleFrame()

        layout.addView(holder)
        setContentView(layout, layout.layoutParams)
    }
}

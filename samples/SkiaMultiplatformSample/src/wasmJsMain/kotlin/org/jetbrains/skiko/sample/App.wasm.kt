package org.jetbrains.skiko.sample

import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontMgrWithFallback
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.TypefaceFontProviderWithFallback
import org.jetbrains.skiko.wasm.onWasmReady
import org.w3c.dom.HTMLCanvasElement


fun main() {
    onWasmReady {
        runClocksApp()
//        runEmojiStoryApp()
    }
}

internal fun runClocksApp() {
    val canvas = document.getElementById("SkikoTarget") as HTMLCanvasElement
    canvas.setAttribute("tabindex", "0")
    startRendering(canvas, WebClocks(canvas))
}

private val notoColorEmoji = "https://storage.googleapis.com/skia-cdn/misc/NotoColorEmoji.ttf"
private val notoSancSC = "./NotoSansSC-Regular.ttf"

internal fun runEmojiStoryApp() {
    val canvas = document.getElementById("SkikoTarget") as HTMLCanvasElement
    canvas.setAttribute("tabindex", "0")
    // Start the continuous render loop now; once the fonts below finish loading the next frame picks them up.
    startRendering(canvas, EmojiStory())

    MainScope().launch {
        val notoEmojisBytes = loadRes(notoColorEmoji).toByteArray()
        val notoSansSCBytes = loadRes(notoSancSC).toByteArray()
        val notoEmojiTypeface = FontMgr.default.makeFromData(Data.makeFromBytes(notoEmojisBytes))
        val notoSansSCTypeface = FontMgr.default.makeFromData(Data.makeFromBytes(notoSansSCBytes))

        val tfp = TypefaceFontProviderWithFallback().apply {
            registerTypeface(notoEmojiTypeface)
            registerTypeface(notoSansSCTypeface)
        }
        EmojiStory.fontCollection.setDefaultFontManager(FontMgrWithFallback(tfp))
    }
}

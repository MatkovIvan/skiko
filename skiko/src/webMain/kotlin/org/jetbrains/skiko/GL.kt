package org.jetbrains.skiko

import org.jetbrains.skia.impl.NativePointer
import org.jetbrains.skiko.wasm.ContextAttributes
import org.w3c.dom.HTMLCanvasElement

internal external interface GLInterface {
    fun createContext(context: HTMLCanvasElement, contextAttributes: ContextAttributes): NativePointer
    fun makeContextCurrent(contextPointer: NativePointer): Boolean
}

internal expect val GL: GLInterface

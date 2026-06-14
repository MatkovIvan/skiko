package org.jetbrains.skiko

import org.jetbrains.skia.impl.NativePointer

/**
 * The WebGL context handle skiko renders through (the emscripten GL context id), or `null` if this context
 * is not WebGL-backed.
 *
 * Unlike Metal/Direct3D there is no separate device or queue in the GL family: the rendering context is
 * **thread-current**. To sample a GL texture you produced, make this context current, wrap your texture id
 * with [org.jetbrains.skia.BackendTexture.makeGL], and import it with [org.jetbrains.skia.Image.adoptTextureFrom]
 * against [RenderContext.directContext] — all on skiko's render thread. On the JVM OpenGL/ANGLE backends and
 * Android (`GLSurfaceView`), skiko does not own a separately passable context (it uses the thread-current one
 * the host manages / the consumer already holds), so only [directContext] + `makeGL` are needed there.
 */
@ExperimentalSkikoApi
val RenderContext.openGLContextHandle: NativePointer?
    get() = (this as? WebGLRenderContext)?.contextPointer

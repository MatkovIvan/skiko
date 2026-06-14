package org.jetbrains.skiko

/**
 * Native-Linux [RenderContext] factory.
 *
 * Native Linux has no on-screen renderer in skiko today (the whole `SkiaLayer.linux` actual is a stub).
 * There is therefore nothing to bind a render context to here. On the Linux **desktop**, use the JVM (AWT)
 * target — `SkiaPanel` / the AWT `RenderContext` — which is fully implemented. This factory exists only to
 * keep the API uniform across targets and fails fast until a native-Linux renderer is implemented.
 */
@ExperimentalSkikoApi
fun createRenderContext(): RenderContext =
    throw NotImplementedError(
        "Native-Linux rendering is not implemented in skiko. Use the JVM (AWT) target on Linux desktop."
    )

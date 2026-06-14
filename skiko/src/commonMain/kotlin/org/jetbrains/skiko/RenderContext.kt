package org.jetbrains.skiko

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface

/**
 * A reusable, view-decoupled GPU render context.
 *
 * It abstracts only the two things that are genuinely platform/API specific — **creating** the drawable
 * surface and **presenting** it — and otherwise gets out of the way: [acquireSurface] hands you the real
 * Skia [Surface], and you draw onto it however you like (replay an `SkPicture`, issue draw calls directly,
 * blit, snapshot, read pixels — whatever [Surface] supports). skiko does not wrap, hide, or dictate the
 * content.
 *
 * Typical frame:
 * ```
 * val surface = ctx.acquireSurface(width, height)
 * surface.canvas.drawPicture(picture)   // or any direct drawing
 * ctx.present()
 * ```
 *
 * This is the primitive non-AWT consumers use to drive their own view: own your `CAMetalLayer` / `<canvas>`
 * / GL context, get a context from `createRenderContext(...)`, and run the loop above. On AWT the same
 * contract backs `SkiaPanel` internally.
 *
 * Not thread-safe — drive it from a single render thread. [close] releases the GPU resources.
 *
 * ## GPU interop
 *
 * For zero-copy interop with an external renderer (a native Metal/OpenGL/Direct3D engine, a video
 * decoder, a game) the context exposes the live GPU primitives it draws with: the Skia [directContext]
 * and — through platform-specific accessors layered on top (e.g. `RenderContext.metalDevice`,
 * `RenderContext.metalDevicePointer`, `RenderContext.direct3DDevicePointer`, `RenderContext.openGLContextHandle`) —
 * the underlying native device/queue handles. Import a texture your engine produced *on the same device*
 * into this context and sample it without a CPU round-trip:
 * ```
 * val ctx = renderContext.directContext ?: return        // null ⇒ software path, no GPU interop
 * val backend = BackendTexture.makeMetal(w, h, yourMtlTexturePtr)   // or makeGL / makeDirect3D
 * val image = Image.adoptTextureFrom(ctx, backend, SurfaceOrigin.TOP_LEFT, ColorType.BGRA_8888)
 * canvas.drawImage(image, 0f, 0f)
 * ```
 * These handles are valid **only on the render thread and only for the lifetime of this context** — never
 * [close][AutoCloseable.close] or otherwise own them. Synchronizing your engine's GPU work against skiko's
 * (e.g. a shared command queue or a semaphore so skiko samples only finished frames) is your responsibility.
 */
@ExperimentalSkikoApi
interface RenderContext : AutoCloseable {
    /**
     * The graphics API this context renders with. Tells you which platform-specific native-handle accessors
     * apply (Metal ⇒ `metalDevice*`, Direct3D ⇒ `direct3D*`, the GL family ⇒ `openGLContextHandle`).
     */
    val graphicsApi: GraphicsApi

    /**
     * The live Skia GPU context backing this render context, or `null` for software/raster rendering and
     * before the first [acquireSurface] of a lazily-initialised backend. It is the entry point for GPU
     * interop — build a `BackendTexture` from your external texture handle and wrap it with
     * `Image.adoptTextureFrom(directContext, …)`. Owned by the context: use it, but do not
     * [close][org.jetbrains.skia.RefCnt.close] it.
     */
    val directContext: DirectContext?

    /**
     * The [Surface] to draw the current frame into, sized [width] x [height] device pixels.
     *
     * The surface is **reused** across frames while its backing is stable (e.g. a GL framebuffer or an
     * offscreen texture whose size has not changed); swap-chain APIs (Metal) necessarily return the current
     * drawable's surface, which rotates per frame. The returned [Surface] is owned by the context — draw on
     * it and flush it, but do not [close][Surface.close] it.
     */
    fun acquireSurface(width: Int, height: Int): Surface

    /**
     * Finish the current frame: submit the GPU work and present it where the API presents — swap a Metal
     * drawable, blit a software buffer, or no-op for targets the host swaps (a browser canvas, a
     * `GLSurfaceView`, or an AWT redrawer that swaps per-display itself).
     */
    fun present()
}

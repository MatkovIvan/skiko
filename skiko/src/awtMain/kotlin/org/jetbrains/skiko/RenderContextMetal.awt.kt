package org.jetbrains.skiko

import org.jetbrains.skiko.context.MetalContextHandler

/**
 * The `id<MTLDevice>` this [RenderContext] renders on as a native pointer (a `__bridge`-castable address),
 * or `null` if it is not Metal-backed. Pass it across your own JNI/`cinterop` boundary to allocate the
 * textures skiko will sample on the same device, then import them with
 * [org.jetbrains.skia.BackendTexture.makeMetal] + [org.jetbrains.skia.Image.adoptTextureFrom]. Valid only on
 * the render thread for the lifetime of the context; never release it.
 */
@ExperimentalSkikoApi
val RenderContext.metalDevicePointer: Long?
    get() = (this as? MetalContextHandler)?.metalDeviceObjcPtr

/**
 * The `id<MTLCommandQueue>` skiko submits its frames on as a native pointer, or `null` if this context is
 * not Metal-backed. Submit your interop work against it (or synchronize your own queue against it) so skiko
 * samples only finished frames. Valid only on the render thread for the lifetime of the context.
 */
@ExperimentalSkikoApi
val RenderContext.metalCommandQueuePointer: Long?
    get() = (this as? MetalContextHandler)?.metalCommandQueueObjcPtr

package org.jetbrains.skiko

import platform.Metal.MTLCommandQueueProtocol
import platform.Metal.MTLDeviceProtocol

/**
 * The `MTLDevice` this [RenderContext] renders on, or `null` if it is not Metal-backed.
 *
 * Allocate the textures you want skiko to sample on this same device, then import them with
 * [org.jetbrains.skia.BackendTexture.makeMetal] + [org.jetbrains.skia.Image.adoptTextureFrom]. Valid only on
 * the render thread for the lifetime of the context; do not retain it past [RenderContext.close].
 */
@ExperimentalSkikoApi
val RenderContext.metalDevice: MTLDeviceProtocol?
    get() = (this as? MetalRenderContext)?.device

/**
 * The `MTLCommandQueue` skiko submits its frames on, or `null` if this context is not Metal-backed. Encode
 * your interop work against it (or synchronize your own queue against it) so skiko samples only finished
 * frames. Valid only on the render thread for the lifetime of the context.
 */
@ExperimentalSkikoApi
val RenderContext.metalCommandQueue: MTLCommandQueueProtocol?
    get() = (this as? MetalRenderContext)?.queue

package org.jetbrains.skiko

import org.jetbrains.skiko.redrawer.Direct3DRenderContext

/**
 * The `IDXGIAdapter1` this [RenderContext] renders on as a native pointer, or `null` if it is not
 * Direct3D-backed. Valid only on the render thread for the lifetime of the context; never release it.
 */
@ExperimentalSkikoApi
val RenderContext.direct3DAdapterPointer: Long?
    get() = (this as? Direct3DRenderContext)?.direct3DAdapterPtr

/**
 * The `ID3D12Device` this [RenderContext] renders on as a native pointer, or `null` if it is not
 * Direct3D-backed. Create the resources skiko will sample on this same device, then import them with
 * [org.jetbrains.skia.BackendTexture.makeDirect3D] + [org.jetbrains.skia.Image.adoptTextureFrom]. Valid only
 * on the render thread for the lifetime of the context; never release it.
 */
@ExperimentalSkikoApi
val RenderContext.direct3DDevicePointer: Long?
    get() = (this as? Direct3DRenderContext)?.direct3DDevicePtr

/**
 * The `ID3D12CommandQueue` skiko submits its frames on as a native pointer, or `null` if this context is not
 * Direct3D-backed. Synchronize your own GPU work against it so skiko samples only finished frames. Valid only
 * on the render thread for the lifetime of the context; never release it.
 */
@ExperimentalSkikoApi
val RenderContext.direct3DQueuePointer: Long?
    get() = (this as? Direct3DRenderContext)?.direct3DQueuePtr

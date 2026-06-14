package org.jetbrains.skia

import org.jetbrains.skia.impl.*
import org.jetbrains.skia.impl.Library.Companion.staticLoad

class BackendTexture internal constructor(ptr: NativePointer) : Managed(ptr, _FinalizerHolder.PTR) {
    companion object {
        /**
         * Creates BackendTexture from GL texture.
         *
         * @param width - width of the [BackendTexture] to be created
         * @param height - height of the [BackendTexture] to be created
         * @param isMipmapped - if the passed [textureId] has a GL mipmap, this should be true, otherwise false
         * @param textureId - GL id of the texture to use
         * @param textureTarget - GL enum, must be valid texture target for 2D, e.g. GL_TEXTURE_2D
         * @param textureFormat - GL enum, must be valid color format, e.g. GL_RGBA or GL_BGRA_INTEGER
         *
         * @see glTextureParametersModified
         */
        fun makeGL(
            width: Int,
            height: Int,
            isMipmapped: Boolean,
            textureId: Int,
            textureTarget: Int,
            textureFormat: Int
        ): BackendTexture {
            Stats.onNativeCall()
            val ptr = _nMakeGL(
                width,
                height,
                isMipmapped,
                textureId,
                textureTarget,
                textureFormat
            )
            return BackendTexture(ptr)
        }

        /**
         * Creates a [BackendTexture] wrapping an existing Metal texture (`id<MTLTexture>`).
         *
         * The texture must live on the same `MTLDevice` as the [DirectContext] it will be used with — read
         * that device from the render context's Metal handle accessor (`metalDevice` / `metalDevicePointer`).
         * Pair with [Image.adoptTextureFrom] to sample it with no CPU round-trip. Available only on Metal
         * builds; throws otherwise.
         *
         * @param width        width of the texture, in pixels
         * @param height       height of the texture, in pixels
         * @param mtlTexturePtr native pointer to the `id<MTLTexture>` (e.g. via `objcPtr()` on Kotlin/Native,
         *                      or a `__bridge` cast to a pointer on the JVM)
         * @param isMipmapped  whether [mtlTexturePtr] has a complete mipmap chain
         */
        fun makeMetal(
            width: Int,
            height: Int,
            mtlTexturePtr: NativePointer,
            isMipmapped: Boolean = false
        ): BackendTexture {
            Stats.onNativeCall()
            val ptr = _nMakeMetal(width, height, mtlTexturePtr, isMipmapped)
            if (ptr == NullPointer) throw RuntimeException("Failed to create Metal BackendTexture (Metal not available on this build?)")
            return BackendTexture(ptr)
        }

        /**
         * Creates a [BackendTexture] wrapping an existing Direct3D 12 texture (`ID3D12Resource`).
         *
         * The resource must belong to the same `ID3D12Device` as the [DirectContext] it will be used with —
         * read that device from the render context's Direct3D handle accessors (`direct3DDevicePointer` etc).
         * Pair with [Image.adoptTextureFrom] to sample it with no CPU round-trip. Available only on Direct3D
         * builds; throws otherwise.
         *
         * @param width      width of the texture, in pixels
         * @param height     height of the texture, in pixels
         * @param texturePtr pointer to the `ID3D12Resource` texture resource; must be non-zero
         * @param format     the resource's `DXGI_FORMAT`
         * @param sampleCnt  sample count of the resource
         * @param levelCnt   mip level count of the resource
         */
        fun makeDirect3D(
            width: Int,
            height: Int,
            texturePtr: NativePointer,
            format: Int,
            sampleCnt: Int = 1,
            levelCnt: Int = 1
        ): BackendTexture {
            Stats.onNativeCall()
            val ptr = _nMakeDirect3DTexture(width, height, texturePtr, format, sampleCnt, levelCnt)
            if (ptr == NullPointer) throw RuntimeException("Failed to create Direct3D BackendTexture (Direct3D not available on this build?)")
            return BackendTexture(ptr)
        }

        init {
            staticLoad()
        }
    }

    /**
     * Call this to indicate to DirectContext that the texture parameters have been modified in the GL context externally
     */
    fun glTextureParametersModified() {
        return try {
            Stats.onNativeCall()
            _nGLTextureParametersModified(getPtr(this))
        } finally {
            reachabilityBarrier(this)
        }
    }

    private object _FinalizerHolder {
        val PTR = BackendTexture_nGetFinalizer()
    }
}

@ExternalSymbolName("org_jetbrains_skia_BackendTexture__1nGetFinalizer")
private external fun BackendTexture_nGetFinalizer(): NativePointer

@ExternalSymbolName("org_jetbrains_skia_BackendTexture__1nMakeGL")
private external fun _nMakeGL(
    width: Int,
    height: Int,
    isMipmapped: Boolean,
    textureId: Int,
    target: Int,
    format: Int
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_BackendTexture__1nMakeMetal")
private external fun _nMakeMetal(
    width: Int,
    height: Int,
    texturePtr: NativePointer,
    isMipmapped: Boolean
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_BackendTexture__1nMakeDirect3DTexture")
private external fun _nMakeDirect3DTexture(
    width: Int,
    height: Int,
    texturePtr: NativePointer,
    format: Int,
    sampleCnt: Int,
    levelCnt: Int
): NativePointer

@ExternalSymbolName("org_jetbrains_skia_BackendTexture__1nGLTextureParametersModified")
private external fun _nGLTextureParametersModified(backendTexturePtr: NativePointer)

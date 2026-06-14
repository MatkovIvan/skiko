@file:OptIn(ExperimentalSkikoApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.ExperimentalSkikoApi
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.Library
import org.jetbrains.skiko.Logger
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.RenderContext
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkiaPanel
import org.jetbrains.skiko.SkikoProperties
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.loadAngleLibrary
import org.jetbrains.skiko.loadOpenGLLibrary

/**
 * One organized way to obtain an on-screen [RenderContext] for a [SkiaPanel], in three layers (plan §6.6):
 *
 *  1. [createRenderContext] `(panel, properties, api)` — **direct**: build exactly `api`, or throw. The only
 *     place per-API construction (device-from-window-handle, swap chain, GL/ANGLE library load) lives. This
 *     is `makeDefaultRenderFactory`'s `when (hostOs)/(api)` table, now producing the per-API `RenderContext`
 *     instead of a `Redrawer`.
 *  2. [createRenderContext] `(panel, properties, apiPreference)` — **fallback**: try the API order, return the
 *     first that constructs, or throw. Reusable off-screen / standalone.
 *  3. [RenderContextFactory] — **stateful selector** for a long-lived driver: remembers the working API,
 *     recreates after a failure, and forces an API. Replaces `RedrawerManager.findNextWorkingRenderApi`.
 */
/**
 * The on-screen render-context creation seam (replaces the deleted `RenderFactory`). [Default] builds the
 * real per-API context; tests inject their own to force software / simulate a failing API.
 */
internal fun interface RenderContextProvider {
    fun create(layer: SkiaPanel, properties: SkiaLayerProperties, api: GraphicsApi): RenderContext

    companion object {
        val Default = RenderContextProvider { layer, properties, api -> createRenderContext(layer, properties, api) }
    }
}

internal fun createRenderContext(
    layer: SkiaPanel,
    properties: SkiaLayerProperties,
    api: GraphicsApi,
): RenderContext = when (hostOs) {
    OS.MacOS -> when (api) {
        GraphicsApi.SOFTWARE_COMPAT, GraphicsApi.SOFTWARE_FAST -> SoftwareRenderContext(layer, properties)
        else -> {
            Library.load()
            MetalRenderContext(layer, properties)
        }
    }
    OS.Windows -> when (api) {
        GraphicsApi.SOFTWARE_COMPAT -> SoftwareRenderContext(layer, properties)
        GraphicsApi.SOFTWARE_FAST -> WindowsDirectSoftwareRenderContext(layer, properties)
        GraphicsApi.OPENGL -> {
            loadOpenGLLibrary()
            WindowsOpenGLRenderContext(layer, properties)
        }
        GraphicsApi.ANGLE -> {
            loadAngleLibrary()
            AngleRenderContext(layer, properties)
        }
        else -> Direct3DRenderContext(layer, properties)
    }
    OS.Linux -> when (api) {
        GraphicsApi.SOFTWARE_COMPAT -> SoftwareRenderContext(layer, properties)
        GraphicsApi.SOFTWARE_FAST -> LinuxDirectSoftwareRenderContext(layer, properties)
        else -> {
            loadOpenGLLibrary()
            LinuxOpenGLRenderContext(layer, properties)
        }
    }
    else -> throw UnsupportedOperationException("AWT doesn't support $hostOs")
}

/**
 * Fallback chain (plan §6.6 layer 2): the first API in [apiPreference] that constructs a working
 * [RenderContext] for [layer], or [RenderException] if none do. Stateless — reusable off-screen and standalone.
 */
internal fun createRenderContext(
    layer: SkiaPanel,
    properties: SkiaLayerProperties,
    apiPreference: List<GraphicsApi> = SkikoProperties.fallbackRenderApiQueue(properties.renderApi),
): RenderContext {
    for (api in apiPreference) {
        try {
            return createRenderContext(layer, properties, api)
        } catch (e: RenderException) {
            Logger.warn(e) { "Failed to create $api render context, trying next API" }
        }
    }
    throw RenderException("Cannot create a render context for any of $apiPreference")
}

/**
 * Stateful render-API selector (plan §6.6 layer 3) for a long-lived on-screen driver: owns the
 * preference queue, remembers the current API/context, and recreates after a runtime failure. Replaces the
 * `Redrawer`-typed `RedrawerManager`; yields a [RenderContext] instead.
 */
internal class RenderContextFactory(
    private val layer: SkiaPanel,
    private val properties: SkiaLayerProperties,
    private val provider: RenderContextProvider = RenderContextProvider.Default,
    defaultRenderApi: GraphicsApi = properties.renderApi,
    private val onRenderApiChanged: ((GraphicsApi) -> Unit)? = null,
) {
    private val fallbackRenderApiQueue = SkikoProperties.fallbackRenderApiQueue(defaultRenderApi).toMutableList()

    var current: RenderContext? = null
        private set

    var renderApi: GraphicsApi = fallbackRenderApiQueue[0]
        private set(value) {
            field = value
            onRenderApiChanged?.invoke(value)
        }

    /** Create the first working context (or, with [recreation], retry the current API first). Throws if none work. */
    fun create(recreation: Boolean = false): RenderContext {
        if (recreation) {
            fallbackRenderApiQueue.add(0, renderApi)
        }
        var thrown: Boolean
        do {
            thrown = false
            try {
                renderApi = fallbackRenderApiQueue.removeAt(0)
                current = provider.create(layer, properties, renderApi)
            } catch (e: RenderException) {
                current = null
                Logger.warn(e) { "Fallback to next API" }
                thrown = true
            }
        } while (thrown && fallbackRenderApiQueue.isNotEmpty())

        if (thrown) {
            throw RenderException("Cannot fallback to any render API")
        }
        return current!!
    }

    fun dispose() {
        current = null
    }
}

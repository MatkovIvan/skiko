package org.jetbrains.skiko

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.skia.*
import org.jetbrains.skiko.internal.fastForEach
import org.jetbrains.skiko.redrawer.Redrawer
import org.jetbrains.skiko.redrawer.RedrawerManager
import org.jetbrains.skiko.swing.SwingLayerProperties
import org.jetbrains.skiko.swing.SwingRedrawer
import org.jetbrains.skiko.swing.createSwingRedrawer
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.Point
import java.awt.event.*
import java.awt.geom.AffineTransform
import java.awt.im.InputMethodRequests
import java.beans.PropertyChangeListener
import java.util.concurrent.CancellationException
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.SwingUtilities.isEventDispatchThread
import javax.swing.event.AncestorEvent
import javax.swing.event.AncestorListener
import kotlin.math.floor

/**
 * How a [SkiaPanel] presents its frames. The mode is a **parameter** of
 * the one component — not a separate component per mode.
 */
@ExperimentalSkikoApi
enum class SkiaRenderMode {
    /** Heavyweight on-screen GPU surface presented at vsync (the former `SkiaLayer` path). */
    DirectSurface,

    /** Lightweight offscreen rasterize + Java2D blit; best Swing interop (the former `SkiaSwingLayer` path). */
    SwingComposited,
}

/**
 * The unified AWT skiko component. One component, with [renderMode] as a
 * parameter, replaces both `SkiaLayer` (DirectSurface) and `SkiaSwingLayer` (SwingComposited).
 *
 * It owns the recorded [Picture] and the per-mode render engine, and supports two ways to supply content:
 *  - **push** — the caller hands a ready picture via [present] (the seam Compose adopts);
 *  - **pull** — the legacy [renderDelegate] is invoked each frame to produce the content.
 *
 * The two modes differ only in the heavyweight/lightweight bits, handled internally:
 *  - **DirectSurface** adds a heavyweight `HardwareLayer` canvas child (the input target, [eventSurface]),
 *    runs an on-screen [Redrawer] at vsync, and re-presents on damage via the redrawer;
 *  - **SwingComposited** is its own lightweight input surface ([eventSurface] = `this`), rasterizes
 *    offscreen and blits in [paint].
 *
 * The deprecated `SkiaLayer`/`SkiaSwingLayer` are thin subclasses that just pin the mode. Non-AWT consumers
 * use [RenderContext] directly and wire their own view.
 */
@ExperimentalSkikoApi
open class SkiaPanel internal constructor(
    accessibleContextProvider: ((Component) -> AccessibleContext)? = null,
    val properties: SkiaLayerProperties = SkiaLayerProperties(),
    private val renderFactory: RenderFactory = RenderFactory.Default,
    private val analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
    val pixelGeometry: PixelGeometry = PixelGeometry.UNKNOWN,
    val renderMode: SkiaRenderMode = SkiaRenderMode.DirectSurface,
) : JComponent(), Accessible {

    @JvmOverloads
    constructor(
        properties: SkiaLayerProperties = SkiaLayerProperties(),
        analytics: SkiaLayerAnalytics = SkiaLayerAnalytics.Empty,
        pixelGeometry: PixelGeometry = PixelGeometry.UNKNOWN,
        renderMode: SkiaRenderMode = SkiaRenderMode.DirectSurface,
    ) : this(null, properties, RenderFactory.Default, analytics, pixelGeometry, renderMode)

    internal companion object {
        init {
            Library.load()
        }
    }

    enum class PropertyKind {
        Renderer,
        ContentScale,
    }

    private val isDirect: Boolean get() = renderMode == SkiaRenderMode.DirectSurface

    // Kept for the SwingComposited accessibility context (DirectSurface routes it to the heavyweight canvas).
    private val accessibleContextProvider = accessibleContextProvider

    /**
     * The heavyweight canvas child for [DirectSurface][SkiaRenderMode.DirectSurface]; `null` in
     * [SwingComposited][SkiaRenderMode.SwingComposited] (where the panel is its own surface).
     */
    internal val backedLayer: HardwareLayer?

    /** The heavyweight input canvas (DirectSurface only; `null` in SwingComposited). */
    val canvas: java.awt.Canvas?
        get() = backedLayer

    /**
     * Non-null heavyweight canvas for the on-screen redrawers, which are only ever created in
     * [DirectSurface][SkiaRenderMode.DirectSurface] mode (so [backedLayer] is non-null there).
     */
    internal val requireBackedLayer: HardwareLayer
        get() = backedLayer ?: error("requireBackedLayer is only valid in DirectSurface mode")

    /**
     * The AWT component that is the input target for this panel: the heavyweight [canvas] for DirectSurface,
     * or `this` for SwingComposited. Compose wires its `contentRoot` to this uniformly.
     */
    val eventSurface: Component
        get() = backedLayer ?: this

    private var peerBufferSizeFixJob: Job? = null
    private var latestReceivedGraphicsContextScaleTransform: AffineTransform? = null

    init {
        layout = null
        if (isDirect) {
            backedLayer = object : HardwareLayer(accessibleContextProvider) {
                override fun paint(g: Graphics) {
                    Logger.debug { "Paint called on HardwareLayer $this" }
                    checkContentScale()
                    redrawer?.needRender(throttledToVsync = false)
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun reshape(x: Int, y: Int, width: Int, height: Int) {
                    Logger.debug { "reshape(x=$x, y=$y, w=$width, h=$height) called on $this" }
                    @Suppress("DEPRECATION")
                    super.reshape(x, y, width, height)

                    redrawer?.syncBounds()
                    redrawer?.needRender(throttledToVsync = false)
                }

                override fun getInputMethodRequests(): InputMethodRequests? {
                    return this@SkiaPanel.inputMethodRequests
                }

                override fun requestFocus(cause: FocusEvent.Cause?) {
                    if (canReceiveFocus(cause)) {
                        super.requestFocus(cause)
                    }
                }

                override fun requestFocusInWindow(cause: FocusEvent.Cause?): Boolean {
                    return canReceiveFocus(cause) && super.requestFocusInWindow(cause)
                }

                private fun canReceiveFocus(cause: FocusEvent.Cause?) = cause != FocusEvent.Cause.MOUSE_EVENT ||
                        isRequestFocusEnabled
            }.also {
                @Suppress("LeakingThis")
                add(it)
            }

            addAncestorListener(object : AncestorListener {

                private var positionInWindow: Point? = null

                private val zeroPoint = Point(0, 0)

                private fun computePositionInWindow(): Point? {
                    val window = SwingUtilities.getWindowAncestor(this@SkiaPanel)
                    return if (window == null) {
                        null
                    } else {
                        SwingUtilities.convertPoint(this@SkiaPanel, zeroPoint, window)
                    }
                }

                override fun ancestorAdded(event: AncestorEvent?) {
                    positionInWindow = computePositionInWindow()
                }

                override fun ancestorRemoved(event: AncestorEvent?) {
                    positionInWindow = null
                }

                override fun ancestorMoved(event: AncestorEvent?) {
                    val newPosition = computePositionInWindow()
                    if ((positionInWindow != null) && (positionInWindow != newPosition)) {
                        revalidate()
                    }
                    positionInWindow = newPosition
                }
            })

            backedLayer!!.addHierarchyListener {
                if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                    checkShowing()
                }
            }

            addPropertyChangeListener("graphicsContextScaleTransform") {
                Logger.debug { "graphicsContextScaleTransform changed for $this" }
                latestReceivedGraphicsContextScaleTransform = it.newValue as AffineTransform
                revalidate()
                notifyChange(PropertyKind.ContentScale)

                // Workaround for JBR-5259
                if (hostOs == OS.Windows) {
                    peerBufferSizeFixJob?.cancel()
                    @OptIn(DelicateCoroutinesApi::class)
                    peerBufferSizeFixJob = GlobalScope.launch(MainUIDispatcher) {
                        backedLayer.setLocation(1, 0)
                        backedLayer.setLocation(0, 0)
                    }
                }
            }
        } else {
            backedLayer = null
            isOpaque = false
        }
    }

    private var _transparency: Boolean = false

    /**
     * Whether transparency is enabled.
     */
    var transparency: Boolean
        get() = _transparency
        set(value) {
            configureBackground(value, _background)
        }

    private var _background: Color? = null

    override fun setBackground(bg: Color?) {
        configureBackground(_transparency, bg)
    }

    private fun configureBackground(transparency: Boolean, bg: Color?) {
        _transparency = transparency
        _background = bg
        super.setBackground(bg)
        backedLayer?.background = if (transparency) Color(0, 0, 0, 0) else bg
        needRender(throttledToVsync = true)
    }

    // Override to make final, because it's called it in the init block
    final override fun addAncestorListener(listener: AncestorListener?) {
        super.addAncestorListener(listener)
    }

    // Override to make final, because it's called it in the init block
    final override fun addPropertyChangeListener(propertyName: String?, listener: PropertyChangeListener?) {
        super.addPropertyChangeListener(propertyName, listener)
    }

    private val fullscreenAdapter = backedLayer?.let { FullscreenAdapter(it) }

    override fun removeNotify() {
        Logger.debug { "SkiaPanel#removeNotify $this" }
        fullscreenAdapter?.let { SwingUtilities.getWindowAncestor(this).removeComponentListener(it) }
        dispose()
        super.removeNotify()
    }

    override fun addNotify() {
        Logger.debug { "SkiaPanel#addNotify $this" }
        super.addNotify()
        fullscreenAdapter?.let { SwingUtilities.getWindowAncestor(this).addComponentListener(it) }
        checkShowing()
        init(isInited)
    }

    fun detach() {
        dispose()
    }

    private var isInited = false
    private var isRendering = false

    private fun checkShowing() {
        val wasShowing = isShowingCached
        val isShowingNow = super.isShowing().also {
            isShowingCached = it
        }
        if (isDirect) {
            if (wasShowing != isShowingNow) {
                if (SwingUtilities.getWindowAncestor(this).isShowing) {
                    redrawer?.setVisible(isShowingNow)
                }
            }
            if (isShowingNow) {
                redrawer?.syncBounds()
                repaint()
            }
        } else if (isShowingNow) {
            repaint()
        }
    }

    private var isShowingCached = false

    override fun isShowing(): Boolean {
        // DirectSurface tracks the heavyweight canvas's showing state explicitly; SwingComposited is its
        // own surface and uses the real Swing showing state (so RepaintManager actually paints it).
        return if (isDirect) isShowingCached else super.isShowing()
    }

    val contentScale: Float
        get() = graphicsConfiguration.defaultTransform.scaleX.toFloat()

    /**
     * Returns the pointer to an OS specific handle (native resource) of the panel; `0` in SwingComposited.
     */
    val contentHandle: Long
        get() = backedLayer?.contentHandle ?: 0L

    /**
     * Returns the pointer to an OS specific window handle (native resource) which the current panel is
     * attached; `0` in SwingComposited.
     */
    val windowHandle: Long
        get() = backedLayer?.windowHandle ?: 0L

    /**
     * Returns the physical DPI value (number of dots per inch) of the current monitor.
     */
    val currentDPI: Int
        // SwingComposited has no heavyweight peer to query; fall back to the standard baseline DPI.
        get() = backedLayer?.currentDPI ?: 96

    var fullscreen: Boolean
        get() = fullscreenAdapter?.fullscreen ?: false
        set(value) {
            fullscreenAdapter?.fullscreen = value
        }

    val component: Any?
        get() = backedLayer ?: this

    /**
     * The legacy pull render callback. When non-null, it is invoked each frame to produce the content (the
     * historical `SkiaLayer`/`SkiaSwingLayer` path). Leave it null and call [present] to push a ready picture.
     */
    var renderDelegate: SkikoRenderDelegate? = null

    fun attachTo(container: Any) {
        attachTo(container as JComponent)
    }

    fun attachTo(jComponent: JComponent) {
        jComponent.add(this)
    }

    /**
     * A list of rectangles to cut out from the rendered content; No content will be drawn inside them.
     */
    val clipComponents = mutableListOf<ClipRectangle>()

    @Volatile
    private var isDisposed = false

    // --- DirectSurface engine (on-screen Redrawer) ---

    private val redrawerManager: RedrawerManager<Redrawer>? =
        if (isDirect) RedrawerManager(
            defaultRenderApi = properties.renderApi,
            redrawerFactory = { renderApi, oldRedrawer ->
                oldRedrawer?.dispose()
                renderFactory.createRedrawer(this, renderApi, analytics, properties).also {
                    it.syncBounds()
                }
            },
            onRenderApiChanged = { notifyChange(PropertyKind.Renderer) }
        ) else null

    internal val redrawer: Redrawer? get() = redrawerManager?.redrawer

    /**
     * The live [RenderContext] backing this panel's on-screen rendering, exposed for GPU interop: read its
     * [directContext][RenderContext.directContext] and platform-specific device/queue handles (e.g.
     * `metalDevicePointer`, `direct3DDevicePointer`) to import an external GPU texture into the same context
     * and sample it from the scene — no CPU round-trip — so Compose `graphicsLayer` transforms and clipping
     * apply with full fidelity.
     *
     * `null` in [SkiaRenderMode.SwingComposited] mode (the offscreen path is not exposed — use
     * [SkiaRenderMode.DirectSurface] or a standalone `createRenderContext` for interop) and before the
     * on-screen redrawer has been created. Even when non-null, its [directContext][RenderContext.directContext]
     * is `null` until the first frame initialises the GPU context. Touch it only on the render thread.
     */
    @ExperimentalSkikoApi
    val renderContext: RenderContext? get() = redrawer?.renderContext

    // --- SwingComposited engine (offscreen SwingRedrawer, blit in paint) ---

    private val swingLayerProperties = object : SwingLayerProperties {
        override val width: Int get() = this@SkiaPanel.width
        override val height: Int get() = this@SkiaPanel.height
        override val graphicsConfiguration: GraphicsConfiguration get() = this@SkiaPanel.graphicsConfiguration
        override val adapterPriority: GpuPriority get() = properties.adapterPriority
        override val gpuResourceCacheLimit: Long get() = properties.gpuResourceCacheLimit
    }

    // The SwingComposited frame content: cut out interop holes, then either replay the pushed picture or
    // pull the legacy render delegate (mirrors the DirectSurface picture/renderDelegate choice).
    private val swingRenderDelegate = SkikoRenderDelegate { canvas, width, height, nanoTime ->
        clipComponents.fastForEach { canvas.cutoutFromClip(it, contentScale) }
        val held = lockPicture { it.instance }
        if (held != null) {
            canvas.drawPicture(held)
        } else {
            renderDelegate?.onRender(canvas, width, height, nanoTime)
        }
    }

    private val swingRedrawerManager: RedrawerManager<SwingRedrawer>? =
        if (!isDirect) RedrawerManager(
            defaultRenderApi = properties.renderApi,
            redrawerFactory = { renderApi, oldRedrawer ->
                oldRedrawer?.dispose()
                createSwingRedrawer(swingLayerProperties, swingRenderDelegate, renderApi, analytics)
            },
            onRenderApiChanged = { notifyChange(PropertyKind.Renderer) }
        ) else null

    private val swingRedrawer: SwingRedrawer? get() = swingRedrawerManager?.redrawer

    var renderApi: GraphicsApi
        get() = (redrawerManager ?: swingRedrawerManager!!).renderApi
        set(value) {
            redrawerManager?.renderApi = value
            swingRedrawerManager?.renderApi = value
        }

    val renderInfo: String
        get() = redrawer?.renderInfo
            ?: if (swingRedrawerManager != null) "SkiaPanel(SwingComposited, $renderApi)"
            else "SkiaPanel isn't initialized yet"

    @Volatile
    private var picture: PictureHolder? = null
    // True when [picture] was recorded by this panel (pull path) and must be closed by it; false when it
    // was handed in via [present] (push path) and is owned by the caller.
    private var pictureOwnedByPanel = false
    private var pictureRecorder: PictureRecorder? = null
    private val pictureLock = Any()

    private fun init(recreation: Boolean = false) {
        isDisposed = false
        backedLayer?.init()
        pictureRecorder = PictureRecorder()
        (redrawerManager ?: swingRedrawerManager)?.findNextWorkingRenderApi(recreation)
        isInited = true
    }

    private val stateChangeListeners =
        mutableMapOf<PropertyKind, MutableList<(SkiaPanel) -> Unit>>()

    fun onStateChanged(kind: PropertyKind, handler: (SkiaPanel) -> Unit) {
        stateChangeListeners.getOrPut(kind, ::mutableListOf) += handler
    }

    private fun notifyChange(kind: PropertyKind) {
        stateChangeListeners[kind]?.let { handlers ->
            handlers.fastForEach { handler ->
                handler.invoke(this)
            }
        }
    }

    open fun dispose() {
        check(isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        if (isInited && !isDisposed) {
            // we should dispose redrawer first (to cancel `draw` in rendering thread)
            redrawer?.dispose()
            redrawerManager?.dispose()
            swingRedrawer?.dispose()
            swingRedrawerManager?.dispose()
            if (pictureOwnedByPanel) picture?.instance?.close()
            picture = null
            pictureRecorder?.close()
            pictureRecorder = null
            backedLayer?.dispose()
            peerBufferSizeFixJob?.cancel()
            isDisposed = true
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun reshape(x: Int, y: Int, w: Int, h: Int) {
        @Suppress("DEPRECATION")
        super.reshape(x, y, w, h)

        if (isDirect && renderApi == GraphicsApi.DIRECT3D && isShowing) {
            redrawer?.syncBounds()
            redrawer?.renderImmediately()
        }

        validate()
    }

    override fun doLayout() {
        Logger.debug { "doLayout on $this" }
        val backedLayer = backedLayer ?: return
        backedLayer.setBounds(
            0,
            0,
            adjustSizeToContentScale(contentScale, width),
            adjustSizeToContentScale(contentScale, height)
        )
        backedLayer.validate()
    }

    override fun paint(g: Graphics) {
        Logger.debug { "paint called on SkiaPanel $this" }
        checkContentScale()
        if (isDirect) {
            redrawer?.needRender(throttledToVsync = false)
        } else {
            try {
                swingRedrawer?.redraw(g as Graphics2D)
            } catch (e: RenderException) {
                if (!isDisposed) {
                    Logger.warn(e) { "Exception in draw scope" }
                    swingRedrawerManager?.findNextWorkingRenderApi()
                    repaint()
                }
            }
        }
    }

    // Workaround for JBR-5274 and JBR-5305
    fun checkContentScale() {
        val currentGraphicsContextScaleTransform = graphicsConfiguration.defaultTransform
        if (currentGraphicsContextScaleTransform != latestReceivedGraphicsContextScaleTransform) {
            firePropertyChange(
                "graphicsContextScaleTransform",
                latestReceivedGraphicsContextScaleTransform,
                currentGraphicsContextScaleTransform
            )
        }
    }

    // For DirectSurface we delegate all input/focus to the heavyweight canvas. For SwingComposited the
    // panel is its own surface, so these fall through to the default JComponent behavior.

    override fun enableInputMethods(enable: Boolean) {
        val backedLayer = backedLayer ?: return super.enableInputMethods(enable)
        backedLayer.enableInputMethods(enable)
    }

    override fun getInputMethodListeners(): Array<InputMethodListener> {
        return backedLayer?.getInputMethodListeners() ?: super.getInputMethodListeners()
    }

    override fun processInputMethodEvent(e: InputMethodEvent?) {
        val backedLayer = backedLayer ?: return super.processInputMethodEvent(e)
        backedLayer.doProcessInputMethodEvent(e)
    }

    override fun addFocusListener(l: FocusListener?) {
        val backedLayer = backedLayer ?: return super.addFocusListener(l)
        backedLayer.addFocusListener(l)
    }

    override fun removeFocusListener(l: FocusListener?) {
        val backedLayer = backedLayer ?: return super.removeFocusListener(l)
        backedLayer.removeFocusListener(l)
    }

    override fun setFocusable(focusable: Boolean) {
        val backedLayer = backedLayer ?: return super.setFocusable(focusable)
        backedLayer.isFocusable = focusable
    }

    override fun isFocusable(): Boolean {
        return backedLayer?.isFocusable ?: super.isFocusable()
    }

    override fun hasFocus(): Boolean {
        return backedLayer?.hasFocus() ?: super.hasFocus()
    }

    override fun isFocusOwner(): Boolean {
        return backedLayer?.isFocusOwner ?: super.isFocusOwner()
    }

    override fun requestFocus() {
        val backedLayer = backedLayer ?: return super.requestFocus()
        backedLayer.requestFocus()
    }

    override fun requestFocus(cause: FocusEvent.Cause?) {
        val backedLayer = backedLayer ?: return super.requestFocus(cause)
        backedLayer.requestFocus(cause)
    }

    override fun requestFocusInWindow(): Boolean {
        return backedLayer?.requestFocusInWindow() ?: super.requestFocusInWindow()
    }

    override fun requestFocusInWindow(cause: FocusEvent.Cause?): Boolean {
        return backedLayer?.requestFocusInWindow(cause) ?: super.requestFocusInWindow(cause)
    }

    override fun setFocusTraversalKeysEnabled(focusTraversalKeysEnabled: Boolean) {
        val backedLayer = backedLayer ?: return super.setFocusTraversalKeysEnabled(focusTraversalKeysEnabled)
        backedLayer.focusTraversalKeysEnabled = focusTraversalKeysEnabled
    }

    override fun getFocusTraversalKeysEnabled(): Boolean {
        return backedLayer?.focusTraversalKeysEnabled ?: super.getFocusTraversalKeysEnabled()
    }

    override fun addInputMethodListener(l: InputMethodListener) {
        super.addInputMethodListener(l)
        backedLayer?.addInputMethodListener(l)
    }

    override fun addMouseListener(l: MouseListener) {
        val backedLayer = backedLayer ?: return super.addMouseListener(l)
        backedLayer.addMouseListener(l)
    }

    override fun addMouseMotionListener(l: MouseMotionListener) {
        val backedLayer = backedLayer ?: return super.addMouseMotionListener(l)
        backedLayer.addMouseMotionListener(l)
    }

    override fun addMouseWheelListener(l: MouseWheelListener) {
        val backedLayer = backedLayer ?: return super.addMouseWheelListener(l)
        backedLayer.addMouseWheelListener(l)
    }

    override fun addKeyListener(l: KeyListener) {
        val backedLayer = backedLayer ?: return super.addKeyListener(l)
        backedLayer.addKeyListener(l)
    }

    override fun removeInputMethodListener(l: InputMethodListener) {
        super.removeInputMethodListener(l)
        backedLayer?.removeInputMethodListener(l)
    }

    override fun removeMouseListener(l: MouseListener) {
        val backedLayer = backedLayer ?: return super.removeMouseListener(l)
        backedLayer.removeMouseListener(l)
    }

    override fun removeMouseMotionListener(l: MouseMotionListener) {
        val backedLayer = backedLayer ?: return super.removeMouseMotionListener(l)
        backedLayer.removeMouseMotionListener(l)
    }

    override fun removeMouseWheelListener(l: MouseWheelListener) {
        val backedLayer = backedLayer ?: return super.removeMouseWheelListener(l)
        backedLayer.removeMouseWheelListener(l)
    }

    override fun removeKeyListener(l: KeyListener) {
        val backedLayer = backedLayer ?: return super.removeKeyListener(l)
        backedLayer.removeKeyListener(l)
    }

    /**
     * Redraw on the next animation Frame (on vsync signal if vsync is enabled).
     *
     * No default argument: the deprecated `SkiaLayer` subclass actualizes this for an `expect` member
     * that has a default, and Kotlin disallows an override carrying an inherited default in that case.
     */
    open fun needRender(throttledToVsync: Boolean) {
        check(isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        check(!isDisposed) { "SkiaPanel is disposed" }
        if (isDirect) {
            redrawer?.needRender(throttledToVsync)
        } else {
            repaint()
        }
    }

    /**
     * Updates the panel and redraws synchronously.
     */
    fun renderImmediately() {
        if (isDirect) {
            redrawer?.renderImmediately()
        } else if (width > 0 && height > 0) {
            paintImmediately(0, 0, width, height)
        }
    }

    /**
     * Present a ready [picture] (recorded at [width] x [height] device pixels): stores it and schedules a
     * present. This is the push entry point; re-presenting on damage/expose needs no caller round-trip. The
     * caller retains ownership of [picture] (it is referenced until the next [present]).
     */
    fun present(picture: Picture, width: Int, height: Int) {
        check(isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        check(!isDisposed) { "SkiaPanel is disposed" }
        synchronized(pictureLock) {
            if (pictureOwnedByPanel) this.picture?.instance?.close()
            this.picture = PictureHolder(picture, width, height)
            pictureOwnedByPanel = false // caller owns the pushed picture
        }
        needRender(throttledToVsync = true)
    }

    internal fun update(nanoTime: Long) {
        check(isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        check(!isDisposed) { "SkiaPanel is disposed" }
        val backedLayer = backedLayer ?: return

        checkContentScale()
        FrameWatcher.nextFrame()

        // Pull mode: record the frame from the legacy render delegate. Push mode (present()): the picture
        // is already stored, so there is nothing to record here.
        val renderDelegate = renderDelegate ?: return

        val contentScale = this.contentScale
        val pictureWidth = (backedLayer.width * contentScale).coerceAtLeast(0f)
        val pictureHeight = (backedLayer.height * contentScale).coerceAtLeast(0f)
        val intWidth = pictureWidth.toInt()
        val intHeight = pictureHeight.toInt()

        val pictureRecorder = pictureRecorder!!
        val canvas = pictureRecorder.beginRecording(0f, 0f, pictureWidth, pictureHeight).apply {
            for (component in clipComponents) {
                cutoutFromClip(component, contentScale)
            }

            val layerBg = background.rgb
            clear(
                if (transparency && (redrawer?.isTransparentBackgroundSupported() == true)) {
                    layerBg
                } else {
                    layerBg or 0xFF000000.toInt()
                }
            )
        }

        try {
            isRendering = true
            renderDelegate.onRender(canvas, intWidth, intHeight, nanoTime)
        } finally {
            isRendering = false
        }

        if (!isDisposed && !pictureRecorder.isClosed) {
            synchronized(pictureLock) {
                if (pictureOwnedByPanel) picture?.instance?.close()
                val picture = pictureRecorder.finishRecordingAsPicture()
                this.picture = PictureHolder(picture, intWidth, intHeight)
                pictureOwnedByPanel = true // this panel recorded and owns the picture
            }
        }
    }

    @Suppress("LeakingThis")
    private val fpsCounter = defaultFPSCounter(this)

    private fun createDrawScope() = LayerDrawScope(
        pixelGeometry = pixelGeometry,
        layerWidth = width,
        layerHeight = height,
        scale = contentScale
    )

    internal inline fun inDrawScope(body: LayerDrawScope.() -> Unit) {
        check(isEventDispatchThread()) { "Method should be called from AWT event dispatch thread" }
        check(!isDisposed) { "SkiaPanel is disposed" }
        try {
            fpsCounter?.tick()
            with(createDrawScope()) {
                body()
            }
        } catch (_: CancellationException) {
            // ignore
        } catch (e: RenderException) {
            if (!isDisposed) {
                Logger.warn(e) { "Exception in draw scope" }
                redrawerManager?.findNextWorkingRenderApi()
                redrawer?.renderImmediately()
            }
        }
    }

    internal fun draw(canvas: Canvas) {
        check(!isDisposed) { "SkiaPanel is disposed" }
        lockPicture {
            canvas.drawPicture(it.instance)
        }
    }

    private fun <T : Any> lockPicture(action: (PictureHolder) -> T): T? {
        return synchronized(pictureLock) {
            val picture = picture
            if (picture != null) {
                action(picture)
            } else {
                null
            }
        }
    }

    // Captures current panel content as bitmap.
    fun screenshot(): Bitmap? {
        check(!isDisposed) { "SkiaPanel is disposed" }
        return lockPicture { picture ->
            val store = Bitmap()
            val ci = ColorInfo(
                ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB
            )
            store.setImageInfo(ImageInfo(ci, picture.width, picture.height))
            store.allocN32Pixels(picture.width, picture.height)
            val canvas = Canvas(store)
            canvas.drawPicture(picture.instance)
            store.setImmutable()
            store
        }
    }

    override fun getAccessibleContext(): AccessibleContext {
        // DirectSurface routes accessibility through the heavyweight canvas; SwingComposited (its own
        // surface) uses the supplied provider, falling back to the panel's own context.
        if (!isDirect) {
            accessibleContextProvider?.let { return it.invoke(this) }
        }
        if (accessibleContext == null) {
            accessibleContext = AccessibleSkiaPanel()
        }
        return accessibleContext
    }

    @Suppress("RedundantInnerClassModifier")
    protected inner class AccessibleSkiaPanel : AccessibleJComponent() {
        override fun getAccessibleRole(): AccessibleRole {
            return AccessibleRole.PANEL
        }
    }
}

/**
 * Disable showing the window title bar (DirectSurface only).
 */
@ExperimentalSkikoApi
fun SkiaPanel.disableTitleBar(customHeaderHeight: Float) {
    backedLayer?.disableTitleBar(customHeaderHeight)
}

/**
 * Request to show emoji and symbols popup.
 */
fun orderEmojiAndSymbolsPopup() {
    platformOperations.orderEmojiAndSymbolsPopup()
}

internal fun defaultFPSCounter(
    component: Component
): FPSCounter? = with(SkikoProperties) {
    if (!fpsEnabled) return@with null

    val refreshRate by lazy { component.graphicsConfiguration.device.displayMode.refreshRate }
    FPSCounter(
        periodSeconds = fpsPeriodSeconds,
        showLongFrames = fpsLongFramesShow,
        getLongFrameMillis = { fpsLongFramesMillis ?: (1.5 * 1000 / refreshRate) },
        logOnTick = true
    )
}

private fun adjustSizeToContentScale(contentScale: Float, value: Int): Int {
    val scaled = value * contentScale
    val diff = scaled - floor(scaled)
    return if (diff > 0.4f && diff < 0.6f) {
        value + 1
    } else {
        value
    }
}

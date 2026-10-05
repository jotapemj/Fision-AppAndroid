package com.jotapeveloper.fision.audio3d

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import io.github.sceneview.SceneView

/**
 * SceneView with a render budget.
 *
 * - **Render gate**: SceneView drives its frame loop from the host
 *   lifecycle, so it ran on every vsync whenever the player was resumed,
 *   even with the scene hidden. Here it gets its own lifecycle, RESUMED
 *   only while the host is resumed AND the view is really visible
 *   (own visibility, ancestors and window). Hidden scene = no frames.
 * - **Frame rate**: up to [ACTIVE_FPS] while something moves (touch,
 *   swing, animations: [keepActive] / [continuousActive]); [IDLE_FPS] when
 *   the scene rests. Changes nobody announced still show, at the idle rate.
 */
class FisionSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : SceneView(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        private const val ACTIVE_FPS = 60
        private const val IDLE_FPS = 10
        private const val ACTIVE_PERIOD_NS = 1_000_000_000L / ACTIVE_FPS
        private const val IDLE_PERIOD_NS = 1_000_000_000L / IDLE_FPS
        /** Vsync jitter tolerance, so 60 fps does not slip to 40 on 120 Hz. */
        private const val VSYNC_SLACK_NS = 2_000_000L
        /** Full rate kept after the last touch or announced change. */
        private const val ACTIVE_HOLD_MS = 1_500L
        /** Full rate on resume: warm-up, IBL re-apply and reveal. */
        private const val RESUME_HOLD_MS = 2_500L
    }

    private inner class GateOwner : LifecycleOwner {
        override val lifecycle: Lifecycle get() = gate
    }

    // LifecycleRegistry only holds its owner weakly: keep a strong reference.
    private val gateOwner = GateOwner()
    private val gate: LifecycleRegistry = LifecycleRegistry(gateOwner)

    private var host: Lifecycle? = null
    private var hostResumed = false
    private var hostDestroyed = false
    private var shown = false

    private val hostObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> hostResumed = true
            Lifecycle.Event.ON_PAUSE -> hostResumed = false
            Lifecycle.Event.ON_DESTROY -> hostDestroyed = true
            else -> {}
        }
        updateGate()
    }

    /** Full rate while true (e.g. stems swinging). */
    var continuousActive = false

    private var activeUntilNanos = 0L
    private var lastRenderNanos = 0L

    init {
        // SceneView only adopts the host lifecycle when none is set.
        lifecycle = gate
        gate.currentState = Lifecycle.State.CREATED
    }

    /** Renders at the full rate for the next [ms] milliseconds. */
    fun keepActive(ms: Long = ACTIVE_HOLD_MS) {
        activeUntilNanos = maxOf(activeUntilNanos, System.nanoTime() + ms * 1_000_000L)
    }

    private fun updateGate() {
        if (gate.currentState == Lifecycle.State.DESTROYED) return
        val target = when {
            hostDestroyed -> Lifecycle.State.DESTROYED
            hostResumed && shown -> Lifecycle.State.RESUMED
            else -> Lifecycle.State.CREATED
        }
        if (target == gate.currentState) return
        if (target == Lifecycle.State.RESUMED) {
            keepActive(RESUME_HOLD_MS)
            lastRenderNanos = 0L
        }
        gate.currentState = target
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        host = findViewTreeLifecycleOwner()?.lifecycle
        host?.addObserver(hostObserver)
    }

    override fun onDetachedFromWindow() {
        host?.removeObserver(hostObserver)
        host = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        updateGate()
    }

    override fun onFrame(frameTimeNanos: Long) {
        val active = continuousActive || frameTimeNanos < activeUntilNanos
        val period = if (active) ACTIVE_PERIOD_NS else IDLE_PERIOD_NS
        if (lastRenderNanos != 0L && frameTimeNanos - lastRenderNanos < period - VSYNC_SLACK_NS) {
            return
        }
        lastRenderNanos = frameTimeNanos
        super.onFrame(frameTimeNanos)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        keepActive()
        return super.onTouchEvent(event)
    }
}

package com.example.spheresweeper.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import com.example.spheresweeper.logic.SphereGeometry
import com.example.spheresweeper.math.Quaternion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns raw pointer input on the ball into rotation, zoom and game moves.
 *
 * - One finger beyond touch slop: trackball rotation, with inertia after release.
 * - Two fingers: pinch zoom; the gesture can no longer be a tap.
 * - Double-tap mode: a single tap acts after [DOUBLE_TAP_MILLIS] ([GameViewModel.singleTap]: reveal,
 *   or the scope highlight of a revealed far cell) unless a second tap arrives, which flags a covered
 *   cell or chords a revealed number. The pending tap is held while the next finger is down and
 *   executed as soon as that gesture turns out not to be a tap.
 * - Long-press mode: a tap acts immediately ([GameViewModel.tapImmediate]); holding a covered cell
 *   still for [LONG_PRESS_MILLIS] toggles its flag.
 * - Both modes: holding a revealed number still for [LONG_PRESS_MILLIS] flags the covered cells of
 *   its scope when they must all be mines ([GameViewModel.longPress]).
 */
class SphereInputController(
    private val vm: GameViewModel,
    private val geometry: SphereGeometry,
    private val scope: CoroutineScope,
) {
    var flagByLongPress: Boolean = false
    var haptics: Haptics? = null

    /** Same margins the renderer uses, so hit-testing matches what is drawn. */
    var viewInsets: ViewInsets = ViewInsets.ZERO

    private val deltaRotation = Quaternion()
    private val hitScratch = FloatArray(3)

    /** World-space angular velocity in radians per second (axis * speed). */
    private val velocity = FloatArray(3)
    private var inertiaJob: Job? = null

    // Single tap waiting for the double-tap window to pass.
    private var pendingJob: Job? = null
    private var pendingCell = -1
    private var pendingGame = 0
    private var pendingX = 0f
    private var pendingY = 0f

    // Pending tap frozen while a new finger is down (possible second tap).
    private var heldCell = -1
    private var heldGame = 0
    private var heldX = 0f
    private var heldY = 0f

    suspend fun handleGestures(pointerScope: PointerInputScope) {
        pointerScope.awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            // A touch that catches the spinning ball only stops it; it is never a tap.
            val caughtSpin = stopInertia()
            holdPendingTap()

            val slop = viewConfiguration.touchSlop
            val downX = down.position.x
            val downY = down.position.y
            val downTime = down.uptimeMillis
            var lastEventTime = downTime
            var trackedId: PointerId = down.id
            var potentialTap = !caughtSpin
            var dragging = false
            var multiTouch = false
            var lastMoveTime = downTime
            var finished = false

            try {
                while (true) {
                    val event: PointerEvent? = if (potentialTap) {
                        val remaining = LONG_PRESS_MILLIS - (lastEventTime - downTime)
                        if (remaining <= 0L) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                    } else {
                        awaitPointerEvent()
                    }

                    if (event == null) {
                        // Finger held still long enough: never a tap any more.
                        potentialTap = false
                        releaseHeldTap()
                        // Both modes: auto flag on a revealed number; a flag on a covered cell in long-press mode.
                        val cell = pick(downX, downY, size.width.toFloat(), size.height.toFloat())
                        if (cell >= 0) haptics?.perform(vm.longPress(cell, vm.gameId))
                        continue
                    }

                    val changes = event.changes
                    if (changes.isNotEmpty()) lastEventTime = changes[0].uptimeMillis

                    var pressedCount = 0
                    var primary: PointerInputChange? = null
                    for (index in changes.indices) {
                        val change = changes[index]
                        if (change.pressed) {
                            pressedCount++
                            if (primary == null) primary = change
                        }
                    }

                    if (pressedCount == 0) {
                        finished = true
                        val up = if (changes.isNotEmpty()) changes[0] else null
                        if (up != null) up.consume()
                        if (potentialTap && !multiTouch) {
                            val x = up?.position?.x ?: downX
                            val y = up?.position?.y ?: downY
                            onTap(x, y, size.width.toFloat(), size.height.toFloat(), slop * DOUBLE_TAP_SLOP_FACTOR)
                        } else {
                            releaseHeldTap()
                            if (dragging && !multiTouch) {
                                val idle = (up?.uptimeMillis ?: lastMoveTime) - lastMoveTime
                                if (idle < INERTIA_IDLE_MILLIS) startInertia(size.width, size.height) else clearVelocity()
                            }
                        }
                        break
                    }

                    if (pressedCount >= 2) {
                        if (!multiTouch) {
                            multiTouch = true
                            potentialTap = false
                            releaseHeldTap()
                            clearVelocity()
                        }
                        val zoomChange = event.calculateZoom()
                        if (zoomChange != 1f && zoomChange > 0f) vm.updateZoom(vm.zoom * zoomChange)
                        for (index in changes.indices) changes[index].consume()
                        continue
                    }

                    val change = primary ?: continue
                    if (change.id != trackedId) {
                        // A different finger is left after a pinch: track it without a jump.
                        trackedId = change.id
                        change.consume()
                        continue
                    }
                    if (!dragging && !multiTouch) {
                        val tx = change.position.x - downX
                        val ty = change.position.y - downY
                        if (tx * tx + ty * ty > slop * slop) {
                            dragging = true
                            potentialTap = false
                            releaseHeldTap()
                        }
                    }
                    if (dragging || multiTouch) {
                        val delta = change.positionChangeIgnoreConsumed()
                        val dt = change.uptimeMillis - change.previousUptimeMillis
                        rotateByDrag(delta.x, delta.y, dt, size.width, size.height)
                        lastMoveTime = change.uptimeMillis
                    }
                    change.consume()
                }
            } finally {
                // Gesture aborted (input restarted or composable left): drop the frozen tap silently.
                if (!finished) dropHeldTap()
            }
        }
    }

    // ------------------------------------------------------------------ taps

    private fun pick(x: Float, y: Float, width: Float, height: Float): Int =
        SphereCamera.pickCell(geometry, vm.orientation, vm.zoom, width, height, x, y, viewInsets, hitScratch)

    private fun onTap(x: Float, y: Float, width: Float, height: Float, doubleTapSlop: Float) {
        val cell = pick(x, y, width, height)
        if (flagByLongPress) {
            dropHeldTap()
            if (cell >= 0) haptics?.perform(vm.tapImmediate(cell, vm.gameId))
            return
        }
        if (heldCell >= 0) {
            val dx = x - heldX
            val dy = y - heldY
            val sameSpot = cell == heldCell || dx * dx + dy * dy <= doubleTapSlop * doubleTapSlop
            if (cell >= 0 && sameSpot) {
                val target = heldCell
                val game = heldGame
                dropHeldTap()
                haptics?.perform(vm.doubleTap(target, game))
                return
            }
            // Second tap elsewhere: the first one was a plain single tap.
            releaseHeldTap()
        }
        if (cell >= 0) schedulePendingTap(cell, x, y)
    }

    private fun schedulePendingTap(cell: Int, x: Float, y: Float) {
        pendingJob?.cancel()
        pendingCell = cell
        pendingGame = vm.gameId
        pendingX = x
        pendingY = y
        pendingJob = scope.launch {
            delay(DOUBLE_TAP_MILLIS)
            val target = pendingCell
            val game = pendingGame
            pendingJob = null
            pendingCell = -1
            if (target >= 0) haptics?.perform(vm.singleTap(target, game))
        }
    }

    /** Freezes an active pending tap while a new finger is down. */
    private fun holdPendingTap() {
        val job = pendingJob
        if (job != null && job.isActive && pendingCell >= 0) {
            job.cancel()
            heldCell = pendingCell
            heldGame = pendingGame
            heldX = pendingX
            heldY = pendingY
        }
        pendingJob = null
        pendingCell = -1
    }

    /** Executes the frozen tap as a single tap. */
    private fun releaseHeldTap() {
        val cell = heldCell
        if (cell < 0) return
        val game = heldGame
        heldCell = -1
        haptics?.perform(vm.singleTap(cell, game))
    }

    private fun dropHeldTap() {
        heldCell = -1
    }

    // ------------------------------------------------------------------ rotation

    private fun rotateByDrag(dx: Float, dy: Float, dtMillis: Long, width: Int, height: Int) {
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-3f) return
        val radius = SphereCamera.silhouetteRadius(width.toFloat(), height.toFloat(), vm.zoom, viewInsets)
        if (radius <= 0f) return
        val angle = len / radius
        // Screen drag (dx, dy) is world (dx, -dy, 0); the axis z x drag = (dy, dx, 0) in world space.
        val ax = dy / len
        val ay = dx / len
        applyRotation(ax, ay, 0f, angle)

        if (dtMillis > 0L) {
            val speed = (angle / (dtMillis / 1000f)).coerceAtMost(MAX_SPEED)
            val k = VELOCITY_SMOOTHING
            velocity[0] = velocity[0] * (1f - k) + ax * speed * k
            velocity[1] = velocity[1] * (1f - k) + ay * speed * k
            velocity[2] = velocity[2] * (1f - k)
        }
    }

    private fun applyRotation(ax: Float, ay: Float, az: Float, angle: Float) {
        deltaRotation.setFromAxisAngle(ax, ay, az, angle)
        vm.orientation.preMultiply(deltaRotation).normalize()
        vm.notifyOrientationChanged()
    }

    private fun clearVelocity() {
        velocity[0] = 0f
        velocity[1] = 0f
        velocity[2] = 0f
    }

    /** Stops the inertia animation; returns true if the ball was still spinning. */
    private fun stopInertia(): Boolean {
        val job = inertiaJob
        val speedSq = velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2]
        val wasSpinning = job != null && job.isActive && speedSq > CATCH_SPEED * CATCH_SPEED
        job?.cancel()
        inertiaJob = null
        clearVelocity()
        return wasSpinning
    }

    private fun startInertia(width: Int, height: Int) {
        inertiaJob?.cancel()
        if (width <= 0 || height <= 0) return
        inertiaJob = scope.launch {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                val vx = velocity[0]
                val vy = velocity[1]
                val vz = velocity[2]
                val speed = sqrt(vx * vx + vy * vy + vz * vz)
                if (speed < MIN_SPEED) break
                applyRotation(vx / speed, vy / speed, vz / speed, speed * dt)
                val decay = exp(-INERTIA_DECAY * dt)
                velocity[0] = vx * decay
                velocity[1] = vy * decay
                velocity[2] = vz * decay
            }
            clearVelocity()
        }
    }

    private companion object {
        const val DOUBLE_TAP_MILLIS = 250L
        const val LONG_PRESS_MILLIS = 400L
        const val DOUBLE_TAP_SLOP_FACTOR = 6f
        const val INERTIA_IDLE_MILLIS = 80L
        const val INERTIA_DECAY = 2.8f
        const val VELOCITY_SMOOTHING = 0.5f
        const val MAX_SPEED = 25f
        const val MIN_SPEED = 0.05f
        const val CATCH_SPEED = 0.4f
    }
}

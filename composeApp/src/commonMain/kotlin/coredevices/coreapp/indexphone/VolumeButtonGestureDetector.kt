package coredevices.coreapp.indexphone

import coredevices.ring.service.button.RingGesture

data class VolumeButtonGestureTiming(
    val holdThresholdMillis: Long = 600,
    val multiClickGapMillis: Long = 600,
) {
    init {
        require(holdThresholdMillis > 0)
        require(multiClickGapMillis > 0)
    }
}

sealed interface VolumeButtonGestureSignal {
    data class Recognized(val gesture: RingGesture) : VolumeButtonGestureSignal
    data class HoldReleased(val gesture: RingGesture) : VolumeButtonGestureSignal
}

/**
 * Converts a monotonic stream of button down/up times into the existing Index gesture vocabulary.
 *
 * The caller advances time at [nextDeadlineMillis] so a hold can be recognized before release and
 * a completed click sequence can be emitted after its multi-click window closes. This class has no
 * Android, audio, recording, or Compose dependency.
 */
class VolumeButtonGestureDetector(
    private val timing: VolumeButtonGestureTiming = VolumeButtonGestureTiming(),
) {
    private var pressedAtMillis: Long? = null
    private var lastReleaseMillis: Long? = null
    private var pendingShortPresses = 0
    private var activeHold: RingGesture? = null
    private var holdClassified = false
    private var lastEventMillis: Long? = null

    val nextDeadlineMillis: Long?
        get() = when {
            pressedAtMillis != null && !holdClassified ->
                pressedAtMillis!! + timing.holdThresholdMillis
            pressedAtMillis == null && pendingShortPresses > 0 ->
                lastReleaseMillis!! + timing.multiClickGapMillis
            else -> null
        }

    val waitingForMoreClicks: Boolean
        get() = pressedAtMillis == null && pendingShortPresses > 0

    fun onDown(timeMillis: Long): List<VolumeButtonGestureSignal> {
        val signals = prepareFor(timeMillis)
        if (pressedAtMillis == null) {
            pressedAtMillis = timeMillis
            holdClassified = false
            activeHold = null
        }
        return signals
    }

    fun onUp(timeMillis: Long): List<VolumeButtonGestureSignal> {
        val signals = prepareFor(timeMillis).toMutableList()
        val pressedAt = pressedAtMillis ?: return signals

        if (!holdClassified && timeMillis - pressedAt >= timing.holdThresholdMillis) {
            signals += classifyHold()
        }

        val releasedHold = activeHold
        pressedAtMillis = null
        activeHold = null

        if (holdClassified) {
            holdClassified = false
            pendingShortPresses = 0
            lastReleaseMillis = null
            if (releasedHold != null) {
                signals += VolumeButtonGestureSignal.HoldReleased(releasedHold)
            }
            return signals
        }

        pendingShortPresses += 1
        lastReleaseMillis = timeMillis
        if (pendingShortPresses == MAX_SHORT_PRESSES) {
            signals += VolumeButtonGestureSignal.Recognized(RingGesture.TripleClick)
            pendingShortPresses = 0
            lastReleaseMillis = null
        }
        return signals
    }

    fun onTimeAdvanced(timeMillis: Long): List<VolumeButtonGestureSignal> = prepareFor(timeMillis)

    fun reset() {
        pressedAtMillis = null
        lastReleaseMillis = null
        pendingShortPresses = 0
        activeHold = null
        holdClassified = false
        lastEventMillis = null
    }

    private fun prepareFor(timeMillis: Long): List<VolumeButtonGestureSignal> {
        val previousTime = lastEventMillis
        if (previousTime != null && timeMillis < previousTime) {
            reset()
        }
        lastEventMillis = timeMillis

        if (pressedAtMillis != null && !holdClassified && timeMillis >= nextDeadlineMillis!!) {
            return classifyHold()
        }
        if (pressedAtMillis == null && pendingShortPresses > 0 && timeMillis >= nextDeadlineMillis!!) {
            val gesture = when (pendingShortPresses) {
                1 -> RingGesture.Click
                2 -> RingGesture.DoubleClick
                else -> null
            }
            pendingShortPresses = 0
            lastReleaseMillis = null
            return gesture?.let { listOf(VolumeButtonGestureSignal.Recognized(it)) }.orEmpty()
        }
        return emptyList()
    }

    private fun classifyHold(): List<VolumeButtonGestureSignal> {
        holdClassified = true
        activeHold = when (pendingShortPresses) {
            0 -> RingGesture.Hold
            1 -> RingGesture.ClickHold
            else -> null
        }
        pendingShortPresses = 0
        lastReleaseMillis = null
        return activeHold?.let { listOf(VolumeButtonGestureSignal.Recognized(it)) }.orEmpty()
    }

    private companion object {
        const val MAX_SHORT_PRESSES = 3
    }
}

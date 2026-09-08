package coredevices.coreapp.indexphone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import coredevices.ring.service.button.GestureDestination
import coredevices.ring.service.button.GestureKind
import coredevices.ring.service.button.GestureRoutingPreferences
import coredevices.ring.service.onNextTrack
import coredevices.ring.service.onPlayPause
import coredevices.ring.service.onPreviousTrack
import coredevices.ring.service.onIncreaseVolume

/** Routes one physical button stream through existing Index gesture settings. */
class PhoneGestureDispatcher(
    private val context: Context,
    private val gestureRouting: GestureRoutingPreferences,
    private val tag: String,
    private val dispatchMusic: Boolean = true,
) {
    private val detector = VolumeButtonGestureDetector()
    private val handler = Handler(Looper.getMainLooper())
    private val deadlineRunnable = Runnable {
        detector.nextDeadlineMillis?.let { dispatch(detector.onTimeAdvanced(it)) }
    }

    fun onKeyEvent(action: Int, eventTime: Long) {
        val signals = when (action) {
            KeyEvent.ACTION_DOWN -> detector.onDown(eventTime)
            KeyEvent.ACTION_UP -> detector.onUp(eventTime)
            else -> emptyList()
        }
        dispatch(signals)
        scheduleNextDeadline(eventTime)
    }

    fun reset() {
        handler.removeCallbacks(deadlineRunnable)
        detector.reset()
    }

    private fun scheduleNextDeadline(referenceEventTimeMillis: Long) {
        handler.removeCallbacks(deadlineRunnable)
        detector.nextDeadlineMillis?.let { deadline ->
            handler.postDelayed(
                deadlineRunnable,
                (deadline - referenceEventTimeMillis).coerceAtLeast(0) +
                    if (detector.waitingForMoreClicks) EVENT_DELIVERY_GRACE_MILLIS else 0L,
            )
        }
    }

    private fun dispatch(signals: List<VolumeButtonGestureSignal>) {
        signals.forEach { signal -> when (signal) {
            is VolumeButtonGestureSignal.Recognized -> when (signal.gesture.kind) {
                GestureKind.Music -> if (dispatchMusic) when (gestureRouting.destinationFor(signal.gesture)) {
                    GestureDestination.PlayPause -> onPlayPause()
                    GestureDestination.NextTrack -> onNextTrack()
                    GestureDestination.PreviousTrack -> onPreviousTrack()
                    GestureDestination.IncreaseVolume -> onIncreaseVolume()
                    else -> Unit
                }
                GestureKind.Recording -> {
                    Log.i(tag, "gesture_recognized gesture=${signal.gesture.name}")
                    IndexPhoneRecordingService.startGestureRecording(context, signal.gesture)
                }
            }
            is VolumeButtonGestureSignal.HoldReleased -> if (signal.gesture.kind == GestureKind.Recording) {
                Log.i(tag, "hold_released gesture=${signal.gesture.name}")
                IndexPhoneRecordingService.stopHoldRecording(context)
            }
        } }
    }

    private companion object { const val EVENT_DELIVERY_GRACE_MILLIS = 200L }
}

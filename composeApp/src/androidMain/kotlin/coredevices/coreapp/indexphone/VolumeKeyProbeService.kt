package coredevices.coreapp.indexphone

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import coredevices.ring.service.button.GestureDestination
import coredevices.ring.service.button.GestureKind
import coredevices.ring.service.button.GestureRoutingPreferences
import coredevices.ring.service.onNextTrack
import coredevices.ring.service.onPlayPause
import coredevices.ring.service.onPreviousTrack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives Volume Up events for the Index Phone gesture boundary.
 *
 * While the phone recording service is armed, it consumes the complete Volume Up stream and sends
 * each recognized gesture through the existing Index routing preferences.
 */
class VolumeKeyProbeService : AccessibilityService(), KoinComponent {
    private val gestureRouting: GestureRoutingPreferences by inject()
    private val gestureDetector = VolumeButtonGestureDetector()
    private val handler = Handler(Looper.getMainLooper())
    private var consumingVolumeUpStream = false
    private val deadlineRunnable = Runnable {
        gestureDetector.nextDeadlineMillis?.let { deadline ->
            logSignals(gestureDetector.onTimeAdvanced(deadline))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        clearPendingGesture()
        Log.i(TAG, "probe_service connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false

        val consumeEvent = when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    consumingVolumeUpStream =
                        IndexPhoneRecordingService.isReadyForVolumeGestures()
                }
                consumingVolumeUpStream
            }
            KeyEvent.ACTION_UP -> consumingVolumeUpStream
            else -> consumingVolumeUpStream
        }
        if (!consumeEvent) {
            clearPendingGesture()
            return false
        }

        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> event.action.toString()
        }
        Log.i(
            TAG,
            "volume_up action=$action repeat=${event.repeatCount} " +
                    "eventTime=${event.eventTime} downTime=${event.downTime} " +
                    "interactive=${powerManager?.isInteractive} " +
                    "locked=${keyguardManager?.isKeyguardLocked} " +
                    "indexForeground=${IndexPhoneAppVisibility.isForeground} " +
                    "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}"
        )

        val signals = when (event.action) {
            KeyEvent.ACTION_DOWN -> gestureDetector.onDown(event.eventTime)
            KeyEvent.ACTION_UP -> gestureDetector.onUp(event.eventTime)
            else -> emptyList()
        }
        logSignals(signals)
        scheduleNextDeadline(event.eventTime)

        if (event.action == KeyEvent.ACTION_UP) consumingVolumeUpStream = false

        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        clearPendingGesture()
        Log.i(TAG, "probe_service interrupted")
    }

    override fun onDestroy() {
        clearPendingGesture()
        Log.i(TAG, "probe_service destroyed")
        super.onDestroy()
    }

    private fun clearPendingGesture() {
        handler.removeCallbacks(deadlineRunnable)
        gestureDetector.reset()
        consumingVolumeUpStream = false
    }

    private fun scheduleNextDeadline(referenceEventTimeMillis: Long) {
        handler.removeCallbacks(deadlineRunnable)
        gestureDetector.nextDeadlineMillis?.let { deadline ->
            handler.postDelayed(
                deadlineRunnable,
                (deadline - referenceEventTimeMillis).coerceAtLeast(0) +
                    if (gestureDetector.waitingForMoreClicks) EVENT_DELIVERY_GRACE_MILLIS else 0L,
            )
        }
    }

    private fun logSignals(signals: List<VolumeButtonGestureSignal>) {
        signals.forEach { signal ->
            when (signal) {
                is VolumeButtonGestureSignal.Recognized -> {
                    Log.i(TAG, "gesture_recognized gesture=${signal.gesture.name}")
                    when (signal.gesture.kind) {
                        GestureKind.Music -> when (gestureRouting.destinationFor(signal.gesture)) {
                            GestureDestination.PlayPause -> onPlayPause()
                            GestureDestination.NextTrack -> onNextTrack()
                            GestureDestination.PreviousTrack -> onPreviousTrack()
                            else -> Unit
                        }
                        GestureKind.Recording -> {
                            IndexPhoneRecordingService.startGestureRecording(this, signal.gesture)
                        }
                    }
                }
                is VolumeButtonGestureSignal.HoldReleased -> {
                    Log.i(TAG, "hold_released gesture=${signal.gesture.name}")
                    if (signal.gesture.kind == GestureKind.Recording) {
                        IndexPhoneRecordingService.stopHoldRecording(this)
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "IndexPhoneVolumeProbe"
        private const val EVENT_DELIVERY_GRACE_MILLIS = 200L
    }
}

object IndexPhoneAppVisibility {
    @Volatile
    var isForeground: Boolean = false
}

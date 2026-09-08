package coredevices.coreapp.indexphone

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import coredevices.ring.service.button.GestureRoutingPreferences

/** Receives an opted-in wired/Bluetooth headset transport button with screen off. */
class IndexPhoneMediaButtonBridge(context: Context, gestureRouting: GestureRoutingPreferences) {
    private val dispatcher = PhoneGestureDispatcher(context, gestureRouting, TAG, dispatchMusic = false)
    private val session = MediaSession(context, "IndexPhoneMediaButtonBridge").apply {
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setPlaybackState(
            PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0, 0f).build(),
        )
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return false
                if (event.keyCode !in SUPPORTED_KEYS) return false
                dispatcher.onKeyEvent(event.action, event.eventTime)
                return true
            }
        })
        isActive = true
    }

    fun release() {
        dispatcher.reset()
        session.release()
    }

    private companion object {
        const val TAG = "IndexPhoneMediaButton"
        val SUPPORTED_KEYS = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
}

package coredevices.ring.service

import kotlinx.coroutines.delay


actual fun onPlayPause() {
    //no-op
}

actual fun onNextTrack() {
    //no-op
}

actual fun onPreviousTrack() {
    //no-op
}

actual fun onIncreaseVolume() {
    // Android phone-mode action; iOS button controls are disabled.
}

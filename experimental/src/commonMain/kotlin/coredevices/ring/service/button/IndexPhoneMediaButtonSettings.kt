package coredevices.ring.service.button

import com.russhwolf.settings.Settings

/** Explicit opt-in for the screen-off headset-control bridge. */
const val INDEX_PHONE_MEDIA_BUTTONS_ENABLED = "index_phone_media_buttons_enabled"

fun Settings.indexPhoneMediaButtonsEnabled(): Boolean =
    getBoolean(INDEX_PHONE_MEDIA_BUTTONS_ENABLED, false)

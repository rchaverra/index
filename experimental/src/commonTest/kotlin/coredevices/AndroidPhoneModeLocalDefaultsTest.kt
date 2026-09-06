package coredevices

import com.russhwolf.settings.MapSettings
import coredevices.ring.agent.LlmMode
import coredevices.ring.database.PreferencesImpl
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.models.CactusSTTMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidPhoneModeLocalDefaultsTest {
    @Test
    fun firstInitializationSelectsLocalModes() = runTest {
        val settings = MapSettings()
        val preferences = PreferencesImpl(settings)
        val configHolder = CoreConfigHolder(CoreConfig(), settings, Json)

        applyAndroidPhoneModeLocalDefaults(settings, preferences, configHolder, "recommended-stt")

        assertEquals(LlmMode.LocalOnly, preferences.llmMode.value)
        assertEquals(CactusSTTMode.LocalOnly, configHolder.config.value.sttConfig.mode)
        assertEquals("recommended-stt", configHolder.config.value.sttConfig.modelName)
        assertTrue(settings.getBoolean(ANDROID_PHONE_LOCAL_DEFAULTS_INITIALIZED, false))
    }

    @Test
    fun laterUserChoicesSurviveSubsequentInitialization() = runTest {
        val settings = MapSettings()
        val preferences = PreferencesImpl(settings)
        val configHolder = CoreConfigHolder(CoreConfig(), settings, Json)
        applyAndroidPhoneModeLocalDefaults(settings, preferences, configHolder, "recommended-stt")

        preferences.setLlmMode(LlmMode.RemoteFirst)
        configHolder.update(
            configHolder.config.value.copy(
                sttConfig = configHolder.config.value.sttConfig.copy(
                    mode = CactusSTTMode.PlatformOnly,
                ),
            )
        )

        applyAndroidPhoneModeLocalDefaults(settings, preferences, configHolder, "new-recommended-stt")

        assertEquals(LlmMode.RemoteFirst, preferences.llmMode.value)
        assertEquals(CactusSTTMode.PlatformOnly, configHolder.config.value.sttConfig.mode)
    }
}

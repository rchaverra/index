package coredevices.ring.agent.builtin_servlets.clock

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.AlarmClock
import android.view.accessibility.AccessibilityManager
import org.koin.mp.KoinPlatform
import kotlin.time.Duration

actual suspend fun setTimer(duration: Duration, title: String?, skipUI: Boolean) {
    val context = KoinPlatform.getKoin().get<Context>()
    // Setting a timer launches the clock app's activity. When we run in the background (the common
    // case for the ring), startActivity is silently dropped unless we hold a CompanionDeviceManager
    // association, which grants the background-activity-launch exemption.
    check(context.canLaunchClockActivity()) {
        "Background timers require Index Volume Up accessibility or a device association."
    }
    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, duration.inWholeSeconds.toInt())
        if (skipUI) {
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (title != null) {
            putExtra(AlarmClock.EXTRA_MESSAGE, title)
        }
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    check(context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()) {
        "Couldn't find a Clock app supporting setting timers from other apps."
    }
    context.startActivity(intent)
}

internal fun Context.hasCompanionDeviceAssociation(): Boolean {
    val service = getSystemService(CompanionDeviceManager::class.java) ?: return false
    @Suppress("DEPRECATION")
    return try {
        service.associations.isNotEmpty()
    } catch (e: SecurityException) {
        // Treat an inability to read associations as no association.
        false
    }
}

internal fun Context.canLaunchClockActivity(): Boolean =
    isAppProcessForeground() || hasEnabledOwnAccessibilityService() || hasCompanionDeviceAssociation()

private fun Context.isAppProcessForeground(): Boolean {
    val activityManager = getSystemService(ActivityManager::class.java) ?: return false
    return activityManager.runningAppProcesses
        ?.firstOrNull { it.pid == Process.myPid() }
        ?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

private fun Context.hasEnabledOwnAccessibilityService(): Boolean {
    val accessibilityManager = getSystemService(AccessibilityManager::class.java) ?: return false
    return accessibilityManager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == packageName }
}

package com.example.data.voice.alwayson

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Inspects Android Doze/Battery Optimization state without requesting restricted
 * background exemptions and provides standard Settings intents for optional user control.
 */
object BatteryOptimizationHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: return false
        return runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    fun isDeviceInteractive(context: Context): Boolean {
        val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: return true
        return runCatching { pm.isInteractive }.getOrDefault(true)
    }

    fun createBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun supportsMicrophoneForegroundType(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}

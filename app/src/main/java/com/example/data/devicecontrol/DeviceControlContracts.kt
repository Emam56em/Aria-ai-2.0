package com.example.data.devicecontrol

import com.example.core.localization.AppLanguage

enum class SupportedAppId(val tagValue: String) {
    SETTINGS("SETTINGS"),
    PHONE("PHONE"),
    CALCULATOR("CALCULATOR"),
    CALENDAR("CALENDAR"),
    CAMERA("CAMERA"),
    CLOCK("CLOCK"),
    BROWSER("BROWSER"),
    MESSAGES("MESSAGES"),
    MAPS("MAPS");

    companion object {
        fun fromTag(value: String?): SupportedAppId? =
            entries.firstOrNull { it.tagValue == value }
    }
}

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val isSystemApp: Boolean
)

sealed interface DeviceCommand {
    data class SetFlashlight(val enabled: Boolean) : DeviceCommand
    data object ToggleFlashlight : DeviceCommand
    data class OpenApp(val appId: SupportedAppId) : DeviceCommand
    data class OpenPackage(val packageName: String, val displayName: String? = null) : DeviceCommand
    data class OpenAppByName(val query: String) : DeviceCommand

    fun toActionTag(): String = when (this) {
        is SetFlashlight -> if (enabled) "FLASH_ON" else "FLASH_OFF"
        ToggleFlashlight -> "FLASH_TOGGLE"
        is OpenApp -> "APP:${appId.tagValue}"
        is OpenPackage -> "PKG:$packageName"
        is OpenAppByName -> "NAME:$query"
    }

    companion object {
        fun fromActionTag(tag: String?): DeviceCommand? {
            if (tag.isNullOrBlank()) return null
            return when {
                tag == "FLASH_ON" -> SetFlashlight(true)
                tag == "FLASH_OFF" -> SetFlashlight(false)
                tag == "FLASH_TOGGLE" -> ToggleFlashlight
                tag.startsWith("APP:") -> {
                    SupportedAppId.fromTag(tag.removePrefix("APP:"))?.let { OpenApp(it) }
                }
                tag.startsWith("PKG:") -> {
                    val pkg = tag.removePrefix("PKG:").trim()
                    if (pkg.isNotEmpty()) OpenPackage(pkg) else null
                }
                tag.startsWith("NAME:") -> {
                    val query = tag.removePrefix("NAME:").trim()
                    if (query.isNotEmpty()) OpenAppByName(query) else null
                }
                else -> null
            }
        }
    }
}

sealed interface DeviceActionResult {
    val messageEn: String
    val messageBn: String

    fun localizedMessage(language: AppLanguage): String =
        if (language == AppLanguage.BENGALI) messageBn else messageEn

    data class Success(
        override val messageEn: String,
        override val messageBn: String,
        val actionTag: String
    ) : DeviceActionResult

    data class Unavailable(
        override val messageEn: String,
        override val messageBn: String
    ) : DeviceActionResult

    data class Failure(
        override val messageEn: String,
        override val messageBn: String
    ) : DeviceActionResult
}

interface DeviceControlCapability {
    val capabilityId: String
    fun isSupported(): Boolean
}

data class SupportedAppInfo(
    val appId: SupportedAppId,
    val isAvailable: Boolean
)

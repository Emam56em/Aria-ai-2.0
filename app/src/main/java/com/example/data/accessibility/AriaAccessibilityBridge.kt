package com.example.data.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

enum class AccessibilityQuickAction {
    BACK,
    HOME,
    RECENTS,
    NOTIFICATIONS
}

data class AccessibilityServiceState(
    val isEnabledInSystem: Boolean = false,
    val isServiceConnected: Boolean = false,
    val activePackageName: String? = null
)

/**
 * Coordinates strictly user-authorized accessibility actions when the optional
 * [AriaAccessibilityService] has been explicitly enabled by the user in Android Settings.
 */
object AriaAccessibilityBridge {

    private val _state = MutableStateFlow(AccessibilityServiceState())
    val state: StateFlow<AccessibilityServiceState> = _state.asStateFlow()

    private var serviceRef: WeakReference<AriaAccessibilityService>? = null

    internal fun onServiceConnected(service: AriaAccessibilityService) {
        serviceRef = WeakReference(service)
        _state.value = _state.value.copy(
            isEnabledInSystem = true,
            isServiceConnected = true
        )
    }

    internal fun onServiceDisconnected() {
        serviceRef = null
        _state.value = _state.value.copy(
            isServiceConnected = false,
            activePackageName = null
        )
    }

    internal fun onWindowPackageChanged(packageName: String?) {
        if (!packageName.isNullOrBlank()) {
            _state.value = _state.value.copy(activePackageName = packageName)
        }
    }

    fun refreshSystemStatus(context: Context) {
        val enabled = isServiceEnabledInSystem(context)
        val connected = serviceRef?.get() != null && enabled
        _state.value = _state.value.copy(
            isEnabledInSystem = enabled,
            isServiceConnected = connected
        )
    }

    fun isServiceEnabledInSystem(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java)
        val enabledServices = am?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ) ?: emptyList()

        val targetPackage = context.packageName
        val matchInManager = enabledServices.any {
            it.resolveInfo?.serviceInfo?.packageName == targetPackage &&
                it.resolveInfo?.serviceInfo?.name == AriaAccessibilityService::class.java.name
        }
        if (matchInManager) return true

        val settingValue = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()

        return settingValue.contains(AriaAccessibilityService::class.java.name)
    }

    fun performAuthorizedGlobalAction(action: AccessibilityQuickAction): Boolean {
        val service = serviceRef?.get() ?: return false
        val globalAction = when (action) {
            AccessibilityQuickAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            AccessibilityQuickAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            AccessibilityQuickAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            AccessibilityQuickAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        }
        return service.performGlobalAction(globalAction)
    }

    /**
     * Performs a user-authorized click on a non-password UI node whose visible text or
     * contentDescription matches [targetText]. Only runs when explicitly triggered by the user.
     */
    fun performAuthorizedNodeClick(targetText: String): Boolean {
        val service = serviceRef?.get() ?: return false
        val root = service.rootInActiveWindow ?: return false
        val cleanTarget = targetText.trim()
        if (cleanTarget.isEmpty()) return false

        return findAndClickNode(root, cleanTarget)
    }

    private fun findAndClickNode(node: AccessibilityNodeInfo?, targetText: String): Boolean {
        if (node == null) return false
        // Never inspect or interact with password fields
        if (node.isPassword) return false

        val textMatch = node.text?.toString()?.contains(targetText, ignoreCase = true) == true
        val descMatch = node.contentDescription?.toString()?.contains(targetText, ignoreCase = true) == true

        if ((textMatch || descMatch) && node.isEnabled) {
            var clickableTarget: AccessibilityNodeInfo? = node
            while (clickableTarget != null && !clickableTarget.isClickable) {
                clickableTarget = clickableTarget.parent
            }
            if (clickableTarget != null && clickableTarget.isEnabled && !clickableTarget.isPassword) {
                return clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }

        for (i in 0 until node.childCount) {
            if (findAndClickNode(node.getChild(i), targetText)) {
                return true
            }
        }
        return false
    }
}

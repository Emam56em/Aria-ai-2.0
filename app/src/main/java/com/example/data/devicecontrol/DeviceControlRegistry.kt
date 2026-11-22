package com.example.data.devicecontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class DeviceControlRegistry(
    val flashlight: FlashlightController,
    val appLauncher: AppLauncherController
) {
    val flashlightState: StateFlow<FlashlightState> = flashlight.state

    fun getSupportedApps(): List<SupportedAppInfo> = appLauncher.getSupportedApps()

    suspend fun loadAllLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        appLauncher.getAllLaunchableApps()
    }

    fun execute(
        command: DeviceCommand,
        cachedInstalledApps: List<InstalledAppInfo>? = null
    ): DeviceActionResult = when (command) {
        is DeviceCommand.SetFlashlight -> flashlight.setTorchEnabled(command.enabled)
        DeviceCommand.ToggleFlashlight -> flashlight.toggleTorch()
        is DeviceCommand.OpenApp -> appLauncher.launchApp(command.appId)
        is DeviceCommand.OpenPackage -> appLauncher.launchByPackageName(
            packageName = command.packageName,
            displayName = command.displayName
        )
        is DeviceCommand.OpenAppByName -> appLauncher.launchByNameOrQuery(
            query = command.query,
            cachedApps = cachedInstalledApps
        )
    }

    fun release() {
        flashlight.release()
    }
}

package com.example.data.devicecontrol

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FlashlightState(
    val isSupported: Boolean = false,
    val isEnabled: Boolean = false
)

class FlashlightController(context: Context) : DeviceControlCapability {

    override val capabilityId: String = "hardware.flashlight"

    private val cameraManager: CameraManager? =
        context.applicationContext.getSystemService(CameraManager::class.java)

    private val flashCameraId: String? = findFlashCameraId()

    private val _state = MutableStateFlow(
        FlashlightState(
            isSupported = flashCameraId != null,
            isEnabled = false
        )
    )
    val state: StateFlow<FlashlightState> = _state.asStateFlow()

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == flashCameraId) {
                _state.value = _state.value.copy(isSupported = true, isEnabled = enabled)
            }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == flashCameraId) {
                _state.value = _state.value.copy(isEnabled = false)
            }
        }
    }

    init {
        if (flashCameraId != null) {
            try {
                cameraManager?.registerTorchCallback(
                    torchCallback,
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to register TorchCallback", e)
            }
        }
    }

    override fun isSupported(): Boolean = flashCameraId != null

    fun setTorchEnabled(enabled: Boolean): DeviceActionResult {
        val manager = cameraManager
        val cameraId = flashCameraId
        if (manager == null || cameraId == null) {
            return DeviceActionResult.Unavailable(
                messageEn = "Flashlight hardware is not available on this device.",
                messageBn = "এই ডিভাইসে ফ্ল্যাশলাইট হার্ডওয়্যার পাওয়া যায়নি।"
            )
        }

        return try {
            manager.setTorchMode(cameraId, enabled)
            _state.value = _state.value.copy(isSupported = true, isEnabled = enabled)
            if (enabled) {
                DeviceActionResult.Success(
                    messageEn = "Flashlight turned ON.",
                    messageBn = "ফ্ল্যাশলাইট চালু করা হয়েছে।",
                    actionTag = "FLASH_OFF"
                )
            } else {
                DeviceActionResult.Success(
                    messageEn = "Flashlight turned OFF.",
                    messageBn = "ফ্ল্যাশলাইট বন্ধ করা হয়েছে।",
                    actionTag = "FLASH_ON"
                )
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to toggle flashlight", e)
            DeviceActionResult.Failure(
                messageEn = "Could not access the camera flash right now.",
                messageBn = "এই মুহূর্তে ক্যামেরা ফ্ল্যাশ ব্যবহার করা যাচ্ছে না।"
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission restriction toggling flashlight", e)
            DeviceActionResult.Failure(
                messageEn = "System security policy blocked flashlight access.",
                messageBn = "সিস্টেম নিরাপত্তা নীতির কারণে ফ্ল্যাশলাইট চালু করা যায়নি।"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected flashlight error", e)
            DeviceActionResult.Failure(
                messageEn = "Unable to toggle flashlight: ${e.localizedMessage ?: "Unknown error"}",
                messageBn = "ফ্ল্যাশলাইট নিয়ন্ত্রণ করা যায়নি।"
            )
        }
    }

    fun toggleTorch(): DeviceActionResult = setTorchEnabled(!_state.value.isEnabled)

    fun release() {
        try {
            cameraManager?.unregisterTorchCallback(torchCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister torch callback", e)
        }
    }

    private fun findFlashCameraId(): String? {
        val manager = cameraManager ?: return null
        return try {
            manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facingBack = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
                hasFlash && facingBack
            } ?: manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect camera flash hardware", e)
            null
        }
    }

    companion object {
        private const val TAG = "FlashlightController"
    }
}

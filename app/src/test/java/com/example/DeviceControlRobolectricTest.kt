package com.example

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.accessibility.AriaAccessibilityBridge
import com.example.data.devicecontrol.AppLauncherController
import com.example.data.devicecontrol.DeviceActionResult
import com.example.data.devicecontrol.DeviceCommand
import com.example.data.devicecontrol.DeviceControlRegistry
import com.example.data.devicecontrol.FlashlightController
import com.example.data.devicecontrol.FuzzyAppMatcher
import com.example.data.devicecontrol.InstalledAppInfo
import com.example.data.devicecontrol.LocalCommandParser
import com.example.data.devicecontrol.SupportedAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceControlRobolectricTest {

    @Test
    fun `calculator resolves and launches via CATEGORY_APP_CALCULATOR without CATEGORY_DEFAULT`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = shadowOf(app.packageManager)

        val calcComponent = ComponentName(
            "com.example.mockcalc",
            "com.example.mockcalc.CalculatorActivity"
        )
        registerExportedActivity(
            shadowPm = shadowPm,
            component = calcComponent,
            categories = listOf(Intent.CATEGORY_APP_CALCULATOR, Intent.CATEGORY_LAUNCHER)
        )

        val controller = AppLauncherController(app)
        val apps = controller.getSupportedApps()
        val calcInfo = apps.first { it.appId == SupportedAppId.CALCULATOR }
        assertTrue("Calculator should be reported available", calcInfo.isAvailable)

        val result = controller.launchApp(SupportedAppId.CALCULATOR)
        assertTrue("Expected Success but got $result", result is DeviceActionResult.Success)

        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull("Expected startActivity to be called for Calculator", startedIntent)
        assertEquals(calcComponent, startedIntent.component)
        assertTrue((startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun `calculator resolves and launches via known Google Calculator package fallback`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = shadowOf(app.packageManager)

        val googleCalcComponent = ComponentName(
            "com.google.android.calculator",
            "com.android.calculator2.Calculator"
        )
        registerExportedActivity(
            shadowPm = shadowPm,
            component = googleCalcComponent,
            categories = listOf(Intent.CATEGORY_LAUNCHER)
        )

        val controller = AppLauncherController(app)
        val result = controller.launchApp(SupportedAppId.CALCULATOR)
        assertTrue("Expected Success but got $result", result is DeviceActionResult.Success)

        val startedIntent = shadowOf(app).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(googleCalcComponent, startedIntent.component)
    }

    @Test
    fun `universal app discovery lists launchable apps and launches by package or fuzzy bilingual name`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = shadowOf(app.packageManager)

        val youtubeComponent = ComponentName(
            "com.google.android.youtube",
            "com.google.android.youtube.HomeActivity"
        )
        val spotifyComponent = ComponentName(
            "com.spotify.music",
            "com.spotify.music.MainActivity"
        )
        registerExportedActivity(
            shadowPm = shadowPm,
            component = youtubeComponent,
            categories = listOf(Intent.CATEGORY_LAUNCHER)
        )
        registerExportedActivity(
            shadowPm = shadowPm,
            component = spotifyComponent,
            categories = listOf(Intent.CATEGORY_LAUNCHER)
        )

        val controller = AppLauncherController(app)
        val allApps = controller.getAllLaunchableApps()
        assertTrue(allApps.any { it.packageName == "com.google.android.youtube" })
        assertTrue(allApps.any { it.packageName == "com.spotify.music" })

        // Launch directly by package name
        val pkgResult = controller.launchByPackageName("com.spotify.music", "Spotify")
        assertTrue(pkgResult is DeviceActionResult.Success)
        assertEquals(spotifyComponent, shadowOf(app).nextStartedActivity.component)

        // Launch via Bengali fuzzy alias ("ইউটিউব")
        val cachedList = listOf(
            InstalledAppInfo("YouTube", "com.google.android.youtube", youtubeComponent.className, false),
            InstalledAppInfo("Spotify", "com.spotify.music", spotifyComponent.className, false)
        )
        val bnResult = controller.launchByNameOrQuery("ইউটিউব", cachedList)
        assertTrue(bnResult is DeviceActionResult.Success)
        assertEquals(youtubeComponent, shadowOf(app).nextStartedActivity.component)

        // Launch via English typo ("spotfy")
        val typoResult = controller.launchByNameOrQuery("spotfy", cachedList)
        assertTrue(typoResult is DeviceActionResult.Success)
        assertEquals(spotifyComponent, shadowOf(app).nextStartedActivity.component)
    }

    @Test
    fun `fuzzy matcher filters and transliterates Bengali app queries`() {
        val sampleApps = listOf(
            InstalledAppInfo("YouTube", "com.google.android.youtube", "Home", true),
            InstalledAppInfo("WhatsApp", "com.whatsapp", "Main", false),
            InstalledAppInfo("Spotify", "com.spotify.music", "Main", false),
            InstalledAppInfo("Telegram", "org.telegram.messenger", "Main", false)
        )

        val ytMatch = FuzzyAppMatcher.findBestMatch("ইউটিউব খোলো", sampleApps)
        assertEquals("com.google.android.youtube", ytMatch?.app?.packageName)

        val waMatch = FuzzyAppMatcher.findBestMatch("হোয়াটসঅ্যাপ", sampleApps)
        assertEquals("com.whatsapp", waMatch?.app?.packageName)

        val translitMatch = FuzzyAppMatcher.findBestMatch("স্পটিফাই", sampleApps)
        assertEquals("com.spotify.music", translitMatch?.app?.packageName)

        val filtered = FuzzyAppMatcher.filterApps("টেলিগ্রাম", sampleApps)
        assertEquals(1, filtered.size)
        assertEquals("org.telegram.messenger", filtered.first().packageName)
    }

    @Test
    fun `local command parser handles universal open commands while sparing Gemini questions`() {
        assertEquals(
            DeviceCommand.OpenAppByName("YouTube"),
            LocalCommandParser.parse("Open YouTube")
        )
        assertEquals(
            DeviceCommand.OpenAppByName("হোয়াটসঅ্যাপ"),
            LocalCommandParser.parse("হোয়াটসঅ্যাপ খুলে দাও")
        )
        assertEquals(
            DeviceCommand.OpenPackage("com.whatsapp"),
            LocalCommandParser.parse("open com.whatsapp")
        )
        assertEquals(
            DeviceCommand.OpenApp(SupportedAppId.MAPS),
            LocalCommandParser.parse("ম্যাপস খোলো")
        )
        assertEquals(
            DeviceCommand.OpenApp(SupportedAppId.CAMERA),
            LocalCommandParser.parse("Open Camera")
        )
        assertNull(LocalCommandParser.parse("How do I open YouTube in Android?"))
        assertNull(LocalCommandParser.parse("ইউটিউব কীভাবে কাজ করে?"))
    }

    @Test
    fun `maps camera flashlight and accessibility boundaries remain intact`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = shadowOf(app.packageManager)

        // Register Maps & Camera
        val mapsComponent = ComponentName("com.google.android.apps.maps", "com.google.android.maps.MapsActivity")
        registerExportedActivity(
            shadowPm = shadowPm,
            component = mapsComponent,
            categories = listOf(Intent.CATEGORY_APP_MAPS, Intent.CATEGORY_LAUNCHER)
        )
        val cameraComponent = ComponentName("com.android.camera2", "com.android.camera.CameraActivity")
        val camInfo = shadowPm.addActivityIfNotPresent(cameraComponent).apply {
            exported = true
            enabled = true
        }
        shadowPm.addOrUpdateActivity(camInfo)
        shadowPm.addIntentFilterForActivity(
            cameraComponent,
            IntentFilter(MediaStore.ACTION_IMAGE_CAPTURE).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        )

        // Register Flashlight
        val cameraManager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(characteristics).set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        shadowOf(characteristics).set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        shadowOf(cameraManager).addCamera("0", characteristics)

        val flashlight = FlashlightController(app)
        val controller = AppLauncherController(app)
        val registry = DeviceControlRegistry(flashlight, controller)

        assertTrue(registry.execute(DeviceCommand.OpenApp(SupportedAppId.MAPS)) is DeviceActionResult.Success)
        assertEquals(mapsComponent, shadowOf(app).nextStartedActivity.component)

        assertTrue(registry.execute(DeviceCommand.OpenApp(SupportedAppId.CAMERA)) is DeviceActionResult.Success)
        assertEquals(cameraComponent, shadowOf(app).nextStartedActivity.component)

        assertTrue(registry.execute(DeviceCommand.SetFlashlight(true)) is DeviceActionResult.Success)
        assertTrue(registry.flashlightState.value.isEnabled)
        assertTrue(registry.execute(DeviceCommand.SetFlashlight(false)) is DeviceActionResult.Success)
        assertFalse(registry.flashlightState.value.isEnabled)

        // Optional Accessibility Service must be disabled by default
        assertFalse(AriaAccessibilityBridge.isServiceEnabledInSystem(app))

        registry.release()
    }

    private fun registerExportedActivity(
        shadowPm: ShadowPackageManager,
        component: ComponentName,
        categories: List<String>
    ) {
        val activityInfo = shadowPm.addActivityIfNotPresent(component).apply {
            exported = true
            enabled = true
        }
        shadowPm.addOrUpdateActivity(activityInfo)

        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            categories.forEach { addCategory(it) }
        }
        shadowPm.addIntentFilterForActivity(component, filter)
    }
}

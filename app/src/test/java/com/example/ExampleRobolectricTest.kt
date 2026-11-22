package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.localization.AppLanguage
import com.example.core.localization.stringsFor
import com.example.data.devicecontrol.DeviceCommand
import com.example.data.devicecontrol.LocalCommandParser
import com.example.data.devicecontrol.SupportedAppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Aria AI 2.0", appName)
    }

    @Test
    fun `bilingual strings resolve English and Bengali`() {
        assertEquals("Aria AI 2.0", stringsFor(AppLanguage.ENGLISH).appName)
        assertEquals("আরিয়া এআই ২.০", stringsFor(AppLanguage.BENGALI).appName)
    }

    @Test
    fun `local command parser handles English and Bengali commands`() {
        assertEquals(
            DeviceCommand.SetFlashlight(true),
            LocalCommandParser.parse("Turn on the flashlight")
        )
        assertEquals(
            DeviceCommand.SetFlashlight(false),
            LocalCommandParser.parse("টর্চ বন্ধ করো")
        )
        assertEquals(
            DeviceCommand.OpenApp(SupportedAppId.CALCULATOR),
            LocalCommandParser.parse("ক্যালকুলেটর খোলো")
        )
        assertNull(LocalCommandParser.parse("Tell me a story about Dhaka"))
    }
}

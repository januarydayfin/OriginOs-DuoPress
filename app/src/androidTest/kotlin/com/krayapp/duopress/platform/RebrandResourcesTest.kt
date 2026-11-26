package com.krayapp.duopress.platform

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.krayapp.duopress.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RebrandResourcesTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun appAndHandlerLabelsUseDuoPressInBothLanguages() {
        for (locale in listOf(Locale.forLanguageTag("ru"), Locale.ENGLISH)) {
            val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
            val localized = app.createConfigurationContext(config)

            assertEquals("DuoPress", localized.getString(R.string.app_name))
            assertEquals("DuoPress", localized.getString(R.string.swipe_activity_label))
            assertEquals("DuoPress", localized.getString(R.string.header_title))
        }
    }

    @Test
    fun launcherBackgroundIsBlueInDayAndNightModes() {
        for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }

            assertEquals(Color.parseColor("#415FFF"), app.createConfigurationContext(config).getColor(R.color.ic_launcher_bg))
        }
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    @Test
    fun themedLauncherIconRendersPhoneOutlineAndTwoPressMarksWithoutABackground() {
        val launcher = app.getDrawable(R.drawable.ic_launcher) as AdaptiveIconDrawable
        val layer = checkNotNull(launcher.monochrome) { "Missing themed icon layer" }
        val bitmap = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        layer.setBounds(0, 0, bitmap.width, bitmap.height)
        layer.draw(Canvas(bitmap))

        assertEquals("The phone interior is transparent", 0, Color.alpha(bitmap.getPixel(48, 54)))
        assertEquals("Left edge of the phone", 255, Color.alpha(bitmap.getPixel(34, 54)))
        assertEquals("First press mark", 255, Color.alpha(bitmap.getPixel(78, 42)))
        assertEquals("Second press mark", 255, Color.alpha(bitmap.getPixel(78, 54)))
        assertEquals("No background outside the sign", 0, Color.alpha(bitmap.getPixel(10, 10)))
    }

    @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.N_MR1)
    @Test
    fun api25LoadsTheLegacyLauncherVector() {
        assertTrue(app.getDrawable(R.drawable.ic_launcher) is VectorDrawable)
    }
}

package com.rescueauth.v2.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Check the icon loaded from the installed APK, including Android's adaptive crop. */
@RunWith(AndroidJUnit4::class)
class LauncherIconRenderTest {
    @Test fun packagedIconRetainsVisibleBackgroundGradient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        val bitmap = Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        try {
            icon.setBounds(0,0,bitmap.width,bitmap.height)
            icon.draw(Canvas(bitmap))
            val start = bitmap.getPixel(46,46)
            val end = bitmap.getPixel(209,209)
            assertEquals(255,Color.alpha(start)); assertEquals(255,Color.alpha(end))
            val difference = abs(Color.red(start)-Color.red(end)) +
                abs(Color.green(start)-Color.green(end)) + abs(Color.blue(start)-Color.blue(end))
            assertTrue("Packaged adaptive background looks flat: difference=$difference", difference >= 60)
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
                val file = File(output,"branding/launcher-native.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        } finally { bitmap.recycle() }
    }
}

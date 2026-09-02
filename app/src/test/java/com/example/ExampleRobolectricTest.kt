package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.player.VideoUrlResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("SARNAS", appName)
  }

  @Test
  fun `resolve google drive link`() {
    val driveUrl = "https://drive.google.com/file/d/1A2B3C4D5E/view?usp=sharing"
    val result = VideoUrlResolver.resolve(driveUrl)
    assertTrue(result.isSuccess)
    val resolved = result.getOrThrow()
    assertTrue(resolved.isGoogleDrive)
    assertEquals("https://drive.google.com/uc?export=download&id=1A2B3C4D5E", resolved.directPlayableUrl)
  }
}

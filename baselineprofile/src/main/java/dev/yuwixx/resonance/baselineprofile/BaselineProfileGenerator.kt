package dev.yuwixx.resonance.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records the hot paths users hit first: cold start, the library list, and the player. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    // The profiled build has its own application ID, passed in by the Gradle build.
    private val packageName = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId instrumentation argument missing")

    @Test
    fun generate() = rule.collect(packageName = packageName, includeInStartupProfile = true) {
        device.executeShellCommand("pm grant $packageName android.permission.READ_MEDIA_AUDIO")
        device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        pressHome()
        startActivityAndWait()

        // Fresh install: walk through setup. "Skip" comes first because the Last.fm step's
        // Continue stays disabled until an account is linked. Pages animate in, so a button
        // found a moment ago can be stale by the time it is clicked; look it up again then.
        repeat(8) {
            device.waitForIdle()
            val next = device.findObject(By.text("Skip"))
                ?: device.findObject(By.text("Continue").enabled(true))
                ?: device.findObject(By.text("Get Started"))
                ?: return@repeat
            try {
                next.click()
            } catch (_: StaleObjectException) {
                return@repeat
            }
        }

        // Library tab → scroll the song list.
        device.wait(Until.findObject(By.text("Library")), 5_000)?.click()
        device.waitForIdle()
        // The list is rebuilt while the library loads, so look it up again for every fling.
        fun fling(direction: Direction) {
            runCatching {
                val list = device.wait(Until.findObject(By.scrollable(true)), 5_000) ?: return
                list.setGestureMargin(device.displayWidth / 5)
                list.fling(direction)
            }
            device.waitForIdle()
        }
        repeat(3) { fling(Direction.DOWN) }
        repeat(2) { fling(Direction.UP) }

        // Shuffle-play the library (starts playback, shows the mini player), then open the
        // full player from it.
        device.findObject(By.desc("Shuffle Play"))?.click()
        device.wait(Until.hasObject(By.desc("Pause")), 5_000)
        device.findObject(By.desc("Pause"))?.click() // keep the profile run quiet
        device.findObject(By.textContains("Library"))?.let { device.waitForIdle() }
        device.swipe(device.displayWidth / 2, device.displayHeight - 300, device.displayWidth / 2, device.displayHeight / 3, 20)
        device.waitForIdle()
        device.pressBack()
    }
}

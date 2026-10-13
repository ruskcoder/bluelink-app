package com.ruskcoder.messages.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code a real session runs: a cold start, the conversation list scrolled, and a
 * conversation opened and scrolled back through.
 *
 * Run against an install that is signed in and has a few conversations, or the conversation half has
 * nothing to open and only the startup half is recorded.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupListAndConversation() = rule.collect(
        packageName = "com.ruskcoder.messages",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // The conversation list.
        device.wait(Until.hasObject(By.scrollable(true)), 5_000)
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()

            // The first conversation in it, and back through its history.
            list.children.firstOrNull { it.isClickable }?.let { row ->
                row.click()
                device.waitForIdle()
                device.wait(Until.hasObject(By.scrollable(true)), 5_000)
                device.findObject(By.scrollable(true))?.let { thread ->
                    thread.setGestureMargin(device.displayWidth / 5)
                    // A reversed list: flinging "up" walks back into older messages.
                    repeat(3) {
                        thread.fling(Direction.UP)
                        device.waitForIdle()
                    }
                }
                device.pressBack()
                device.waitForIdle()
            }
        }
    }
}

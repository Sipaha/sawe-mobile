package ru.sipaha.sawe.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sanity check that Robolectric + Roborazzi can render a Compose composable
 * to a PNG file. If this fails the rig is broken and subsequent UI snapshot
 * tests will not work — fix here first.
 *
 * NOTE: The Roborazzi Gradle plugin is NOT applied (incompatible with AGP 9),
 * so we call [captureRoboImage] directly with an explicit [RoborazziOptions].
 * The captured PNG is written relative to the module root (app/).
 *
 * This is the ONE call site that deliberately stays in
 * [RoborazziTaskType.Record]: it proves the rig can render and write a PNG,
 * it is not guarding a golden, and its output is intentionally rewritten on
 * every run. Every other capture site compares against a committed golden and
 * therefore uses [RoborazziTaskType.Verify], which fails the test on a
 * mismatch (plain `Compare` never throws).
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class RoborazziSanityTest {

    @Test
    fun rig_renders_a_text_and_writes_png() {
        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/RoborazziSanityTest_rig_renders_a_text_and_writes_png.png",
            roborazziOptions = RoborazziOptions(
                // RECORDING on purpose — see the class KDoc. Not a golden.
                taskType = RoborazziTaskType.Record,
            ),
        ) {
            MaterialTheme {
                Surface { Text("Roborazzi sanity OK") }
            }
        }
    }
}

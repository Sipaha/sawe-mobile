package ru.sipaha.spkremote.app.ui

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
 * so we call [captureRoboImage] directly with an explicit
 * [RoborazziOptions] specifying [RoborazziTaskType.Record]. The captured PNG
 * is written relative to the module root (app/).
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
                taskType = RoborazziTaskType.Record,
            ),
        ) {
            MaterialTheme {
                Surface { Text("Roborazzi sanity OK") }
            }
        }
    }
}

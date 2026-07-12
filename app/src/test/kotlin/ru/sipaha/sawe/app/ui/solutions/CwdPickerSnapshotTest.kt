package ru.sipaha.sawe.app.ui.solutions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.sipaha.sawe.core.SolutionMember

/**
 * Render verification for the new-session working-directory picker after the
 * solution root was dropped from its options: the field shows the first member
 * project, and "Solution root" is nowhere on screen.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class CwdPickerSnapshotTest {

    @Test
    fun member_projects_only() {
        val options = cwdOptionsFor(
            listOf(
                SolutionMember("sawe", "/home/u/.spk/sol/sawe", "ready"),
                SolutionMember("spk-editor-mobile", "/home/u/.spk/sol/spk-editor-mobile", "ready"),
            ),
        )

        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/CwdPicker_member_projects_only.png",
            // Compare against the committed golden (flip to Record to re-baseline),
            // matching WorkspaceScreenSnapshotTest.
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Compare),
        ) {
            MaterialTheme {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CwdPicker(
                            options = options,
                            selectedPath = options.first().path,
                            enabled = true,
                            onSelected = {},
                        )
                    }
                }
            }
        }
    }
}

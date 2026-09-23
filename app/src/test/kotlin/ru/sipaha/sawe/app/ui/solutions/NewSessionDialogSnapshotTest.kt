package ru.sipaha.sawe.app.ui.solutions

import androidx.compose.material3.MaterialTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.sipaha.sawe.app.vm.UiData
import ru.sipaha.sawe.core.AgentSummary

/** Guards the provider-only creation dialog against reintroducing setup fields. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class NewSessionDialogSnapshotTest {
    @Test
    fun provider_only() {
        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/NewSessionDialog_provider_only.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Verify),
        ) {
            MaterialTheme {
                NewSessionDialogContent(
                    agentsState = UiData.Loaded(listOf(
                        AgentSummary("codex-native", "Codex"),
                        AgentSummary("claude-acp", "Claude"),
                    )),
                    selectedAgentId = "codex-native",
                    inFlight = false,
                    autoOpened = false,
                    onSelected = {},
                    onDismiss = {},
                    onCreate = {},
                )
            }
        }
    }
}

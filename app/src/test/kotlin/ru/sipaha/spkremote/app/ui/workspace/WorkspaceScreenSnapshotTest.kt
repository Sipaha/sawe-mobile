package ru.sipaha.spkremote.app.ui.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.sipaha.spkremote.app.vm.OpenSessionVM
import ru.sipaha.spkremote.app.vm.OpenSolutionVM
import ru.sipaha.spkremote.core.SessionStateDto

/**
 * Roborazzi golden screenshots for the unified Workspace screen.
 *
 * Two captures:
 *   - populated: two solutions with sessions in Running/Idle/Errored states
 *   - empty: the EmptyState shown when there are no open solutions
 *
 * No Roborazzi Gradle plugin is applied (incompatible with AGP 9), so we
 * call [captureRoboImage] directly with an explicit [RoborazziOptions]. To
 * (re-)record goldens, flip [taskType] back to [RoborazziTaskType.Record].
 * See [ru.sipaha.spkremote.app.ui.RoborazziSanityTest] for the rig template.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class WorkspaceScreenSnapshotTest {

    private val taskType = RoborazziTaskType.Compare

    @Test
    fun populated_two_solutions_with_sessions() {
        val solutions = listOf(
            OpenSolutionVM(
                id = "voxelcraft",
                name = "voxelcraft",
                memberCount = 3,
                sessions = listOf(
                    OpenSessionVM(
                        id = "se1",
                        title = "Refactor renderer",
                        state = SessionStateDto.Running(startedAtMs = 0L),
                        lastActivityAt = 1_000L,
                        totalTokens = 2_400L,
                        maxTokens = 200_000L,
                    ),
                    OpenSessionVM(
                        id = "se2",
                        title = "Sprite editor",
                        state = SessionStateDto.Idle,
                        lastActivityAt = 3_600_000L,
                        totalTokens = null,
                        maxTokens = null,
                    ),
                ),
            ),
            OpenSolutionVM(
                id = "spk",
                name = "SPK Solutions",
                memberCount = 5,
                sessions = listOf(
                    OpenSessionVM(
                        id = "se3",
                        title = "Mobile redesign",
                        state = SessionStateDto.Errored("oops"),
                        lastActivityAt = 300_000L,
                        totalTokens = null,
                        maxTokens = null,
                    ),
                ),
            ),
        )

        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/WorkspaceScreen_populated_two_solutions.png",
            roborazziOptions = RoborazziOptions(taskType = taskType),
        ) {
            MaterialTheme {
                Surface {
                    WorkspaceListContent(solutions = solutions)
                }
            }
        }
    }

    @Test
    fun empty_state() {
        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/WorkspaceScreen_empty.png",
            roborazziOptions = RoborazziOptions(taskType = taskType),
        ) {
            MaterialTheme {
                Surface {
                    EmptyState()
                }
            }
        }
    }
}

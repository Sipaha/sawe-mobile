package ru.sipaha.sawe.app.ui.workspace

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
import ru.sipaha.sawe.app.vm.ClosedSolutionRow
import ru.sipaha.sawe.app.vm.OpenSessionVM
import ru.sipaha.sawe.app.vm.OpenSolutionVM
import ru.sipaha.sawe.core.SessionStateDto

/**
 * Roborazzi golden screenshots for the unified Workspace screen.
 *
 * Two captures:
 *   - populated: two solutions with sessions in Running/Idle/Errored states
 *   - empty: the EmptyState shown when there are no open solutions
 *
 * No Roborazzi Gradle plugin is applied (incompatible with AGP 9), so there
 * is no `verifyRoborazzi` task and we call [captureRoboImage] directly with
 * an explicit [RoborazziOptions]. To (re-)record goldens, flip [taskType] to
 * [RoborazziTaskType.Record].
 * See [ru.sipaha.sawe.app.ui.RoborazziSanityTest] for the rig template.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class WorkspaceScreenSnapshotTest {

    // VERIFYING mode: every capture below is checked against its committed
    // golden and the test FAILS on a mismatch. This used to be
    // [RoborazziTaskType.Compare], which merely writes *_compare.png /
    // *_actual.png into build/outputs/roborazzi/ and returns normally — with
    // no Roborazzi Gradle plugin there is no verify task either, so nothing
    // ever asserted and the goldens were decorative. Verify still writes the
    // same diff artifacts, it just throws afterwards.
    private val taskType = RoborazziTaskType.Verify

    @Test
    fun populated_two_solutions_with_sessions() {
        val solutions = listOf(
            OpenSolutionVM(
                id = 1L,
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
                        agentId = "claude-acp",
                    ),
                    OpenSessionVM(
                        id = "se2",
                        title = "Sprite editor",
                        state = SessionStateDto.Idle,
                        lastActivityAt = 3_600_000L,
                        totalTokens = null,
                        maxTokens = null,
                        agentId = "codex-native",
                    ),
                ),
            ),
            OpenSolutionVM(
                id = 2L,
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
                        // An agent this app has no brand for: the row must
                        // fall back to the neutral glyph, not a wrong logo.
                        agentId = "some-future-agent",
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

    /**
     * The state a workspace snapshot failure lands in. Carries a Retry button
     * now — without one the screen is a dead end until the user backgrounds
     * and resumes the app (N-12).
     */
    @Test
    fun error_state_with_retry() {
        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/WorkspaceScreen_error_with_retry.png",
            roborazziOptions = RoborazziOptions(taskType = taskType),
        ) {
            MaterialTheme {
                Surface {
                    ErrorState(msg = "Request timed out after 30s")
                }
            }
        }
    }

    @Test
    fun picker_sheet_populated() {
        val rows = listOf(
            ClosedSolutionRow(
                id = 1L,
                name = "ML experiments",
                memberCount = 4,
                lastOpenedAt = "2 days ago",
            ),
            ClosedSolutionRow(
                id = 2L,
                name = "Old prototype",
                memberCount = 1,
                lastOpenedAt = "6 months ago",
            ),
        )

        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/WorkspaceScreenSnapshotTest_picker_sheet_populated.png",
            roborazziOptions = RoborazziOptions(taskType = taskType),
        ) {
            MaterialTheme {
                Surface {
                    ClosedSolutionsPickerSheetContent(
                        rows = rows,
                        onOpen = {},
                        onDelete = {},
                    )
                }
            }
        }
    }
}

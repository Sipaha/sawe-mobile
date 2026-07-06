package ru.sipaha.sawe.app.ui.solutions

import androidx.compose.foundation.layout.Column
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
import ru.sipaha.sawe.core.StreamDto
import ru.sipaha.sawe.core.StreamIdDto
import ru.sipaha.sawe.core.StreamKindDto
import ru.sipaha.sawe.core.StreamStateDto

/**
 * Phase 5 render verification: the per-source-streams tab strip is driven by the
 * `streams` descriptor list (Main + teammates), NOT the retired
 * `active_subagents`. This offscreen Roborazzi capture proves the migrated
 * [SubagentTabStrip] renders a Main tab plus one pill per teammate stream, with
 * the selected tab highlighted — the device-independent stand-in for the
 * on-device screenshot gate.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class StreamTabStripSnapshotTest {

    private fun stream(id: StreamIdDto, kind: StreamKindDto, label: String, seq: Long) =
        StreamDto(
            id = id,
            kind = kind,
            label = label,
            state = StreamStateDto.Live,
            seq = seq,
            totalCount = 3,
        )

    @Test
    fun main_plus_two_teammate_streams() {
        val streams = listOf(
            stream(StreamIdDto.Main, StreamKindDto.MAIN, "Main", 42L),
            stream(StreamIdDto.Teammate("toolu_a1"), StreamKindDto.TEAMMATE, "Refactor renderer", 51L),
            stream(StreamIdDto.Teammate("toolu_b2"), StreamKindDto.TEAMMATE, "Write tests", 58L),
        )

        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/StreamTabStrip_main_plus_two_teammates.png",
            // Compare against the committed golden (flip to Record to re-baseline),
            // matching WorkspaceScreenSnapshotTest.
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Compare),
        ) {
            MaterialTheme {
                Surface {
                    Column {
                        // Main selected: the Main pill is highlighted, two teammate
                        // pills demuxed straight from `streams`.
                        SubagentTabStrip(
                            streams = streams,
                            selected = StreamIdDto.Main,
                            onSelect = {},
                        )
                        // A second strip with a teammate selected, so the capture
                        // also shows the active-teammate-tab state.
                        SubagentTabStrip(
                            streams = streams,
                            selected = StreamIdDto.Teammate("toolu_a1"),
                            onSelect = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun main_teammate_shell() {
        // v4 (wire schema): background shells + async agents fold onto
        // `session.streams` — a shell rides as `kind:shell`. It flows through
        // the same generic [SubagentTabStrip] as any other stream (no kind
        // filter), rendering as one more pill alongside Main + teammates.
        val streams = listOf(
            stream(StreamIdDto.Main, StreamKindDto.MAIN, "Main", 42L),
            stream(StreamIdDto.Teammate("toolu_a1"), StreamKindDto.TEAMMATE, "Refactor renderer", 51L),
            stream(StreamIdDto.Shell("sh-1"), StreamKindDto.SHELL, "bash: cargo test", 61L),
        )

        captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/StreamTabStrip_main_teammate_shell.png",
            // Compare against the committed golden (flip to Record to re-baseline),
            // matching WorkspaceScreenSnapshotTest.
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Compare),
        ) {
            MaterialTheme {
                Surface {
                    Column {
                        SubagentTabStrip(
                            streams = streams,
                            selected = StreamIdDto.Main,
                            onSelect = {},
                        )
                        // Shell tab selected: the shell pill is highlighted,
                        // proving the shell stream is selectable like any other.
                        SubagentTabStrip(
                            streams = streams,
                            selected = StreamIdDto.Shell("sh-1"),
                            onSelect = {},
                        )
                    }
                }
            }
        }
    }
}

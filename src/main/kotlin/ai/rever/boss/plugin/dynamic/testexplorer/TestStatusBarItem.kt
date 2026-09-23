package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.StatusBarAlignment
import ai.rever.boss.plugin.api.StatusBarItemProvider
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Loader
import compose.icons.feathericons.XCircle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What the status-bar item says, or nothing at all. */
internal enum class TestStatusTone { RUNNING, PASSED, FAILING }

internal data class TestStatusLabel(
    val text: String,
    val tone: TestStatusTone,
)

/**
 * The status-bar label for the latest run, or null when there is nothing to say.
 *
 * Nothing before the first run: an item that only says "no tests run yet" costs space in a bar shared
 * with every other plugin and tells no one anything. Nothing, too, for a run that produced no test
 * results (a scoped run with nothing to run, or no runner found) - the panel says why.
 */
internal fun testStatusLabel(
    report: TestRunReport?,
    running: Boolean,
): TestStatusLabel? {
    val counts = report?.counts
    return when {
        running -> TestStatusLabel("Running tests...", TestStatusTone.RUNNING)
        counts == null -> null
        counts.failing > 0 -> TestStatusLabel("${counts.failing} failing", TestStatusTone.FAILING)
        counts.allPassed -> TestStatusLabel("${counts.passed} passed", TestStatusTone.PASSED)
        else -> null
    }
}

/**
 * The latest run's outcome in the host status bar, so red tests stay visible after the panel is
 * closed - and a click brings the panel back.
 *
 * [openPanel] reveals the Test Explorer panel in this window, through the host's
 * `PanelEventProvider.openPanel` - the same effect as clicking the panel's sidebar icon, except that
 * an already open panel stays open. Null when the host offers no way to reveal a panel, and then the
 * item is a plain label rather than a button that does nothing.
 */
internal class TestStatusBarItem(
    private val session: TestExplorerSession,
    private val openPanel: (suspend () -> Unit)?,
) : StatusBarItemProvider {
    override val itemId: String = "ai.rever.boss.plugin.dynamic.testexplorer:status"
    override val alignment: StatusBarAlignment = StatusBarAlignment.RIGHT

    @Composable
    override fun Content() {
        val report by session.report.collectAsState()
        val running by session.isRunning.collectAsState()
        val scope = rememberCoroutineScope()
        val label = testStatusLabel(report, running) ?: return
        val (icon, tint) =
            when (label.tone) {
                TestStatusTone.RUNNING -> FeatherIcons.Loader to BossThemeColors.TextMuted
                TestStatusTone.PASSED -> FeatherIcons.CheckCircle to BossThemeColors.SuccessColor
                TestStatusTone.FAILING -> FeatherIcons.XCircle to BossThemeColors.ErrorColor
            }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .clickable(enabled = openPanel != null, onClickLabel = "Open Test Explorer") {
                        openPanel?.let { open -> scope.launch { openContained(open) } }
                    }.padding(horizontal = 6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = "Tests: ${label.text}", tint = tint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label.text, color = tint, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/**
 * Runs the host's panel reveal, and keeps any failure in it here.
 *
 * The click's coroutine belongs to the status bar's composition, whose exception handler is the
 * host's own: an exception escaping it is uncaught in the host UI, which a desktop Compose app can
 * treat as fatal. A status-bar label must never be able to close BOSS, whatever the host call does,
 * so a failure is logged and the click simply does nothing.
 */
@Suppress("TooGenericExceptionCaught") // Deliberately total: nothing from the host call may escape.
private suspend fun openContained(open: suspend () -> Unit) {
    try {
        open()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Created here, not held in a file-level val: the logger needs the host's SLF4J, and a field
        // would load it with this file - including in unit tests of the pure label function above.
        BossLogger
            .forComponent("TestExplorer")
            .warn(LogCategory.UI, "Could not open the Test Explorer panel from the status bar", error = e)
    }
}

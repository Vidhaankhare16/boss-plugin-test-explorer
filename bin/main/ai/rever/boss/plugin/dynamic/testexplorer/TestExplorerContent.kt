package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCaseResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCounts
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestStatus
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestSuiteResult
import ai.rever.boss.plugin.dynamic.testexplorer.engine.RunMode
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Filter
import compose.icons.feathericons.Play
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Square
import kotlinx.coroutines.launch

/**
 * The Test Explorer panel UI. Reads everything from [TestExplorerSession]'s flows so it stays in
 * lockstep with runs an agent starts through the MCP tools, and writes nothing back except the
 * three actions (run, rerun failed, stop).
 */
@Composable
fun TestExplorerContent(session: TestExplorerSession) {
    BossTheme {
        val report by session.report.collectAsState()
        val running by session.isRunning.collectAsState()
        val projectMissing by session.projectMissing.collectAsState()
        val output by session.output.collectAsState()
        val scope = rememberCoroutineScope()

        var showFailedOnly by remember { mutableStateOf(false) }
        var showLog by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BossThemeColors.BackgroundColor)
                .padding(8.dp),
        ) {
            Header(
                running = running,
                hasFailures = report?.failures?.isNotEmpty() == true,
                onRun = { scope.launch { session.start(RunMode.ALL) } },
                onRerunFailed = { scope.launch { session.start(RunMode.FAILED_ONLY) } },
                onStop = { session.stop() },
            )

            Spacer(Modifier.size(8.dp))
            SummaryLine(report = report, running = running, projectMissing = projectMissing)

            if (report != null) {
                Spacer(Modifier.size(6.dp))
                ViewControls(
                    showFailedOnly = showFailedOnly,
                    onToggleFailedOnly = { showFailedOnly = !showFailedOnly },
                    showLog = showLog,
                    onToggleLog = { showLog = !showLog },
                )
            }

            Spacer(Modifier.size(6.dp))
            Divider(color = BossThemeColors.BorderColor)
            Spacer(Modifier.size(6.dp))

            when {
                showLog -> LogView(output)
                report != null -> TreeView(report!!, showFailedOnly)
                else -> EmptyState(projectMissing)
            }
        }
    }
}

@Composable
private fun Header(
    running: Boolean,
    hasFailures: Boolean,
    onRun: () -> Unit,
    onRerunFailed: () -> Unit,
    onStop: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "TESTS",
            color = BossThemeColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (running) {
            ActionButton(icon = FeatherIcons.Square, label = "Stop", tint = BossThemeColors.ErrorColor, onClick = onStop)
        } else {
            ActionButton(icon = FeatherIcons.Play, label = "Run", tint = BossThemeColors.SuccessColor, onClick = onRun)
            if (hasFailures) {
                Spacer(Modifier.width(4.dp))
                ActionButton(
                    icon = FeatherIcons.RefreshCw,
                    label = "Rerun failed",
                    tint = BossThemeColors.WarningColor,
                    onClick = onRerunFailed,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BossThemeColors.SurfaceColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = BossThemeColors.TextPrimary, fontSize = 11.sp)
    }
}

@Composable
private fun SummaryLine(report: TestRunReport?, running: Boolean, projectMissing: Boolean) {
    val text = when {
        running && report == null -> "Running tests..."
        report == null && projectMissing -> "No project open."
        report == null -> "No run yet. Press Run."
        else -> countsText(report.counts)
    }
    val color = when {
        report == null -> BossThemeColors.TextMuted
        report.counts.failing > 0 -> BossThemeColors.ErrorColor
        report.counts.allPassed -> BossThemeColors.SuccessColor
        else -> BossThemeColors.TextSecondary
    }
    Column {
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        report?.note?.let {
            Spacer(Modifier.size(2.dp))
            Text(it, color = BossThemeColors.WarningColor, fontSize = 11.sp)
        }
        if (running && report != null) {
            Spacer(Modifier.size(2.dp))
            Text("Running...", color = BossThemeColors.TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ViewControls(
    showFailedOnly: Boolean,
    onToggleFailedOnly: () -> Unit,
    showLog: Boolean,
    onToggleLog: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Toggle(icon = FeatherIcons.Filter, label = "Failed only", active = showFailedOnly, onClick = onToggleFailedOnly)
        Spacer(Modifier.width(6.dp))
        Toggle(icon = FeatherIcons.FileText, label = "Output", active = showLog, onClick = onToggleLog)
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) BossThemeColors.AccentColor else BossThemeColors.TextMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

@Composable
private fun TreeView(report: TestRunReport, showFailedOnly: Boolean) {
    val suites = report.suites
        .map { suite -> suite to suite.cases.filter { !showFailedOnly || it.failed } }
        .filter { (_, cases) -> cases.isNotEmpty() }

    if (suites.isEmpty()) {
        Text(
            if (showFailedOnly) "No failing tests." else "No tests found in the reports.",
            color = BossThemeColors.TextMuted,
            fontSize = 12.sp,
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        suites.forEach { (suite, cases) ->
            item(key = "suite:${suite.name}") {
                SuiteRow(suite)
            }
            items(cases, key = { "case:${suite.name}:${it.qualifiedName}" }) { case ->
                CaseRow(case)
            }
        }
    }
}

@Composable
private fun SuiteRow(suite: TestSuiteResult) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(
            text = suite.name.substringAfterLast('.'),
            color = BossThemeColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(text = countsText(suite.counts), color = BossThemeColors.TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun CaseRow(case: TestCaseResult) {
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = case.message != null || case.details != null
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasDetail) { expanded = !expanded }
                .padding(vertical = 2.dp),
        ) {
            StatusDot(case.status)
            Spacer(Modifier.width(6.dp))
            Text(
                text = case.name,
                color = BossThemeColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            if (hasDetail) {
                Icon(
                    imageVector = if (expanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = BossThemeColors.TextMuted,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        if (expanded && hasDetail) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, bottom = 4.dp)
                    .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
                    .padding(6.dp),
            ) {
                case.message?.let { Text(it, color = BossThemeColors.ErrorColor, fontSize = 11.sp) }
                case.details?.let {
                    if (case.message != null) Spacer(Modifier.size(4.dp))
                    Text(
                        text = it,
                        color = BossThemeColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: TestStatus) {
    val color = when (status) {
        TestStatus.PASSED -> BossThemeColors.SuccessColor
        TestStatus.FAILED -> BossThemeColors.ErrorColor
        TestStatus.ERROR -> BossThemeColors.ErrorColor
        TestStatus.SKIPPED -> BossThemeColors.TextMuted
    }
    Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(color))
}

@Composable
private fun LogView(output: List<String>) {
    if (output.isEmpty()) {
        Text("No output yet.", color = BossThemeColors.TextMuted, fontSize = 12.sp)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(output.size) { index ->
            Text(
                text = output[index],
                color = BossThemeColors.TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun EmptyState(projectMissing: Boolean) {
    val message = if (projectMissing) {
        "Open a project in BOSS, then press Run to test it."
    } else {
        "Press Run to test the open project.\n\nDetects Gradle, Maven, and pytest."
    }
    Text(message, color = BossThemeColors.TextMuted, fontSize = 12.sp)
}

private fun countsText(counts: TestCounts): String {
    if (counts.total == 0) return "No tests."
    val parts = buildList {
        add("${counts.passed} passed")
        if (counts.failing > 0) add("${counts.failing} failing")
        if (counts.skipped > 0) add("${counts.skipped} skipped")
    }
    return parts.joinToString(", ")
}

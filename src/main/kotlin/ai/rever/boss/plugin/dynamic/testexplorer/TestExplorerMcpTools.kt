package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestReportText
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.engine.RunMode

/**
 * The `test_*` MCP tools, so an agent in a BOSS terminal can run the project's tests and read
 * structured pass/fail instead of scraping the terminal.
 *
 * The two tools that execute code declare `readOnly = false`. `test_run` and `test_rerun_failed`
 * do not hold the call open for the whole suite: they wait a bounded time and, if the run is still
 * going when that elapses, return a "still running" note and let the agent poll `test_results`.
 * That bound is below the host's own per-call timeout on purpose, so a long suite never turns into
 * a cancelled tool call.
 */
internal class TestExplorerMcpToolProvider(
    override val providerId: String,
    private val session: TestExplorerSession,
    private val boundMillis: Long = DEFAULT_BOUND_MILLIS,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = "test_run",
                description = "Run the open BOSS project's test suite (Gradle, Maven, or pytest) and " +
                    "report how many passed, failed, or were skipped, with the failing tests, their " +
                    "messages, and the file:line each one failed at. A long run returns a 'still running' note; call test_results for the outcome.",
                readOnly = false,
                handler = McpToolHandler { runResult(RunMode.ALL) },
            ),
            McpToolDefinition(
                name = "test_rerun_failed",
                description = "Rerun only the tests that failed in the last run, and report the outcome. " +
                    "Runs the whole suite instead when no previous run has any failures. A long run returns a " +
                    "'still running' note; call test_results for the outcome.",
                readOnly = false,
                handler = McpToolHandler { runResult(RunMode.FAILED_ONLY) },
            ),
            McpToolDefinition(
                name = "test_affected",
                description = "Run only the tests affected by the project's uncommitted changes (staged, " +
                    "unstaged, and untracked, from git): changed test files, and the tests that import a " +
                    "changed source file. Runs everything when a build or test configuration file changed, " +
                    "and says which changed files no test refers to. Use it after an edit for fast feedback, " +
                    "then test_run before finishing. A long run returns a 'still running' note; call " +
                    "test_results for the outcome.",
                readOnly = false,
                handler = McpToolHandler { runResult(RunMode.AFFECTED) },
            ),
            McpToolDefinition(
                name = "test_results",
                description = "Show the result of the most recent test run: counts, exit code, and the " +
                    "failing tests with their messages and the file:line each one failed at. Does not start a run.",
                handler = McpToolHandler { latestResult() },
            ),
            McpToolDefinition(
                name = "test_list",
                description = "List every test from the most recent run with its pass/fail/skip status. " +
                    "Does not start a run.",
                handler = McpToolHandler { listResult() },
            ),
        )

    private suspend fun runResult(mode: RunMode): McpToolResult {
        val report = session.runBounded(mode, boundMillis)
        return when {
            report != null -> McpToolResult(summaryOf(report))
            session.projectMissing.value -> noProject()
            else ->
                McpToolResult(
                    "The test run is still going. Call test_results in a moment for the outcome.",
                )
        }
    }

    private fun latestResult(): McpToolResult {
        val report = session.report.value ?: return notRunYet()
        val prefix = if (session.isRunning.value) "A run is in progress; showing the previous result.\n\n" else ""
        return McpToolResult(prefix + summaryOf(report))
    }

    private fun summaryOf(report: TestRunReport): String =
        TestReportText.summary(report, session.locations.value, session.projectRoot.value)

    private fun listResult(): McpToolResult {
        val report: TestRunReport = session.report.value ?: return notRunYet()
        return McpToolResult(TestReportText.list(report))
    }

    private fun notRunYet(): McpToolResult =
        McpToolResult("No test run yet. Call test_run first.")

    private fun noProject(): McpToolResult =
        McpToolResult("No project is open in BOSS, so there is nothing to test.", isError = true)

    private companion object {
        const val DEFAULT_BOUND_MILLIS = 45_000L
    }
}

package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCaseResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestStatus
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestSuiteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the status bar says about the latest run, and when it says nothing. */
class TestStatusBarItemTest {
    private fun report(vararg statuses: TestStatus) =
        TestRunReport(
            listOf(TestSuiteResult("S", statuses.mapIndexed { i, s -> TestCaseResult("S", "t$i", s, 0.1) })),
            command = "fake",
            exitCode = 0,
        )

    @Test
    fun `nothing is shown before the first run`() {
        assertNull(testStatusLabel(report = null, running = false))
    }

    @Test
    fun `a run in progress says so, even over a previous result`() {
        val label = testStatusLabel(report(TestStatus.FAILED), running = true)

        assertEquals(TestStatusLabel("Running tests...", TestStatusTone.RUNNING), label)
    }

    @Test
    fun `failures and errors are counted together and win over passes`() {
        val label = testStatusLabel(report(TestStatus.PASSED, TestStatus.FAILED, TestStatus.ERROR), running = false)

        assertEquals(TestStatusLabel("2 failing", TestStatusTone.FAILING), label)
    }

    @Test
    fun `an all-green run says how many passed`() {
        val label = testStatusLabel(report(TestStatus.PASSED, TestStatus.PASSED, TestStatus.SKIPPED), running = false)

        assertEquals(TestStatusLabel("2 passed", TestStatusTone.PASSED), label)
    }

    @Test
    fun `a run with no test results shows nothing, leaving the reason to the panel`() {
        val nothingToRun = TestRunReport.empty(command = "(nothing to run)", exitCode = null, note = "No files have changed.")

        assertNull(testStatusLabel(nothingToRun, running = false))
    }
}

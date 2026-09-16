package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCaseResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestReportText
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestStatus
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestSuiteResult
import kotlin.test.Test
import kotlin.test.assertTrue

class TestReportTextTest {

    private fun report(cases: List<TestCaseResult>, exitCode: Int?) =
        TestRunReport(listOf(TestSuiteResult("S", cases)), command = "./gradlew test", exitCode = exitCode)

    @Test
    fun `summary leads with counts and lists failures with their first message line`() {
        val cases =
            listOf(
                TestCaseResult("S", "ok", TestStatus.PASSED, 0.1),
                TestCaseResult("S", "bad", TestStatus.FAILED, 0.2, message = "expected 1\nbut was 2"),
            )
        val text = TestReportText.summary(report(cases, exitCode = 1))

        assertTrue(text.contains("Exit code: 1"))
        assertTrue(text.contains("2 tests: 1 passed, 1 failing, 0 skipped"))
        assertTrue(text.contains("S.bad [FAILED]"))
        assertTrue(text.contains("expected 1"))
        assertTrue(!text.contains("but was 2"), "only the first line of the message should be shown")
    }

    @Test
    fun `summary truncates a large failure list and says how many remain`() {
        val cases = (1..80).map { TestCaseResult("S", "t$it", TestStatus.FAILED, null) }
        val text = TestReportText.summary(report(cases, exitCode = 1), maxFailures = 50)

        assertTrue(text.contains("... and 30 more"))
    }

    @Test
    fun `list renders every case with a padded status`() {
        val cases =
            listOf(
                TestCaseResult("S", "ok", TestStatus.PASSED, null),
                TestCaseResult("S", "skip", TestStatus.SKIPPED, null),
            )
        val text = TestReportText.list(report(cases, exitCode = 0))

        assertTrue(text.contains("[PASSED]"))
        assertTrue(text.contains("S.ok"))
        assertTrue(text.contains("[SKIPPED]"))
    }
}

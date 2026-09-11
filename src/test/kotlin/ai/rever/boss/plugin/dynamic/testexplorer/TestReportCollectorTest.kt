package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestReportCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestReportCollectorTest {

    private val passing =
        """<testsuite name="A"><testcase classname="A" name="ok"/></testsuite>"""
    private val failing =
        """<testsuite name="B"><testcase classname="B" name="bad"><failure message="no"/></testcase></testsuite>"""

    @Test
    fun `aggregates suites across several report files`() {
        val report = TestReportCollector.collect("cmd", exitCode = 1, reportXmls = listOf(passing, failing))

        assertEquals(2, report.suites.size)
        assertEquals(1, report.counts.passed)
        assertEquals(1, report.counts.failing)
        assertEquals(1, report.failures.size)
        assertEquals("B.bad", report.failures.single().qualifiedName)
        assertNull(report.note)
    }

    @Test
    fun `a non-zero exit with no failing test is flagged as a likely build error`() {
        val report = TestReportCollector.collect("cmd", exitCode = 1, reportXmls = emptyList())

        assertTrue(report.suites.isEmpty())
        assertTrue(report.note!!.contains("build failed"))
    }

    @Test
    fun `a run that never started says so`() {
        val report = TestReportCollector.collect("cmd", exitCode = null, reportXmls = emptyList())
        assertTrue(report.note!!.contains("did not start"))
    }

    @Test
    fun `a clean exit with reports carries no note`() {
        val report = TestReportCollector.collect("cmd", exitCode = 0, reportXmls = listOf(passing))
        assertNull(report.note)
        assertTrue(report.counts.allPassed)
    }
}

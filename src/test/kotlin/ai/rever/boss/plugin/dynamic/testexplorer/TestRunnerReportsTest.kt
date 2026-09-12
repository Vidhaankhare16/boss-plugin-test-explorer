package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestFramework
import ai.rever.boss.plugin.dynamic.testexplorer.engine.TestRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The report walk against a real directory tree.
 *
 * The parser is covered by its own unit tests; what these pin is the part that only shows up on
 * disk: that a multi-module build's reports are all found, that a previous run's are not counted,
 * and that unrelated XML sitting in the project is ignored.
 */
class TestRunnerReportsTest {

    @TempDir
    lateinit var projectDir: Path

    private fun runner() = TestRunner(projectDir.toFile(), isWindows = false)

    private fun write(relativePath: String, xml: String, lastModified: Long? = null): File {
        val file = File(projectDir.toFile(), relativePath)
        file.parentFile.mkdirs()
        file.writeText(xml)
        lastModified?.let { file.setLastModified(it) }
        return file
    }

    private fun suite(name: String) =
        """<testsuite name="$name"><testcase classname="$name" name="t"/></testsuite>"""

    @Test
    fun `finds gradle reports in every module of a multi-module build`() {
        val now = System.currentTimeMillis()
        write("build/test-results/test/TEST-Root.xml", suite("Root"), now)
        write("moduleA/build/test-results/test/TEST-A.xml", suite("A"), now)
        write("nested/moduleB/build/test-results/test/TEST-B.xml", suite("B"), now)

        val reports = runner().readReports(TestFramework.GRADLE, File(projectDir.toFile(), "unused"), now - 1_000)

        assertEquals(3, reports.size)
        val names = reports.flatMap { Regex("""name="(\w+)"""").findAll(it).map { m -> m.groupValues[1] } }.toSet()
        assertTrue(names.containsAll(setOf("Root", "A", "B")), "expected every module's suite, got $names")
    }

    @Test
    fun `ignores reports left by a previous run`() {
        val now = System.currentTimeMillis()
        write("build/test-results/test/TEST-Fresh.xml", suite("Fresh"), now)
        // Well outside the grace window, so this belongs to an earlier run.
        write("build/test-results/test/TEST-Stale.xml", suite("Stale"), now - 600_000)

        val reports = runner().readReports(TestFramework.GRADLE, File(projectDir.toFile(), "unused"), now - 1_000)

        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("Fresh"))
    }

    @Test
    fun `ignores xml that is not under a result directory`() {
        val now = System.currentTimeMillis()
        write("pom-ish/config.xml", suite("NotAReport"), now)
        write("build/reports/something.xml", suite("AlsoNotAReport"), now)

        val reports = runner().readReports(TestFramework.GRADLE, File(projectDir.toFile(), "unused"), now - 1_000)

        assertTrue(reports.isEmpty(), "only files under build/test-results are reports")
    }

    @Test
    fun `maven reports are read from surefire and failsafe directories`() {
        val now = System.currentTimeMillis()
        write("target/surefire-reports/TEST-Unit.xml", suite("Unit"), now)
        write("service/target/failsafe-reports/TEST-It.xml", suite("It"), now)

        val reports = runner().readReports(TestFramework.MAVEN, File(projectDir.toFile(), "unused"), now - 1_000)

        assertEquals(2, reports.size)
    }

    @Test
    fun `pytest reads only its own report file`() {
        val now = System.currentTimeMillis()
        write("build/test-results/test/TEST-Ignored.xml", suite("Ignored"), now)
        val report = write(".boss-test-explorer/pytest-report.xml", suite("Pytest"), now)

        val reports = runner().readReports(TestFramework.PYTEST, report, now - 1_000)

        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("Pytest"))
    }

    @Test
    fun `an unrecognised project detects no framework`() {
        write("README.md", "nothing to see")
        assertNull(runner().detectFramework())
    }

    @Test
    fun `a gradle project is detected from its marker file`() {
        write("build.gradle.kts", "")
        assertEquals(TestFramework.GRADLE, runner().detectFramework())
    }
}

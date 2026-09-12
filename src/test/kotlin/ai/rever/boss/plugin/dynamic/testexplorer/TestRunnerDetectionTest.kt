package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCaseResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestFramework
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunnerDetection
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestRunnerDetectionTest {

    @Test
    fun `detects each framework from its marker files`() {
        assertEquals(TestFramework.GRADLE, TestRunnerDetection.detect(setOf("build.gradle.kts", "settings.gradle.kts")))
        assertEquals(TestFramework.GRADLE, TestRunnerDetection.detect(setOf("gradlew", "README.md")))
        assertEquals(TestFramework.MAVEN, TestRunnerDetection.detect(setOf("pom.xml")))
        assertEquals(TestFramework.PYTEST, TestRunnerDetection.detect(setOf("pyproject.toml")))
        assertEquals(TestFramework.PYTEST, TestRunnerDetection.detect(setOf("conftest.py", "setup.cfg")))
    }

    @Test
    fun `gradle and maven win over pytest when both are present`() {
        assertEquals(TestFramework.GRADLE, TestRunnerDetection.detect(setOf("build.gradle.kts", "pyproject.toml")))
        assertEquals(TestFramework.MAVEN, TestRunnerDetection.detect(setOf("pom.xml", "conftest.py")))
    }

    @Test
    fun `an unrecognised project detects nothing`() {
        assertNull(TestRunnerDetection.detect(setOf("README.md", "main.c", "Makefile")))
        assertNull(TestRunnerDetection.detect(emptySet()))
    }

    @Test
    fun `gradle full run uses the platform wrapper`() {
        assertEquals(
            listOf("./gradlew", "test"),
            TestRunnerDetection.fullRunCommand(TestFramework.GRADLE, isWindows = false, reportPath = "x"),
        )
        assertEquals(
            listOf("cmd", "/c", "gradlew.bat", "test"),
            TestRunnerDetection.fullRunCommand(TestFramework.GRADLE, isWindows = true, reportPath = "x"),
        )
    }

    @Test
    fun `gradle rerun adds a tests filter per failing case`() {
        val failures =
            listOf(
                case("com.example.FooTest", "does a thing"),
                case("com.example.BarTest", "does another"),
            )
        val command = TestRunnerDetection.rerunCommand(TestFramework.GRADLE, isWindows = false, failures, "x")

        assertEquals(
            listOf(
                "./gradlew", "test",
                "--tests", "com.example.FooTest.does a thing",
                "--tests", "com.example.BarTest.does another",
            ),
            command,
        )
    }

    @Test
    fun `maven rerun groups methods by simple class name`() {
        val failures =
            listOf(
                case("com.example.FooTest", "a"),
                case("com.example.FooTest", "b"),
                case("com.example.BarTest", "c"),
            )
        val command = TestRunnerDetection.rerunCommand(TestFramework.MAVEN, isWindows = false, failures, "x")

        assertTrue(command.contains("-Dtest=FooTest#a+b,BarTest#c"))
    }

    @Test
    fun `on Windows the command is wrapped in cmd slash c so bat and cmd launchers resolve`() {
        assertEquals(
            listOf("cmd", "/c", "mvn", "test"),
            TestRunnerDetection.fullRunCommand(TestFramework.MAVEN, isWindows = true, reportPath = "x"),
        )
        assertEquals(
            listOf("cmd", "/c", "pytest", "--junitxml=r.xml"),
            TestRunnerDetection.fullRunCommand(TestFramework.PYTEST, isWindows = true, reportPath = "r.xml"),
        )
    }

    @Test
    fun `pytest rerun uses last-failed and the given report path`() {
        val command =
            TestRunnerDetection.rerunCommand(
                TestFramework.PYTEST,
                isWindows = false,
                listOf(case("t", "x")),
                reportPath = "/tmp/r.xml",
            )
        assertEquals(listOf("pytest", "--last-failed", "--junitxml=/tmp/r.xml"), command)
    }

    @Test
    fun `rerun with no failures falls back to a full run`() {
        assertEquals(
            TestRunnerDetection.fullRunCommand(TestFramework.GRADLE, isWindows = false, "x"),
            TestRunnerDetection.rerunCommand(TestFramework.GRADLE, isWindows = false, emptyList(), "x"),
        )
    }

    private fun case(className: String, name: String) =
        TestCaseResult(className, name, TestStatus.FAILED, durationSeconds = null)
}

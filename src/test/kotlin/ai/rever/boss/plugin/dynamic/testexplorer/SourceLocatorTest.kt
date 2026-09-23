package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.SourceFrame
import ai.rever.boss.plugin.dynamic.testexplorer.core.SourceLocator
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCaseResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestStatus
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestSuiteResult
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Finding the line a failing test broke at, from the runner's own report.
 *
 * The traces are shaped like the real thing: an assertion library's frames come first and the test's
 * own frame below them, which is exactly the frame a person wants to land on.
 */
class SourceLocatorTest {
    @TempDir
    lateinit var root: File

    private fun failing(
        className: String,
        details: String,
        status: TestStatus = TestStatus.FAILED,
    ) = TestCaseResult(className, "case", status, 0.1, message = "boom", details = details)

    private fun write(relative: String): File =
        File(root, relative).apply {
            parentFile.mkdirs()
            writeText("// source")
        }

    private val junitTrace =
        """
        org.opentest4j.AssertionFailedError: expected: <4> but was: <5>
            at org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:151)
            at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:145)
            at com.example.calc.CalculatorTest.adds two numbers(CalculatorTest.kt:14)
            at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
        """.trimIndent()

    @Test
    fun `a JVM failure points at the test's own frame, not the assertion library's`() {
        val frame = SourceLocator.frameFor(failing("com.example.calc.CalculatorTest", junitTrace))

        assertEquals(SourceFrame("com/example/calc/CalculatorTest.kt", 14), frame)
    }

    @Test
    fun `a frame in a nested or lambda class of the test still counts as the test's`() {
        val trace = "java.lang.IllegalStateException\n    at com.example.FooTest\$nested\$1.invoke(FooTest.kt:31)"

        assertEquals(SourceFrame("com/example/FooTest.kt", 31), SourceLocator.frameFor(failing("com.example.FooTest", trace)))
    }

    @Test
    fun `a JVM failure is resolved to the file under the module's test sources`() {
        val file = write("app/src/test/kotlin/com/example/calc/CalculatorTest.kt")
        write("app/build/tmp/com/example/calc/CalculatorTest.kt") // build output must never win

        val location = SourceLocator.locate(failing("com.example.calc.CalculatorTest", junitTrace), root)

        assertEquals(file.absolutePath, location?.path)
        assertEquals(14, location?.line)
        assertEquals("app/src/test/kotlin/com/example/calc/CalculatorTest.kt:14", location?.display(root))
    }

    @Test
    fun `a Kotlin file outside its package directory is found by an unambiguous name`() {
        val file = write("src/test/kotlin/CalculatorTest.kt")

        val location = SourceLocator.locate(failing("com.example.calc.CalculatorTest", junitTrace), root)

        assertEquals(file.absolutePath, location?.path)
    }

    @Test
    fun `two files with the same name and neither in the package path are not guessed between`() {
        write("a/CalculatorTest.kt")
        write("b/CalculatorTest.kt")

        assertNull(SourceLocator.locate(failing("com.example.calc.CalculatorTest", junitTrace), root))
    }

    @Test
    fun `a pytest failure points at the frame in the test's own module`() {
        val trace =
            """
            def test_adds():
            >       assert add(2, 2) == 5
            E       assert 4 == 5
            src/calc/helpers.py:3: in add
            tests/test_calc.py:8: AssertionError
            """.trimIndent()

        val frame = SourceLocator.frameFor(failing("tests.test_calc", trace))

        assertEquals(SourceFrame("tests/test_calc.py", 8), frame)
    }

    @Test
    fun `a pytest class-based test still matches its module`() {
        val trace = "tests/test_calc.py:21: AssertionError"

        assertEquals(SourceFrame("tests/test_calc.py", 21), SourceLocator.frameFor(failing("tests.test_calc.TestAdd", trace)))
    }

    @Test
    fun `a pytest path printed with Windows separators is normalised and resolved`() {
        val file = write("tests/test_calc.py")

        val location = SourceLocator.locate(failing("tests.test_calc", "tests\\test_calc.py:8: AssertionError"), root)

        assertEquals(file.absolutePath, location?.path)
        assertEquals(8, location?.line)
    }

    @Test
    fun `a pytest path with a Windows drive letter is still read as a frame`() {
        val frame = SourceLocator.frameFor(failing("tests.test_calc", "C:\\proj\\tests\\test_calc.py:8: AssertionError"))

        assertEquals(SourceFrame("C:/proj/tests/test_calc.py", 8), frame)
    }

    @Test
    fun `passed tests and failures with no frame have no location`() {
        val passed = TestCaseResult("com.example.FooTest", "ok", TestStatus.PASSED, 0.1)

        assertNull(SourceLocator.frameFor(passed))
        assertNull(SourceLocator.frameFor(failing("com.example.FooTest", "timed out after 10s")))
        assertNull(SourceLocator.locate(failing("com.example.calc.CalculatorTest", junitTrace), root), "no such file")
    }

    @Test
    fun `an error is located the same way as a failure`() {
        write("src/test/kotlin/com/example/calc/CalculatorTest.kt")

        val location =
            SourceLocator.locate(failing("com.example.calc.CalculatorTest", junitTrace, TestStatus.ERROR), root)

        assertEquals(14, location?.line)
    }

    @Test
    fun `locateAll keys every located failure by its qualified name`() {
        write("src/test/kotlin/com/example/calc/CalculatorTest.kt")
        val located = failing("com.example.calc.CalculatorTest", junitTrace)
        val unlocated = TestCaseResult("com.example.Other", "x", TestStatus.FAILED, 0.1, message = "no trace")
        val report = TestRunReport(listOf(TestSuiteResult("S", listOf(located, unlocated))), "fake", 1)

        val all = SourceLocator.locateAll(report, root)

        assertEquals(setOf(located.qualifiedName), all.keys)
    }
}

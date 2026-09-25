package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestFramework
import ai.rever.boss.plugin.dynamic.testexplorer.engine.RunMode
import ai.rever.boss.plugin.dynamic.testexplorer.engine.TestRunner
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scoped run's two decisions that happen before any process starts: which test files exist to
 * be searched, and what to say when there is nothing to scope by. Both run against a real directory
 * tree; neither needs pytest or git.
 */
class TestRunnerAffectedTest {
    @TempDir
    lateinit var root: File

    private fun write(
        relative: String,
        text: String = "",
    ) = File(root, relative).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun runner(changed: List<String>?) = TestRunner(root, isWindows = false, changedFiles = { changed })

    @Test
    fun `the test files searched are the project's own, not build output or caches`() {
        write("pyproject.toml")
        write("tests/test_cart.py", "from pricing.cart import x")
        write("build/lib/tests/test_cart.py", "stale copy")
        write(".venv/lib/site-packages/pkg/test_vendor.py")
        write("pricing/cart.py")

        val files = runner(emptyList()).testFileText(TestFramework.PYTEST)

        assertEquals(setOf("tests/test_cart.py"), files.keys)
    }

    @Test
    fun `a changed module selects the tests that import it`() {
        write("tests/test_cart.py", "from pricing.cart import apply_discount")
        write("tests/test_tax.py", "import pricing.tax")

        val selection = runner(listOf("pricing/cart.py")).affectedSelection(TestFramework.PYTEST)

        assertEquals(listOf("tests/test_cart.py"), selection?.testFiles)
    }

    @Test
    fun `outside git a scoped run says there is nothing to scope by, and runs nothing`() =
        runBlocking {
            write("pyproject.toml")

            val report = runner(changed = null).run(RunMode.AFFECTED, emptyList()) {}

            assertNull(report.exitCode, "nothing may run")
            assertTrue(report.note.orEmpty().contains("not a git repository"), report.note)
        }

    @Test
    fun `a change no test refers to runs nothing and names the untested file`() =
        runBlocking {
            write("pyproject.toml")
            write("tests/test_cart.py", "from pricing.cart import x")

            val report = runner(listOf("pricing/shipping.py")).run(RunMode.AFFECTED, emptyList()) {}

            assertEquals("(nothing to run)", report.command)
            assertTrue(report.note.orEmpty().contains("No test refers to: pricing/shipping.py"), report.note)
        }
}

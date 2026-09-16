package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCaseResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestStatus
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestSuiteResult
import ai.rever.boss.plugin.dynamic.testexplorer.engine.RunMode
import ai.rever.boss.plugin.dynamic.testexplorer.engine.TestExecution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent-facing contract: what the four tools declare, and what they say back.
 *
 * The read-only flags are asserted deliberately. They are what the host governs an executing tool
 * by, so a tool that quietly flipped to read-only would be a governance regression, not a cosmetic
 * one.
 */
class TestExplorerMcpToolsTest {

    private val passing =
        TestRunReport(
            listOf(
                TestSuiteResult(
                    "S",
                    listOf(
                        TestCaseResult("S", "ok", TestStatus.PASSED, 0.1),
                        TestCaseResult("S", "bad", TestStatus.FAILED, 0.2, message = "expected 1"),
                    ),
                ),
            ),
            command = "./gradlew test",
            exitCode = 1,
        )

    private class FakeExecution(
        private val result: TestRunReport,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : TestExecution {
        override suspend fun run(
            mode: RunMode,
            priorFailures: List<TestCaseResult>,
            onOutput: (String) -> Unit,
        ): TestRunReport {
            gate?.await()
            return result
        }
    }

    private fun session(execution: TestExecution, projectPath: String? = "/project") =
        TestExplorerSession(projectPathSupplier = { projectPath }, runnerFactory = { execution })

    private fun provider(session: TestExplorerSession, boundMillis: Long = 45_000L) =
        TestExplorerMcpToolProvider("ai.rever.boss.plugin.dynamic.testexplorer", session, boundMillis)

    private suspend fun call(session: TestExplorerSession, tool: String, bound: Long = 45_000L): McpToolResult =
        provider(session, bound).tools().single { it.name == tool }.handler.call(McpToolArgs(emptyMap()))

    @Test
    fun `the executing tools are declared as such and the reading tools are not`() {
        val tools = provider(session(FakeExecution(passing))).tools().associateBy { it.name }

        assertEquals(setOf("test_run", "test_rerun_failed", "test_results", "test_list"), tools.keys)
        assertFalse(tools.getValue("test_run").readOnly, "test_run executes the project's code")
        assertFalse(tools.getValue("test_rerun_failed").readOnly, "a rerun executes the project's code")
        assertTrue(tools.getValue("test_results").readOnly)
        assertTrue(tools.getValue("test_list").readOnly)
    }

    @Test
    fun `reading results before any run points at test_run instead of inventing one`() = runBlocking {
        val result = call(session(FakeExecution(passing)), "test_results")

        assertFalse(result.isError)
        assertTrue(result.text.contains("No test run yet"))
    }

    @Test
    fun `test_run reports counts, the exit code and the failing test`() = runBlocking {
        val result = call(session(FakeExecution(passing)), "test_run")

        assertFalse(result.isError)
        assertTrue(result.text.contains("Exit code: 1"))
        assertTrue(result.text.contains("2 tests: 1 passed, 1 failing, 0 skipped"))
        assertTrue(result.text.contains("S.bad [FAILED]"))
        assertTrue(result.text.contains("expected 1"))
    }

    @Test
    fun `test_list gives every case with its status`() = runBlocking {
        val session = session(FakeExecution(passing))
        call(session, "test_run")

        val result = call(session, "test_list")

        assertTrue(result.text.contains("[PASSED]"))
        assertTrue(result.text.contains("S.ok"))
        assertTrue(result.text.contains("[FAILED]"))
        assertTrue(result.text.contains("S.bad"))
    }

    @Test
    fun `with no project open test_run is an error rather than an empty pass`() = runBlocking {
        val result = call(session(FakeExecution(passing), projectPath = ""), "test_run")

        assertTrue(result.isError, "an agent must not read this as a clean run")
        assertTrue(result.text.contains("No project is open"))
    }

    @Test
    fun `a run that outlasts the bound tells the agent to poll instead of holding the call open`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val session = session(FakeExecution(passing, gate))

            val result = call(session, "test_run", bound = 30)

            assertFalse(result.isError)
            assertTrue(result.text.contains("still going"))
            assertTrue(result.text.contains("test_results"))
            gate.complete(Unit)
        }
}

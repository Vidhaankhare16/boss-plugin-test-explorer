package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCaseResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestStatus
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestSuiteResult
import ai.rever.boss.plugin.dynamic.testexplorer.engine.RunMode
import ai.rever.boss.plugin.dynamic.testexplorer.engine.TestExecution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The session's orchestration, with no build anywhere near it.
 *
 * What matters here is not what a runner reports but what the session does around it: that only one
 * run happens at a time, that a caller which gives up waiting does not cancel the run, and that a
 * missing project is reported rather than guessed at.
 */
class TestExplorerSessionTest {

    private fun report(vararg cases: TestCaseResult) =
        TestRunReport(listOf(TestSuiteResult("S", cases.toList())), command = "fake", exitCode = 0)

    private val passing = report(TestCaseResult("S", "ok", TestStatus.PASSED, 0.1))
    private val failing = report(TestCaseResult("S", "bad", TestStatus.FAILED, 0.1, message = "no"))

    private class FakeExecution(
        private val result: TestRunReport,
        private val gate: CompletableDeferred<Unit>? = null,
        private val lines: List<String> = emptyList(),
    ) : TestExecution {
        val modes = mutableListOf<RunMode>()
        var lastPriorFailures: List<TestCaseResult> = emptyList()

        /** Completes once a run has actually begun: the session starts it on another thread. */
        val started = CompletableDeferred<Unit>()

        override suspend fun run(
            mode: RunMode,
            priorFailures: List<TestCaseResult>,
            onOutput: (String) -> Unit,
        ): TestRunReport {
            modes += mode
            lastPriorFailures = priorFailures
            started.complete(Unit)
            lines.forEach(onOutput)
            gate?.await()
            return result
        }
    }

    private fun session(execution: TestExecution, projectPath: String? = "/project") =
        TestExplorerSession(projectPathSupplier = { projectPath }, runnerFactory = { execution })

    @Test
    fun `with no project open nothing runs and the session says so`() = runBlocking {
        val execution = FakeExecution(passing)
        val session = session(execution, projectPath = "")

        assertNull(session.start(RunMode.ALL))
        assertTrue(session.projectMissing.value)
        assertTrue(execution.modes.isEmpty(), "nothing should have been run")
    }

    @Test
    fun `a finished run lands in the report flow and clears the running flag`() = runBlocking {
        val session = session(FakeExecution(passing))

        session.start(RunMode.ALL)!!.await()

        assertEquals(passing, session.report.value)
        assertFalse(session.isRunning.value)
        assertFalse(session.projectMissing.value)
    }

    @Test
    fun `asking for a second run while one is going joins the run already in flight`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val execution = FakeExecution(passing, gate)
        val session = session(execution)

        val first = session.start(RunMode.ALL)
        // The run begins on the session's own scope. Counting runs before it has started would read
        // zero on a slow machine, which is a race in the test, not a second run being refused.
        withTimeout(5_000) { execution.started.await() }
        val second = session.start(RunMode.ALL)

        assertSame(first, second, "a second start must not spawn a competing run")
        assertEquals(1, execution.modes.size)

        gate.complete(Unit)
        first!!.await()
        assertFalse(session.isRunning.value)
    }

    @Test
    fun `a caller that gives up waiting does not cancel the run`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val session = session(FakeExecution(passing, gate))

        // The bound is far shorter than the run, which is the case the MCP tools hit on a real suite.
        assertNull(session.runBounded(RunMode.ALL, timeoutMillis = 50))
        assertTrue(session.isRunning.value, "the run should still be going")

        gate.complete(Unit)
        val landed = withTimeoutOrNull(5_000) {
            while (session.report.value == null) delay(10)
            session.report.value
        }
        assertEquals(passing, landed, "the abandoned wait still produces a report")
    }

    @Test
    fun `a rerun is told which cases failed last time`() = runBlocking {
        val execution = FakeExecution(failing)
        val session = session(execution)

        session.start(RunMode.ALL)!!.await()
        session.start(RunMode.FAILED_ONLY)!!.await()

        assertEquals(listOf(RunMode.ALL, RunMode.FAILED_ONLY), execution.modes)
        assertEquals(listOf("S.bad"), execution.lastPriorFailures.map { it.qualifiedName })
    }

    @Test
    fun `runner output reaches the output flow`() = runBlocking {
        val session = session(FakeExecution(passing, lines = listOf("compiling", "running")))

        session.start(RunMode.ALL)!!.await()

        assertEquals(listOf("compiling", "running"), session.output.value)
    }

    @Test
    fun `a finished run publishes where each failure broke, for the panel and the tools alike`() = runBlocking {
        val project = kotlin.io.path.createTempDirectory("te-session").toFile()
        try {
            val source = java.io.File(project, "src/test/kotlin/com/example/FooTest.kt").apply {
                parentFile.mkdirs()
                writeText("// test")
            }
            val trace = "java.lang.AssertionError: no\n    at com.example.FooTest.bad(FooTest.kt:9)"
            val broken =
                TestCaseResult("com.example.FooTest", "bad", TestStatus.FAILED, 0.1, message = "no", details = trace)
            val run = TestRunReport(listOf(TestSuiteResult("S", listOf(broken))), command = "fake", exitCode = 1)
            val session = session(FakeExecution(run), projectPath = project.path)

            session.start(RunMode.ALL)!!.await()

            val location = session.locations.value[broken.qualifiedName]
            assertEquals(source.absolutePath, location?.path)
            assertEquals(9, location?.line)
            assertEquals(project, session.projectRoot.value)
            session.dispose()
        } finally {
            project.deleteRecursively()
        }
    }
}

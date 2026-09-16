package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.engine.RunMode
import ai.rever.boss.plugin.dynamic.testexplorer.engine.TestExecution
import ai.rever.boss.plugin.dynamic.testexplorer.engine.TestRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The one place a run lives, shared by the panel and the MCP tools so a run an agent starts and a
 * run a person starts are the same run, reported the same way.
 *
 * The project to test is read fresh from [projectPathSupplier] each time a run starts, because the
 * active project can change under a long-lived plugin. At most one run happens at a time; asking
 * for another while one is in flight returns the run already going rather than racing a second
 * process against the same report files.
 */
class TestExplorerSession(
    private val projectPathSupplier: () -> String?,
    private val runnerFactory: (File) -> TestExecution = { TestRunner(it) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startLock = Mutex()

    private val _report = MutableStateFlow<TestRunReport?>(null)
    val report: StateFlow<TestRunReport?> = _report.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()

    private val _projectMissing = MutableStateFlow(false)
    /** True when the last start found no open project, so the UI can say so plainly. */
    val projectMissing: StateFlow<Boolean> = _projectMissing.asStateFlow()

    private var current: Deferred<TestRunReport>? = null

    /**
     * Start a run, or return the one already in flight. Never blocks the caller: the work runs on
     * the session's own scope, and the returned [Deferred] completes with the parsed report.
     * Returns null only when there is no open project to test.
     */
    suspend fun start(mode: RunMode): Deferred<TestRunReport>? =
        startLock.withLock {
            current?.let { return it }

            val projectPath = projectPathSupplier()?.takeIf { it.isNotBlank() }
            if (projectPath == null) {
                _projectMissing.value = true
                return null
            }
            _projectMissing.value = false

            val priorFailures = _report.value?.failures.orEmpty()
            val runner = runnerFactory(File(projectPath))
            _output.value = emptyList()
            _isRunning.value = true

            val job =
                scope.async {
                    try {
                        val result = runner.run(mode, priorFailures) { line -> appendOutput(line) }
                        // Set the report from inside the run so a caller that does not await the
                        // Deferred (the panel) still sees the result land in the flow. A cancelled
                        // run (Stop) throws before here and leaves the previous report in place.
                        _report.value = result
                        result
                    } finally {
                        _isRunning.value = false
                        current = null
                    }
                }
            current = job
            job
        }

    /**
     * Start a run and wait up to [timeoutMillis] for it. Returns the report when it finished in
     * time, or null when it is still running (the run keeps going and its result lands in
     * [report]) or when there is no project. The bound exists because an MCP handler is capped by
     * the host at 60s while a real suite can take longer; a caller that gets null polls
     * [report]/[isRunning] instead of holding the tool call open.
     */
    suspend fun runBounded(mode: RunMode, timeoutMillis: Long): TestRunReport? {
        val job = start(mode) ?: return null
        return withTimeoutOrNull(timeoutMillis) { job.await() }
    }

    fun stop() {
        current?.cancel()
    }

    fun dispose() {
        scope.cancel()
    }

    private fun appendOutput(line: String) {
        _output.value = (_output.value + line).takeLast(MAX_OUTPUT_LINES)
    }

    private companion object {
        const val MAX_OUTPUT_LINES = 500
    }
}

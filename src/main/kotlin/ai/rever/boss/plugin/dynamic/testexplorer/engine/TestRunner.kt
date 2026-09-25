package ai.rever.boss.plugin.dynamic.testexplorer.engine

import ai.rever.boss.plugin.dynamic.testexplorer.core.AffectedSelection
import ai.rever.boss.plugin.dynamic.testexplorer.core.AffectedTests
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestCaseResult
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestFramework
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestReportCollector
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunReport
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunnerDetection
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * What to run: the whole suite, only the cases that failed last time, or only the tests the
 * uncommitted changes touch (see [AffectedTests]).
 */
enum class RunMode { ALL, FAILED_ONLY, AFFECTED }

/**
 * Running a project's tests and reporting what happened.
 *
 * An interface rather than the concrete [TestRunner] so the session that owns a run can be tested
 * without spawning a build: the behaviour worth pinning there is orchestration (one run at a time,
 * what a bounded wait returns, where the report lands), and none of it should need a real process.
 */
interface TestExecution {
    suspend fun run(
        mode: RunMode,
        priorFailures: List<TestCaseResult> = emptyList(),
        onOutput: (String) -> Unit = {},
    ): TestRunReport
}

/**
 * Runs a project's test suite as a child process and reads the JUnit XML it leaves behind.
 *
 * This is the one impure piece: it spawns the runner, streams its output, and walks the result
 * directories. The framework knowledge (what to run, where reports land, how to rerun) lives in
 * [TestRunnerDetection], and the interpretation of the reports lives in [TestReportCollector], so
 * both are unit tested without a process; this class is the wiring between them.
 *
 * The process is spawned directly, not through a shell, so no argument is ever word-split or
 * glob-expanded, and it inherits the project directory as its working directory.
 */
class TestRunner(
    private val projectDir: File,
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    private val clock: () -> Long = System::currentTimeMillis,
    private val changedFiles: () -> List<String>? = { GitChanges(projectDir).changedFiles() },
) : TestExecution {

    /** The framework detected from the project root, or null when none is recognised. */
    fun detectFramework(): TestFramework? {
        val names = projectDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        return TestRunnerDetection.detect(names)
    }

    /**
     * Run the tests and return the parsed report.
     *
     * [priorFailures] is only consulted for [RunMode.FAILED_ONLY]. [onOutput] receives each line
     * the runner prints, for a live log in the panel. Cancelling the calling coroutine destroys
     * the process tree and abandons the run: the cancellation propagates out of this call, so a
     * stopped run leaves the previous report in place rather than reporting a half-finished one.
     */
    override suspend fun run(
        mode: RunMode,
        priorFailures: List<TestCaseResult>,
        onOutput: (String) -> Unit,
    ): TestRunReport {
        val framework =
            detectFramework()
                ?: return TestRunReport.empty(
                    command = "(no runner)",
                    exitCode = null,
                    note = "No supported test runner was detected in this project " +
                        "(looked for Gradle, Maven, and pytest).",
                )

        // The report path handed to the runner is relative to the project (its working directory),
        // so it never carries a space from an absolute path that Windows' cmd would resplit; the
        // absolute file is used only to delete a stale report and to read the fresh one.
        val reportRelative = "$WORK_DIR/pytest-report.xml"
        val reportFile = File(projectDir, reportRelative)
        var scopeNote: String? = null
        val command =
            when (mode) {
                RunMode.ALL -> TestRunnerDetection.fullRunCommand(framework, isWindows, reportRelative)
                RunMode.FAILED_ONLY ->
                    TestRunnerDetection.rerunCommand(framework, isWindows, priorFailures, reportRelative)
                RunMode.AFFECTED -> {
                    val selection =
                        affectedSelection(framework)
                            ?: return TestRunReport.empty(
                                command = "(no change set)",
                                exitCode = null,
                                note = "This project is not a git repository, so there are no changes to scope a run " +
                                    "by. Run the whole suite instead.",
                            )
                    scopeNote = describe(selection)
                    if (selection.isEmpty) {
                        return TestRunReport.empty(command = "(nothing to run)", exitCode = null, note = scopeNote)
                    }
                    if (selection.runEverything) {
                        TestRunnerDetection.fullRunCommand(framework, isWindows, reportRelative)
                    } else {
                        TestRunnerDetection.affectedCommand(framework, isWindows, selection.testFiles, reportRelative)
                    }
                }
            }
        val commandLine = command.joinToString(" ")

        if (framework == TestFramework.PYTEST) {
            reportFile.parentFile?.mkdirs()
            reportFile.delete()
        }
        val startedAt = clock()

        val exitCode =
            runCatching { execute(command, onOutput) }
                .getOrElse { throwable ->
                    if (throwable is InterruptedException) throw throwable
                    return TestRunReport.empty(
                        command = commandLine,
                        exitCode = null,
                        note = "Could not start the test runner: ${throwable.message ?: throwable::class.simpleName}",
                    )
                }

        val reports = readReports(framework, reportFile, startedAt)
        val report = TestReportCollector.collect(commandLine, exitCode, reports)
        // Say why this subset ran, ahead of anything the collector had to say about the reports.
        val note = listOfNotNull(scopeNote, report.note).joinToString(" ").ifBlank { null }
        return report.copy(note = note)
    }

    /** Which tests the current changes touch, or null when the project is not a git repository. */
    internal fun affectedSelection(framework: TestFramework): AffectedSelection? {
        val changed = changedFiles() ?: return null
        return AffectedTests.select(framework, changed, testFileText(framework))
    }

    /**
     * Every test file in the project with its text, project-relative, for [AffectedTests] to search
     * for references. Bounded: dependency and build trees are pruned and an oversized file is
     * skipped, because a generated fixture must not make a quick scoped run slow.
     */
    internal fun testFileText(framework: TestFramework): Map<String, String> =
        projectDir
            .walkTopDown()
            .onEnter { it == projectDir || it.name !in PRUNED_DIRS + SCOPE_PRUNED_DIRS }
            .filter { it.isFile && it.length() <= MAX_TEST_FILE_BYTES }
            .map { it.relativeTo(projectDir).invariantSeparatorsPath to it }
            .filter { (path, _) -> AffectedTests.isTestFile(framework, path) }
            .mapNotNull { (path, file) -> runCatching { path to file.readText() }.getOrNull() }
            .toMap()

    private fun describe(selection: AffectedSelection): String {
        if (selection.untested.isEmpty()) return selection.reason
        val shown = selection.untested.take(MAX_UNTESTED_SHOWN).joinToString(", ")
        val more = selection.untested.size - MAX_UNTESTED_SHOWN
        return selection.reason + " No test refers to: " + shown + (if (more > 0) " and $more more." else ".")
    }

    private suspend fun execute(command: List<String>, onOutput: (String) -> Unit): Int =
        suspendCancellableCoroutine { continuation ->
            val process =
                ProcessBuilder(command)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .start()

            continuation.invokeOnCancellation { process.destroyForcibly() }

            // One reader thread drains the merged stdout/stderr so the pipe never fills and stalls
            // the child, forwarding each line to the panel's live log.
            val pump =
                CoroutineScope(Dispatchers.IO).launch {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach(onOutput)
                    }
                }

            CoroutineScope(Dispatchers.IO).launch {
                val code = process.waitFor()
                pump.join()
                continuation.resumeIfActive(code)
            }
        }

    /**
     * Reads the XML this run produced, ignoring reports left by an earlier run.
     *
     * Internal rather than private so the directory walk can be tested against a real on-disk
     * layout: finding a multi-module build's reports, and excluding a previous run's, are the two
     * things most likely to be silently wrong and neither is visible from a unit test of the parser.
     */
    internal fun readReports(framework: TestFramework, pytestReport: File, startedAt: Long): List<String> {
        val files =
            when (framework) {
                TestFramework.PYTEST -> listOfNotNull(pytestReport.takeIf { it.isFile })
                // Gradle and Maven write one report directory per (sub)project, so a multi-module
                // build scatters them under moduleA/build/test-results, moduleB/build/... . Walk the
                // whole project once (pruning VCS, dependency and cache trees) and keep any XML whose
                // path sits under a result-directory marker, so every module is covered.
                else -> {
                    val markers = TestRunnerDetection.resultDirs(framework).map { "/$it/" }
                    projectDir
                        .walkTopDown()
                        .onEnter { it.name !in PRUNED_DIRS }
                        .filter { it.isFile && it.extension == "xml" }
                        .filter { file -> markers.any { file.invariantPath().contains(it) } }
                        .toList()
                }
            }
        // Only this run's reports: a runner rewrites its report files, so anything not touched
        // since we started belongs to a previous run and would double count or mislead. A small
        // grace window absorbs clock granularity between this process and the child.
        return files
            .filter { it.lastModified() >= startedAt - REPORT_GRACE_MILLIS }
            .mapNotNull { runCatching { it.readText() }.getOrNull() }
    }

    /** The file's path with `/` separators, so a marker match works the same on Windows. */
    private fun File.invariantPath(): String = path.replace('\\', '/')

    private fun CancellableContinuation<Int>.resumeIfActive(value: Int) {
        if (isActive) resume(value)
    }

    private companion object {
        const val REPORT_GRACE_MILLIS = 2_000L
        const val WORK_DIR = ".boss-test-explorer"
        val PRUNED_DIRS = setOf(".git", ".gradle", "node_modules", ".venv", "venv")
        val SCOPE_PRUNED_DIRS = setOf("build", "target", "out", "bin", "__pycache__", ".pytest_cache", WORK_DIR)
        const val MAX_TEST_FILE_BYTES = 1_000_000L
        const val MAX_UNTESTED_SHOWN = 5
    }
}

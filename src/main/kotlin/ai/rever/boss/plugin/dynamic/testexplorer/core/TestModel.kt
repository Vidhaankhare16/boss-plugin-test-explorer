package ai.rever.boss.plugin.dynamic.testexplorer.core

/**
 * The outcome of a single test case.
 *
 * Kept deliberately small and framework neutral: every JUnit-XML producer (Gradle, Maven's
 * Surefire, pytest's `--junitxml`) maps onto exactly these four, so the rest of the plugin never
 * has to know which runner produced a report.
 */
enum class TestStatus {
    /** Ran and passed. */
    PASSED,

    /** Ran and an assertion failed (`<failure>` in the report). */
    FAILED,

    /** Ran and threw before it could assert (`<error>` in the report). */
    ERROR,

    /** Did not run (`<skipped>`), e.g. disabled or filtered out. */
    SKIPPED,
}

/**
 * One test case: the leaf of the tree the panel renders and the thing an agent reruns.
 *
 * [className] plus [name] is the identity used everywhere, including the `--tests` filter a rerun
 * builds, so both are carried verbatim from the report rather than pretty-printed.
 */
data class TestCaseResult(
    val className: String,
    val name: String,
    val status: TestStatus,
    /** Seconds the case took, as the runner reported it; null when the report omitted it. */
    val durationSeconds: Double?,
    /** The failure/error message, or null for a passed or skipped case. */
    val message: String? = null,
    /** The stack trace or detail body, when the report carried one. */
    val details: String? = null,
) {
    /** `com.example.FooTest.does a thing` - the fully qualified identity, stable across runs. */
    val qualifiedName: String
        get() = if (className.isBlank()) name else "$className.$name"

    val failed: Boolean
        get() = status == TestStatus.FAILED || status == TestStatus.ERROR
}

/**
 * A `<testsuite>`: one class (or file) and the cases under it.
 */
data class TestSuiteResult(
    val name: String,
    val cases: List<TestCaseResult>,
) {
    val counts: TestCounts
        get() = TestCounts.of(cases)
}

/**
 * A tally of cases by outcome. Small, immutable, and summable so counts can be rolled up from
 * cases to a suite to a whole run without a mutable accumulator.
 */
data class TestCounts(
    val passed: Int = 0,
    val failed: Int = 0,
    val errored: Int = 0,
    val skipped: Int = 0,
) {
    val total: Int
        get() = passed + failed + errored + skipped

    /** Failures and errors together, the number that decides red-versus-green. */
    val failing: Int
        get() = failed + errored

    val allPassed: Boolean
        get() = failing == 0 && total > 0

    operator fun plus(other: TestCounts): TestCounts =
        TestCounts(
            passed + other.passed,
            failed + other.failed,
            errored + other.errored,
            skipped + other.skipped,
        )

    companion object {
        fun of(cases: List<TestCaseResult>): TestCounts =
            cases.fold(TestCounts()) { acc, case ->
                when (case.status) {
                    TestStatus.PASSED -> acc.copy(passed = acc.passed + 1)
                    TestStatus.FAILED -> acc.copy(failed = acc.failed + 1)
                    TestStatus.ERROR -> acc.copy(errored = acc.errored + 1)
                    TestStatus.SKIPPED -> acc.copy(skipped = acc.skipped + 1)
                }
            }
    }
}

/**
 * The result of one whole run: the suites parsed from every report, plus the exit status of the
 * runner process. Both matter, and neither implies the other: a build can fail with no failing
 * test (a compile error) and, in principle, pass with reports the plugin could not read, so the
 * panel and the MCP tools always show the two side by side rather than collapsing them.
 */
data class TestRunReport(
    val suites: List<TestSuiteResult>,
    /** The command line that produced it, for the panel header and for reproduction. */
    val command: String,
    /** The runner process exit code, or null when the run never started or is still running. */
    val exitCode: Int?,
    /** A short, human line when the run could not be interpreted (no reports, runner missing). */
    val note: String? = null,
) {
    val counts: TestCounts
        get() = suites.fold(TestCounts()) { acc, suite -> acc + suite.counts }

    /** Every failing case across every suite, in report order, for rerun and for agents. */
    val failures: List<TestCaseResult>
        get() = suites.flatMap { it.cases }.filter { it.failed }

    companion object {
        /** A report for a run that produced no readable results, carrying only the reason. */
        fun empty(command: String, exitCode: Int?, note: String): TestRunReport =
            TestRunReport(emptyList(), command, exitCode, note)
    }
}

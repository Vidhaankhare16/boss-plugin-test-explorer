package ai.rever.boss.plugin.dynamic.testexplorer.core

/**
 * Turns the raw XML of a run's report files into one [TestRunReport], parsing and aggregating
 * them and deciding the human note. Pure, so the interpretation of a run - including the
 * "build failed but no test did" case that trips people up - is testable without running a build.
 */
object TestReportCollector {

    fun collect(command: String, exitCode: Int?, reportXmls: List<String>): TestRunReport {
        val suites = reportXmls.flatMap { JUnitXmlParser.parse(it) }.filter { it.cases.isNotEmpty() }
        val note = noteFor(exitCode, suites)
        return TestRunReport(suites = suites, command = command, exitCode = exitCode, note = note)
    }

    /**
     * The one-line explanation shown above the tree, or null when the numbers speak for
     * themselves. It exists for the case the counts cannot express: a non-zero exit with no
     * failing test is almost always a compile or configuration error, and saying so is more
     * useful than a green "0 failed" over an empty tree.
     */
    private fun noteFor(exitCode: Int?, suites: List<TestSuiteResult>): String? {
        val counts = suites.fold(TestCounts()) { acc, suite -> acc + suite.counts }
        return when {
            suites.isEmpty() && exitCode == null ->
                "The test run did not start. Check that the runner is installed and on PATH."

            suites.isEmpty() && exitCode == 0 ->
                "The run finished but produced no test reports. It may have run no tests."

            suites.isEmpty() ->
                "The runner exited with code $exitCode and produced no test reports, " +
                    "which usually means the build failed before tests ran (for example a compile error)."

            exitCode != null && exitCode != 0 && counts.failing == 0 ->
                "The runner exited with code $exitCode even though no test failed, " +
                    "which usually points at a build or configuration error rather than a test."

            else -> null
        }
    }
}

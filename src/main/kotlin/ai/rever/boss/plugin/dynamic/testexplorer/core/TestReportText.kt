package ai.rever.boss.plugin.dynamic.testexplorer.core

/**
 * Renders a [TestRunReport] as compact text for an agent reading tool output.
 *
 * The shape is deliberate: the counts and exit code first so a one-glance answer to "did the
 * tests pass" is on the first lines, then the failures with their messages, because that is what
 * an agent acts on. It is bounded - a thousand-failure run must not flood the model's context -
 * and it says when it truncated so the reader knows more exist.
 */
object TestReportText {

    fun summary(report: TestRunReport, maxFailures: Int = DEFAULT_MAX_FAILURES): String {
        val counts = report.counts
        val lines = mutableListOf<String>()
        lines += "Command: ${report.command}"
        lines += "Exit code: ${report.exitCode ?: "n/a"}"
        lines += countsLine(counts)
        report.note?.let { lines += "Note: $it" }

        val failures = report.failures
        if (failures.isNotEmpty()) {
            lines += ""
            lines += "Failures:"
            failures.take(maxFailures).forEach { case ->
                lines += "  - ${case.qualifiedName} [${case.status}]"
                case.message?.let { lines += "      ${it.firstLine()}" }
            }
            if (failures.size > maxFailures) {
                lines += "  ... and ${failures.size - maxFailures} more (use test_list to see all)."
            }
        }
        return lines.joinToString("\n")
    }

    fun list(report: TestRunReport): String {
        val cases = report.suites.flatMap { it.cases }
        if (cases.isEmpty()) return countsLine(report.counts)
        return buildString {
            append(countsLine(report.counts))
            append("\n")
            append(
                cases.joinToString("\n") { case ->
                    val duration = case.durationSeconds?.let { " (${it}s)" }.orEmpty()
                    "${case.status.padded()} ${case.qualifiedName}$duration"
                },
            )
        }
    }

    private fun countsLine(counts: TestCounts): String =
        "${counts.total} tests: ${counts.passed} passed, ${counts.failing} failing, ${counts.skipped} skipped"

    private fun TestStatus.padded(): String = "[${name}]".padEnd(9)

    private fun String.firstLine(): String = trim().lineSequence().firstOrNull().orEmpty()

    private const val DEFAULT_MAX_FAILURES = 50
}

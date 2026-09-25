package ai.rever.boss.plugin.dynamic.testexplorer.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses the JUnit XML report format into [TestSuiteResult]s.
 *
 * That format is the one common surface across the runners this plugin drives: Gradle's `test`
 * task, Maven's Surefire, and pytest's `--junitxml` all emit it, which is what lets one parser
 * back every runner. It is intentionally lenient - a report from a runner this was never tested
 * against still yields whatever suites and cases it can read rather than throwing - because a
 * half-read report is more useful to someone staring at a red build than an exception is.
 *
 * The document is parsed with external entities and DTDs disabled: a test report is data produced
 * by a build, but the build runs whatever is in the repository, so a crafted report must not be
 * able to make the parser open a file or a URL of its choosing (XXE).
 */
object JUnitXmlParser {

    /**
     * Parse one report file's [xml]. Returns the suites it contained, or an empty list when the
     * text is not a JUnit report at all (so a stray file in the results directory is ignored
     * rather than failing the whole run's parse).
     */
    fun parse(xml: String): List<TestSuiteResult> {
        if (xml.isBlank()) return emptyList()
        val document =
            runCatching {
                val factory =
                    DocumentBuilderFactory.newInstance().apply {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                        isExpandEntityReferences = false
                        isNamespaceAware = false
                    }
                factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            }.getOrNull() ?: return emptyList()

        // A file may hold one <testsuite>, or a <testsuites> wrapper around several. Collect every
        // <testsuite> element wherever it sits so both shapes read the same way.
        val suiteElements = document.getElementsByTagName("testsuite")
        return (0 until suiteElements.length)
            .mapNotNull { suiteElements.item(it) as? Element }
            .flatMap { parseSuite(it) }
    }

    /**
     * One `<testsuite>`, as one group - or, when its cases name other classes, one group per class.
     *
     * pytest writes a single `<testsuite name="pytest">` for the whole run and carries each test's
     * module in its `classname`, so the report-level name says nothing about where a test lives and
     * a panel grouped by it shows every test under "pytest". Gradle and Surefire already name the
     * suite after the class its cases belong to, so for them this changes nothing.
     */
    private fun parseSuite(suite: Element): List<TestSuiteResult> {
        val suiteName = suite.getAttribute("name").ifBlank { "(unnamed suite)" }
        val caseElements = suite.getElementsByTagName("testcase")
        val cases =
            (0 until caseElements.length)
                .mapNotNull { caseElements.item(it) as? Element }
                .map { parseCase(it, suiteName) }
        if (cases.all { it.className == suiteName }) return listOf(TestSuiteResult(suiteName, cases))
        // groupBy keeps first-seen order, so groups appear in the order the runner ran them.
        return cases.groupBy { it.className }.map { (className, group) -> TestSuiteResult(className, group) }
    }

    private fun parseCase(case: Element, suiteName: String): TestCaseResult {
        // classname is per-case in Gradle/Surefire and often blank in pytest, where the suite name
        // carries the file; fall back to the suite so a case is never left without an owner.
        val className = case.getAttribute("classname").ifBlank { suiteName }
        val name = case.getAttribute("name").ifBlank { "(unnamed test)" }
        val duration = case.getAttribute("time").toDoubleOrNull()

        val failure = case.firstChild("failure")
        val error = case.firstChild("error")
        val skipped = case.firstChild("skipped")

        val (status, marker) =
            when {
                failure != null -> TestStatus.FAILED to failure
                error != null -> TestStatus.ERROR to error
                skipped != null -> TestStatus.SKIPPED to skipped
                else -> TestStatus.PASSED to null
            }

        return TestCaseResult(
            className = className,
            name = name,
            status = status,
            durationSeconds = duration,
            message = marker?.getAttribute("message")?.ifBlank { null },
            details = marker?.textContent?.trim()?.ifBlank { null },
        )
    }

    /** First direct-or-nested child element named [tag], or null. */
    private fun Element.firstChild(tag: String): Element? {
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) return node as Element
        }
        return null
    }
}

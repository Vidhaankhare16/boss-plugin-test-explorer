package ai.rever.boss.plugin.dynamic.testexplorer.core

import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Parses the JUnit XML report format into [TestSuiteResult]s.
 *
 * That format is the one common surface across the runners this plugin drives: Gradle's `test`
 * task, Maven's Surefire, and pytest's `--junitxml` all emit it, which is what lets one parser
 * back every runner. It is intentionally lenient - a report from a runner this was never tested
 * against still yields whatever suites and cases it can read rather than throwing - because a
 * half-read report is more useful to someone staring at a red build than an exception is.
 *
 * Read with StAX (`javax.xml.stream`) rather than DOM. BOSS 9.5.25 confines a plugin's parent
 * class loading to shared packages: `javax.` is shared, but `org.w3c.dom` is not, so a DOM
 * parser's `Element` failed to load inside BOSS with `NoClassDefFoundError` while every unit test
 * passed. Everything this parser touches lives under `java.` and `javax.`.
 *
 * DTDs are refused and external entities disabled: a test report is data produced by a build, but
 * the build runs whatever is in the repository, so a crafted report must not be able to make the
 * parser open a file or a URL of its choosing (XXE).
 */
object JUnitXmlParser {

    private val MARKERS = setOf("failure", "error", "skipped")

    /**
     * Parse one report file's [xml]. Returns the suites it contained, or an empty list when the
     * text is not a JUnit report at all (so a stray file in the results directory is ignored
     * rather than failing the whole run's parse).
     */
    fun parse(xml: String): List<TestSuiteResult> {
        if (xml.isBlank()) return emptyList()
        val suites = runCatching { readSuites(xml) }.getOrNull() ?: return emptyList()
        return suites.flatMap { groupByClass(it.name, it.cases) }
    }

    private class OpenSuite(val name: String) {
        val cases = mutableListOf<TestCaseResult>()
    }

    private class OpenCase(val className: String?, val name: String, val duration: Double?) {
        /** The first marker of each kind, in the order met. */
        val markers = linkedMapOf<String, Marker>()
    }

    /** A marker element's message, and its text while it is open (DOM's textContent). */
    private class Marker(val message: String?, val depth: Int) {
        val text = StringBuilder()
        var closed = false
    }

    private fun inputFactory(): XMLInputFactory =
        XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }

    /**
     * Every `<testsuite>`, wherever it sits (one per file, or several under `<testsuites>`), in
     * document order. A case belongs to every suite it is nested in, as it did under DOM's
     * descendant lookup; a case outside any suite is ignored.
     */
    private fun readSuites(xml: String): List<OpenSuite> {
        val reader = inputFactory().createXMLStreamReader(StringReader(xml))
        val all = mutableListOf<OpenSuite>()
        val openSuites = ArrayDeque<OpenSuite>()
        var case: OpenCase? = null
        var depth = 0
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.DTD -> error("A DOCTYPE is not allowed in a test report")

                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        when (val tag = reader.localName) {
                            "testsuite" -> OpenSuite(reader.attr("name").ifBlank { "(unnamed suite)" })
                                .also { all += it; openSuites.addLast(it) }

                            "testcase" -> if (openSuites.isNotEmpty() && case == null) {
                                case = OpenCase(
                                    className = reader.attr("classname").ifBlank { null },
                                    name = reader.attr("name").ifBlank { "(unnamed test)" },
                                    duration = reader.attr("time").toDoubleOrNull(),
                                )
                            }

                            in MARKERS -> case?.markers?.getOrPut(tag) {
                                Marker(reader.attr("message").ifBlank { null }, depth)
                            }
                        }
                    }

                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                        case?.markers?.values?.filter { !it.closed }?.forEach { it.text.append(reader.text) }

                    XMLStreamConstants.END_ELEMENT -> {
                        when (reader.localName) {
                            "testsuite" -> openSuites.removeLastOrNull()
                            "testcase" -> case?.let { open ->
                                openSuites.forEach { it.cases += open.toResult(it.name) }
                                case = null
                            }
                        }
                        case?.markers?.values?.forEach { if (it.depth == depth) it.closed = true }
                        depth--
                    }
                }
            }
        } finally {
            reader.close()
        }
        return all
    }

    private fun XMLStreamReader.attr(name: String): String = getAttributeValue(null, name) ?: ""

    private fun OpenCase.toResult(suiteName: String): TestCaseResult {
        // classname is per-case in Gradle/Surefire and often blank in pytest, where the suite name
        // carries the file; fall back to the suite so a case is never left without an owner.
        val failure = markers["failure"]
        val error = markers["error"]
        val skipped = markers["skipped"]
        val (status, marker) =
            when {
                failure != null -> TestStatus.FAILED to failure
                error != null -> TestStatus.ERROR to error
                skipped != null -> TestStatus.SKIPPED to skipped
                else -> TestStatus.PASSED to null
            }
        return TestCaseResult(
            className = className ?: suiteName,
            name = name,
            status = status,
            durationSeconds = duration,
            message = marker?.message,
            details = marker?.text?.toString()?.trim()?.ifBlank { null },
        )
    }

    /**
     * One `<testsuite>`, as one group - or, when its cases name other classes, one group per class.
     *
     * pytest writes a single `<testsuite name="pytest">` for the whole run and carries each test's
     * module in its `classname`, so the report-level name says nothing about where a test lives and
     * a panel grouped by it shows every test under "pytest". Gradle and Surefire already name the
     * suite after the class its cases belong to, so for them this changes nothing.
     */
    private fun groupByClass(suiteName: String, cases: List<TestCaseResult>): List<TestSuiteResult> {
        if (cases.all { it.className == suiteName }) return listOf(TestSuiteResult(suiteName, cases))
        // groupBy keeps first-seen order, so groups appear in the order the runner ran them.
        return cases.groupBy { it.className }.map { (className, group) -> TestSuiteResult(className, group) }
    }
}

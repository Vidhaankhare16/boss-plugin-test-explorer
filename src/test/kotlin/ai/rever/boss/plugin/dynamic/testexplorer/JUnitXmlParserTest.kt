package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.JUnitXmlParser
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JUnitXmlParserTest {

    @Test
    fun `parses a single suite with pass fail error and skip`() {
        val xml =
            """
            <testsuite name="com.example.FooTest" tests="4" failures="1" errors="1" skipped="1">
              <testcase classname="com.example.FooTest" name="passes" time="0.01"/>
              <testcase classname="com.example.FooTest" name="fails" time="0.02">
                <failure message="expected true">assertion trace line 1</failure>
              </testcase>
              <testcase classname="com.example.FooTest" name="errors" time="0.03">
                <error message="boom">NullPointerException at Foo.kt:10</error>
              </testcase>
              <testcase classname="com.example.FooTest" name="skips">
                <skipped/>
              </testcase>
            </testsuite>
            """.trimIndent()

        val suites = JUnitXmlParser.parse(xml)

        assertEquals(1, suites.size)
        val suite = suites.single()
        assertEquals("com.example.FooTest", suite.name)
        assertEquals(4, suite.cases.size)

        val counts = suite.counts
        assertEquals(1, counts.passed)
        assertEquals(1, counts.failed)
        assertEquals(1, counts.errored)
        assertEquals(1, counts.skipped)

        val failed = suite.cases.single { it.name == "fails" }
        assertEquals(TestStatus.FAILED, failed.status)
        assertEquals("expected true", failed.message)
        assertTrue(failed.details!!.contains("assertion trace"))
        assertEquals(0.02, failed.durationSeconds)
    }

    @Test
    fun `parses a testsuites wrapper holding several suites`() {
        val xml =
            """
            <testsuites>
              <testsuite name="A"><testcase classname="A" name="a"/></testsuite>
              <testsuite name="B"><testcase classname="B" name="b"><failure/></testcase></testsuite>
            </testsuites>
            """.trimIndent()

        val suites = JUnitXmlParser.parse(xml)

        assertEquals(2, suites.size)
        assertEquals(setOf("A", "B"), suites.map { it.name }.toSet())
    }

    @Test
    fun `pytest style case with a blank classname falls back to the suite name`() {
        val xml =
            """
            <testsuite name="tests/test_math.py">
              <testcase classname="" name="test_add" time="0.5"/>
            </testsuite>
            """.trimIndent()

        val case = JUnitXmlParser.parse(xml).single().cases.single()

        assertEquals("tests/test_math.py", case.className)
        assertEquals("tests/test_math.py.test_add", case.qualifiedName)
    }

    @Test
    fun `non junit and blank input yield no suites rather than throwing`() {
        assertTrue(JUnitXmlParser.parse("").isEmpty())
        assertTrue(JUnitXmlParser.parse("not xml at all <<<").isEmpty())
        assertTrue(JUnitXmlParser.parse("<other><thing/></other>").isEmpty())
    }

    @Test
    fun `a pytest run's single suite is grouped by module, in the order the runner ran them`() {
        val xml =
            """
            <testsuites><testsuite name="pytest" tests="3">
              <testcase classname="tests.test_cart" name="test_a" time="0.01"/>
              <testcase classname="tests.test_tax" name="test_b" time="0.01"/>
              <testcase classname="tests.test_cart" name="test_c" time="0.01"><failure message="no">boom</failure></testcase>
            </testsuite></testsuites>
            """.trimIndent()

        val suites = JUnitXmlParser.parse(xml)

        assertEquals(listOf("tests.test_cart", "tests.test_tax"), suites.map { it.name })
        assertEquals(listOf("test_a", "test_c"), suites.first().cases.map { it.name })
        assertEquals(1, suites.first().counts.failing)
    }

    @Test
    fun `a report with a DOCTYPE is refused, so an external entity is never read`() {
        val xml =
            """
            <?xml version="1.0"?>
            <!DOCTYPE testsuite [ <!ENTITY secret SYSTEM "file:///etc/passwd"> ]>
            <testsuite name="t"><testcase name="c"><failure message="&secret;"/></testcase></testsuite>
            """.trimIndent()

        assertTrue(JUnitXmlParser.parse(xml).isEmpty())
    }

    /**
     * BOSS 9.5.25 shares only some packages with a plugin: `javax.` yes, `org.w3c.` no. A DOM
     * parser passed every test here and failed inside BOSS with NoClassDefFoundError, so the
     * compiled parser must not reference `org/w3c` or `org/xml` at all.
     */
    @Test
    fun `the parser touches no package BOSS withholds from plugins`() {
        val loader = JUnitXmlParser::class.java.classLoader
        val classFiles =
            listOf("JUnitXmlParser", "JUnitXmlParser\$OpenSuite", "JUnitXmlParser\$OpenCase", "JUnitXmlParser\$Marker")
                .mapNotNull { loader.getResourceAsStream("ai/rever/boss/plugin/dynamic/testexplorer/core/$it.class") }
        assertTrue(classFiles.isNotEmpty(), "compiled parser classes should be on the test classpath")
        classFiles.forEach { stream ->
            val bytes = stream.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            assertTrue("org/w3c" !in bytes && "org/xml" !in bytes, "the parser must not load org.w3c or org.xml classes")
        }
    }
}

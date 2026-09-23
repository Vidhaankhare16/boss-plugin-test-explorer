package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.core.AffectedTests
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestFramework
import ai.rever.boss.plugin.dynamic.testexplorer.core.TestRunnerDetection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which tests a change set selects. The rules lean toward running a test when in doubt, because a
 * scoped run that silently skips the one test that would have caught the bug is worse than no scope.
 */
class AffectedTestsTest {
    private val pythonTests =
        mapOf(
            "tests/test_cart.py" to "from pricing.cart import apply_discount\n\ndef test_x():\n    pass\n",
            "tests/test_tax.py" to "import pricing.tax\n",
            "tests/test_misc.py" to "from pricing import cart, tax\n",
            "tests/test_unrelated.py" to "def test_y():\n    assert True\n",
        )

    @Test
    fun `a changed python module selects every test that imports it, in any import form`() {
        val selection = AffectedTests.select(TestFramework.PYTEST, listOf("pricing/cart.py"), pythonTests)

        assertEquals(listOf("tests/test_cart.py", "tests/test_misc.py"), selection.testFiles)
        assertFalse(selection.runEverything)
        assertEquals("1 changed file selects 2 test files.", selection.reason)
    }

    @Test
    fun `a changed test file selects itself`() {
        val selection = AffectedTests.select(TestFramework.PYTEST, listOf("tests/test_unrelated.py"), pythonTests)

        assertEquals(listOf("tests/test_unrelated.py"), selection.testFiles)
    }

    @Test
    fun `a conventionally named test is selected even without an import`() {
        val tests = mapOf("tests/test_report.py" to "def test_z():\n    pass\n")

        val selection = AffectedTests.select(TestFramework.PYTEST, listOf("app/report.py"), tests)

        assertEquals(listOf("tests/test_report.py"), selection.testFiles)
    }

    @Test
    fun `a package's __init__ counts as the package module`() {
        val tests = mapOf("tests/test_pkg.py" to "import pricing\n")

        val selection = AffectedTests.select(TestFramework.PYTEST, listOf("pricing/__init__.py"), tests)

        assertEquals(listOf("tests/test_pkg.py"), selection.testFiles)
    }

    @Test
    fun `a source file no test refers to is reported, not silently passed over`() {
        val selection = AffectedTests.select(TestFramework.PYTEST, listOf("pricing/shipping.py"), pythonTests)

        assertTrue(selection.isEmpty)
        assertEquals(listOf("pricing/shipping.py"), selection.untested)
        assertEquals("1 changed file, and no test refers to it.", selection.reason)
    }

    @Test
    fun `a changed test configuration runs everything and says why`() {
        val configs =
            listOf(
                TestFramework.PYTEST to "tests/conftest.py",
                TestFramework.PYTEST to "pyproject.toml",
                TestFramework.GRADLE to "app/build.gradle.kts",
                TestFramework.GRADLE to "gradle/libs.versions.toml",
                TestFramework.MAVEN to "pom.xml",
            )
        for ((framework, config) in configs) {
            val selection = AffectedTests.select(framework, listOf("README.md", config), emptyMap())
            assertTrue(selection.runEverything, config)
            assertTrue(selection.reason.startsWith(config), selection.reason)
        }
    }

    @Test
    fun `docs and nothing-changed select nothing`() {
        assertTrue(AffectedTests.select(TestFramework.PYTEST, listOf("README.md", "docs/a.png"), pythonTests).isEmpty)
        assertEquals("No files have changed.", AffectedTests.select(TestFramework.GRADLE, emptyList(), emptyMap()).reason)
    }

    private val jvmTests =
        mapOf(
            "app/src/test/kotlin/com/shop/CartTest.kt" to "package com.shop\n\nclass CartTest",
            "app/src/test/kotlin/com/shop/flow/CheckoutFlowTest.kt" to
                "package com.shop.flow\n\nimport com.shop.Cart\n\nclass CheckoutFlowTest { val c = Cart() }",
            "app/src/test/kotlin/com/shop/PricingTest.kt" to "package com.shop\n\nclass PricingTest { val c = Cart() }",
            "app/src/test/kotlin/com/other/StockTest.kt" to "package com.other\n\nclass StockTest { val cartridge = 1 }",
            "app/src/test/kotlin/com/other/UnimportedTest.kt" to "package com.other\n\nclass UnimportedTest { val c = Cart() }",
        )

    @Test
    fun `a changed JVM class selects its conventional test, importers, and same-package users`() {
        val selection =
            AffectedTests.select(TestFramework.GRADLE, listOf("app/src/main/kotlin/com/shop/Cart.kt"), jvmTests)

        assertEquals(
            listOf(
                "app/src/test/kotlin/com/shop/CartTest.kt",
                "app/src/test/kotlin/com/shop/PricingTest.kt",
                "app/src/test/kotlin/com/shop/flow/CheckoutFlowTest.kt",
            ),
            selection.testFiles,
            "'cartridge' is not a mention of Cart, and a Cart in another package without an import is not this Cart",
        )
    }

    @Test
    fun `the pytest command runs exactly the selected files`() {
        val command =
            TestRunnerDetection.affectedCommand(TestFramework.PYTEST, isWindows = true, listOf("tests/test_cart.py"), "r.xml")

        assertEquals(listOf("cmd", "/c", "pytest", "tests/test_cart.py", "--junitxml=r.xml"), command)
    }

    @Test
    fun `the gradle command scopes each class to its own module`() {
        val files =
            listOf(
                "app/src/test/kotlin/com/shop/CartTest.kt",
                "src/test/java/com/root/RootTest.java",
                "libs/core/src/test/kotlin/a/BTest.kt",
            )

        val command = TestRunnerDetection.affectedCommand(TestFramework.GRADLE, isWindows = false, files, "r.xml")

        assertEquals(
            listOf(
                "./gradlew",
                ":app:test", "--tests", "com.shop.CartTest",
                "test", "--tests", "com.root.RootTest",
                ":libs:core:test", "--tests", "a.BTest",
            ),
            command,
        )
    }

    @Test
    fun `the maven command selects simple class names and tolerates modules with no match`() {
        val command =
            TestRunnerDetection.affectedCommand(
                TestFramework.MAVEN,
                isWindows = false,
                listOf("src/test/java/com/shop/CartTest.java"),
                "r.xml",
            )

        assertEquals(
            listOf("mvn", "test", "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false", "-Dtest=CartTest"),
            command,
        )
    }
}

package ai.rever.boss.plugin.dynamic.testexplorer.core

/**
 * Which tests a change set touches, and why - so a run after an edit can be the tests that matter
 * rather than the whole suite.
 *
 * @property testFiles project-relative test files to run, in a stable order
 * @property runEverything a changed file can affect any test (a build or test configuration file),
 *   so scoping would be a guess; the whole suite runs instead, and [reason] says which file
 * @property reason one human line: what changed and what that selected
 * @property untested changed source files no test file references - worth telling a person or an
 *   agent, because "no test failed" says nothing about code no test covers
 */
data class AffectedSelection(
    val testFiles: List<String>,
    val runEverything: Boolean,
    val reason: String,
    val untested: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = !runEverything && testFiles.isEmpty()
}

/**
 * Maps changed files to the test files that exercise them. Pure: it takes paths and the text of the
 * project's test files, so every rule is testable without git, a build, or a disk.
 *
 * The rules are deliberately conservative, because a missed test is worse than an extra one:
 *
 * - A changed **test file** selects itself.
 * - A changed **source file** selects every test file that refers to it: for Python, an import of
 *   its module; for Kotlin and Java, an import of its class or package, or a mention of its class
 *   from the same package, where no import is needed. A conventionally named test (`test_cart.py`,
 *   `CartTest`) is selected too, even without a reference.
 * - A changed **build or test configuration file** selects everything.
 * - Anything else (docs, assets) selects nothing.
 */
object AffectedTests {
    fun select(
        framework: TestFramework,
        changedFiles: List<String>,
        testFileText: Map<String, String>,
    ): AffectedSelection {
        val changed = changedFiles.map { it.replace('\\', '/').removePrefix("./") }.distinct()
        if (changed.isEmpty()) {
            return AffectedSelection(emptyList(), runEverything = false, reason = "No files have changed.")
        }
        changed.firstOrNull { isConfig(framework, it) }?.let { config ->
            return AffectedSelection(
                emptyList(),
                runEverything = true,
                reason = "$config changed, and a change there can affect any test, so every test runs.",
            )
        }

        val selected = linkedSetOf<String>()
        val untested = mutableListOf<String>()
        for (path in changed) {
            when {
                isTestFile(framework, path) -> selected += path
                isSource(framework, path) -> {
                    val hits = testFileText.filter { (test, text) -> references(framework, path, test, text) }.keys
                    if (hits.isEmpty()) untested += path else selected += hits
                }
            }
        }
        val tests = selected.sorted()
        return AffectedSelection(tests, runEverything = false, reason = reasonFor(changed, tests), untested = untested)
    }

    /** Whether [path] is a test file of [framework], so the caller knows which files to read. */
    fun isTestFile(
        framework: TestFramework,
        path: String,
    ): Boolean =
        when (framework) {
            TestFramework.PYTEST -> {
                val name = path.substringAfterLast('/')
                name.endsWith(".py") && (name.startsWith("test_") || name.endsWith("_test.py"))
            }

            else -> isJvmSource(path) && ("/src/test/" in "/$path")
        }

    private fun isSource(
        framework: TestFramework,
        path: String,
    ): Boolean =
        when (framework) {
            TestFramework.PYTEST -> path.endsWith(".py")
            else -> isJvmSource(path)
        }

    private fun isJvmSource(path: String): Boolean = path.endsWith(".kt") || path.endsWith(".java")

    private fun isConfig(
        framework: TestFramework,
        path: String,
    ): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return when (framework) {
            TestFramework.GRADLE -> name.endsWith(".gradle") || name.endsWith(".gradle.kts") ||
                name == "gradle.properties" || name.endsWith(".versions.toml")
            TestFramework.MAVEN -> name == "pom.xml"
            TestFramework.PYTEST -> name == "conftest.py" || name in PYTHON_CONFIG || name.startsWith("requirements")
        }
    }

    private fun references(
        framework: TestFramework,
        source: String,
        test: String,
        text: String,
    ): Boolean =
        when (framework) {
            TestFramework.PYTEST -> pythonReferences(source, test, text)
            else -> jvmReferences(source, test, text)
        }

    private fun pythonReferences(
        source: String,
        test: String,
        text: String,
    ): Boolean {
        val module = source.removeSuffix(".py").removeSuffix("/__init__").replace('/', '.')
        val leaf = module.substringAfterLast('.')
        val parent = module.substringBeforeLast('.', missingDelimiterValue = "")
        val testName = test.substringAfterLast('/')
        if (testName == "test_$leaf.py" || testName == "${leaf}_test.py") return true
        val m = Regex.escape(module)
        val patterns =
            buildList {
                add("""^\s*from\s+$m(\.[\w.]+)?\s+import\b""")
                add("""^\s*import\s+$m\b""")
                if (parent.isNotEmpty()) {
                    add("""^\s*from\s+${Regex.escape(parent)}\s+import\s+[^\n]*\b${Regex.escape(leaf)}\b""")
                }
            }
        return patterns.any { Regex(it, RegexOption.MULTILINE).containsMatchIn(text) }
    }

    private fun jvmReferences(
        source: String,
        test: String,
        text: String,
    ): Boolean {
        val className = source.substringAfterLast('/').substringBeforeLast('.')
        val testClass = test.substringAfterLast('/').substringBeforeLast('.')
        if (testClass in conventionalTestNames(className)) return true
        val word = Regex("""\b${Regex.escape(className)}\b""")
        if (!word.containsMatchIn(text)) return false
        val sourcePackage = packageOf(source)
        val imported =
            sourcePackage.isNotEmpty() &&
                Regex("""^\s*import\s+${Regex.escape(sourcePackage)}\.(${Regex.escape(className)}|\*)\b""", RegexOption.MULTILINE)
                    .containsMatchIn(text)
        // The same package needs no import, so a mention by name is the reference.
        return imported || packageOf(test) == sourcePackage
    }

    private fun conventionalTestNames(className: String): Set<String> =
        setOf("${className}Test", "${className}Tests", "${className}Spec", "${className}IT", "Test$className")

    /** `com.example` for `app/src/main/kotlin/com/example/Foo.kt`, from the path under the source root. */
    private fun packageOf(path: String): String {
        val underRoot = SOURCE_ROOT.find("/$path")?.let { "/$path".substring(it.range.last + 1) } ?: return ""
        return underRoot.substringBeforeLast('/', missingDelimiterValue = "").replace('/', '.')
    }

    private fun reasonFor(
        changed: List<String>,
        tests: List<String>,
    ): String {
        val files = if (changed.size == 1) "1 changed file" else "${changed.size} changed files"
        val selects = if (changed.size == 1) "selects" else "select"
        return when (tests.size) {
            0 -> "$files, and no test refers to ${if (changed.size == 1) "it" else "any of them"}."
            1 -> "$files $selects 1 test file: ${tests.single()}."
            else -> "$files $selects ${tests.size} test files."
        }
    }

    private val SOURCE_ROOT = Regex("""/src/(main|test)/(kotlin|java)/""")
    private val PYTHON_CONFIG = setOf("pyproject.toml", "pytest.ini", "setup.cfg", "tox.ini", "setup.py")
}

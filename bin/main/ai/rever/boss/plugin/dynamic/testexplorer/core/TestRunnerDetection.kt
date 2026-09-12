package ai.rever.boss.plugin.dynamic.testexplorer.core

/**
 * A test framework this plugin knows how to drive, and everything framework specific about it:
 * how it is detected, how it is invoked, where it writes its JUnit XML, and how a rerun of just
 * the failures is expressed. Keeping all of that here, as pure data and pure functions over file
 * names, is what lets it be unit tested without a project or a process.
 */
enum class TestFramework(val displayName: String) {
    GRADLE("Gradle"),
    MAVEN("Maven"),
    PYTEST("pytest"),
}

/**
 * Where a run's XML reports land, relative to the project root. The engine walks these for
 * `*.xml`; an empty list means the runner writes to a path chosen per run instead
 * (pytest's `--junitxml`), which the engine supplies.
 */
private val RESULT_DIRS: Map<TestFramework, List<String>> =
    mapOf(
        // Gradle writes per (sub)project under build/test-results/<task>; walk broadly so a
        // multi-module build is covered without enumerating modules.
        TestFramework.GRADLE to listOf("build/test-results"),
        TestFramework.MAVEN to listOf("target/surefire-reports", "target/failsafe-reports"),
        TestFramework.PYTEST to emptyList(),
    )

object TestRunnerDetection {

    /**
     * The framework a project uses, from the names of the files in its root, or null when none is
     * recognised. Gradle and Maven win over pytest when both are present: a JVM project that also
     * carries a `pyproject.toml` for tooling is still a JVM project, and running its build tool is
     * what the user means by "run the tests".
     */
    fun detect(rootFileNames: Set<String>): TestFramework? {
        val names = rootFileNames.map { it.lowercase() }.toSet()
        return when {
            names.any { it in GRADLE_MARKERS } -> TestFramework.GRADLE
            "pom.xml" in names -> TestFramework.MAVEN
            names.any { it in PYTEST_MARKERS } -> TestFramework.PYTEST
            else -> null
        }
    }

    /** Result directories to walk for [framework], relative to the project root. */
    fun resultDirs(framework: TestFramework): List<String> = RESULT_DIRS.getValue(framework)

    /**
     * The argv for a full test run. On Windows the whole command is run through `cmd /c` so that a
     * `.bat` wrapper and PATH-resolved launchers (`mvn.cmd`, `pytest`) are found the way they are
     * from a prompt; on other platforms it is the bare command. [reportPath] is where pytest is
     * told to write its JUnit XML (keep it relative to the project so it never carries a space that
     * `cmd` would resplit) and is ignored by the other frameworks.
     */
    fun fullRunCommand(
        framework: TestFramework,
        isWindows: Boolean,
        reportPath: String,
    ): List<String> = wrap(isWindows, bareFullRun(framework, isWindows, reportPath))

    private fun bareFullRun(
        framework: TestFramework,
        isWindows: Boolean,
        reportPath: String,
    ): List<String> =
        when (framework) {
            TestFramework.GRADLE -> listOf(gradleExe(isWindows), "test")
            TestFramework.MAVEN -> listOf("mvn", "test")
            TestFramework.PYTEST -> listOf("pytest", "--junitxml=$reportPath")
        }

    /**
     * The argv for rerunning only [failures]. Falls back to a full run when there is nothing to
     * rerun, so a caller never has to special-case an empty list.
     *
     * The three frameworks express "just these" very differently, and each choice is the robust
     * one for that tool rather than the cleverest: Gradle takes a `--tests` filter per failing
     * case by fully qualified name; Maven's `-Dtest` takes simple class names with `#method`
     * selectors; pytest is told `--last-failed`, which reruns exactly what its own cache recorded
     * as failing and so does not depend on this plugin reconstructing pytest node ids.
     */
    fun rerunCommand(
        framework: TestFramework,
        isWindows: Boolean,
        failures: List<TestCaseResult>,
        reportPath: String,
    ): List<String> {
        if (failures.isEmpty()) return fullRunCommand(framework, isWindows, reportPath)
        val bare =
            when (framework) {
                TestFramework.GRADLE ->
                    listOf(gradleExe(isWindows), "test") +
                        failures.flatMap { listOf("--tests", it.qualifiedName) }

                TestFramework.MAVEN ->
                    listOf("mvn", "test", "-DfailIfNoTests=false", "-Dtest=${mavenSelector(failures)}")

                TestFramework.PYTEST ->
                    listOf("pytest", "--last-failed", "--junitxml=$reportPath")
            }
        return wrap(isWindows, bare)
    }

    /** `com.example.FooTest#a+b,com.example.BarTest#c` - Surefire's simple-name selector syntax. */
    private fun mavenSelector(failures: List<TestCaseResult>): String =
        failures
            .groupBy { it.className.substringAfterLast('.').ifBlank { it.className } }
            .entries
            .joinToString(",") { (simpleClass, cases) ->
                val methods = cases.map { it.name }.filter { it.isNotBlank() }
                if (methods.isEmpty()) simpleClass else "$simpleClass#${methods.joinToString("+")}"
            }

    private fun gradleExe(isWindows: Boolean): String = if (isWindows) "gradlew.bat" else "./gradlew"

    /** Wrap a PATH/wrapper command in `cmd /c` on Windows so `.bat` and `.cmd` launchers resolve. */
    private fun wrap(isWindows: Boolean, bare: List<String>): List<String> =
        if (isWindows) listOf("cmd", "/c") + bare else bare

    private val GRADLE_MARKERS =
        setOf("gradlew", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

    private val PYTEST_MARKERS =
        setOf("pytest.ini", "tox.ini", "conftest.py", "setup.cfg", "pyproject.toml")
}

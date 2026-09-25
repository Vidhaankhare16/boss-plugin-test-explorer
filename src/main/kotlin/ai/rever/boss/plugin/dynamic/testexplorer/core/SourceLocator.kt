package ai.rever.boss.plugin.dynamic.testexplorer.core

import java.io.File

/**
 * Where a failing test broke: a file in the project and a 1-based line in it.
 *
 * [path] is absolute, because that is what the host's editor opens and what an agent can act on
 * without knowing the project root.
 */
data class SourceLocation(
    val path: String,
    val line: Int,
) {
    val fileName: String
        get() = File(path).name

    /** `src/test/kotlin/FooTest.kt:42`, relative to [projectRoot] when the file is inside it. */
    fun display(projectRoot: File?): String {
        val shown = projectRoot?.let { relativeTo(it) } ?: path
        return "$shown:$line"
    }

    private fun relativeTo(root: File): String? {
        val file = File(path)
        return runCatching { file.relativeTo(root).invariantSeparatorsPath }
            .getOrNull()
            ?.takeUnless { it.startsWith("..") }
    }
}

/**
 * The frame a report points at, before it is matched to a file on disk.
 *
 * [relativePath] is what the report knows: `com/example/FooTest.kt` for a JVM frame (package path
 * plus the frame's file name) or `tests/test_math.py` for a pytest one. [fileName] is its last
 * segment, the fallback when the file does not live where its package says (Kotlin allows that).
 */
data class SourceFrame(
    val relativePath: String,
    val line: Int,
) {
    val fileName: String
        get() = relativePath.substringAfterLast('/')
}

/**
 * Turns a failing [TestCaseResult] into the place a person or an agent should look.
 *
 * Reading the location from the report rather than guessing from the test's name is the point:
 * the line comes from the runner's own stack trace, so it is the line that actually failed.
 *
 * - **JVM** (Gradle, Maven): the first stack frame whose class is the test class itself, or one of
 *   its nested/lambda classes (`FooTest$inner`). Frames from the assertion library above it are
 *   skipped, because the person wants the test's own line, not JUnit's.
 * - **pytest**: the `path.py:LINE:` frames in the failure body. The one in the test's own module
 *   wins; otherwise the last frame, which is where pytest reports the assertion.
 *
 * [frameFor] is pure. [locate] resolves a frame against the project on disk with a bounded walk that
 * skips build output and dependency folders, so a large project cannot stall it.
 */
object SourceLocator {
    // The method part allows spaces: a Kotlin test named `adds two numbers` appears in the trace as
    // `FooTest.adds two numbers(FooTest.kt:14)`.
    private val jvmFrame = Regex("""at\s+([\w.$]+)\.[^(\s][^(\n]*\(([^:()]+):(\d+)\)""")
    // An optional drive prefix, because pytest on Windows can print `C:\proj\tests\test_x.py:12:`.
    private val pythonFrame = Regex("""(?m)^\s*((?:[A-Za-z]:)?[^\s:][^:\n]*\.py):(\d+):""")

    /** Directories that never hold a project's own test sources. */
    private val skippedDirs =
        setOf(
            ".git", ".gradle", ".idea", ".kotlin", "build", "out", "target", "bin",
            "node_modules", "__pycache__", ".pytest_cache", ".venv", "venv", ".tox", "dist",
        )

    fun frameFor(case: TestCaseResult): SourceFrame? {
        if (!case.failed) return null
        val trace = listOfNotNull(case.details, case.message).joinToString("\n")
        if (trace.isBlank()) return null
        return jvmFrameFor(case.className, trace) ?: pythonFrameFor(case.className, trace)
    }

    /**
     * Where [case] failed inside [projectRoot], or null when the report names no frame or the file
     * cannot be found. [maxDepth] bounds the directory walk.
     */
    fun locate(
        case: TestCaseResult,
        projectRoot: File,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
    ): SourceLocation? {
        val frame = frameFor(case) ?: return null
        val file = resolve(frame, projectRoot, maxDepth) ?: return null
        return SourceLocation(file.absolutePath, frame.line)
    }

    /** [locate] for every failure in [report], keyed by [TestCaseResult.qualifiedName]. */
    fun locateAll(
        report: TestRunReport,
        projectRoot: File,
    ): Map<String, SourceLocation> =
        report.failures
            .mapNotNull { case -> locate(case, projectRoot)?.let { case.qualifiedName to it } }
            .toMap()

    private fun jvmFrameFor(
        className: String,
        trace: String,
    ): SourceFrame? {
        if (className.isBlank()) return null
        val match =
            jvmFrame.findAll(trace).firstOrNull { frame ->
                val frameClass = frame.groupValues[1]
                frameClass == className || frameClass.startsWith("$className$")
            } ?: return null
        val packagePath = className.substringBeforeLast('.', missingDelimiterValue = "").replace('.', '/')
        val fileName = match.groupValues[2]
        val relative = if (packagePath.isEmpty()) fileName else "$packagePath/$fileName"
        return SourceFrame(relative, match.groupValues[3].toInt())
    }

    private fun pythonFrameFor(
        className: String,
        trace: String,
    ): SourceFrame? {
        val frames =
            pythonFrame.findAll(trace).map { SourceFrame(it.groupValues[1].replace('\\', '/'), it.groupValues[2].toInt()) }
                .toList()
        if (frames.isEmpty()) return null
        // pytest's classname is the dotted module, optionally followed by a test class:
        // `tests.test_math` or `tests.test_math.TestAdd`. Any dotted prefix may be the module.
        val modules =
            className.split('.').let { parts ->
                (parts.size downTo 1).map { parts.take(it).joinToString("/") + ".py" }
            }
        return frames.firstOrNull { frame -> modules.any { frame.relativePath.endsWith(it) } } ?: frames.last()
    }

    private fun resolve(
        frame: SourceFrame,
        root: File,
        maxDepth: Int,
    ): File? {
        File(frame.relativePath).takeIf { it.isAbsolute && it.isFile }?.let { return it }
        File(root, frame.relativePath).takeIf { it.isFile }?.let { return it }

        val suffix = "/" + frame.relativePath
        val byName = mutableListOf<File>()
        for (file in walk(root, maxDepth)) {
            if (file.name != frame.fileName) continue
            if (file.invariantSeparatorsPath.endsWith(suffix)) return file
            byName += file
        }
        // Not where its package says - legal in Kotlin. Only trust a name that is unambiguous.
        return byName.singleOrNull()
    }

    private fun walk(
        root: File,
        maxDepth: Int,
    ): Sequence<File> =
        root
            .walkTopDown()
            .maxDepth(maxDepth)
            .onEnter { dir -> dir == root || dir.name !in skippedDirs }
            .filter { it.isFile }

    private const val DEFAULT_MAX_DEPTH = 12
}

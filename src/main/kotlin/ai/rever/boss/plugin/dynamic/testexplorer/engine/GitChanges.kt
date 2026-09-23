package ai.rever.boss.plugin.dynamic.testexplorer.engine

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The files changed in a project since its last commit, as git sees them: staged, unstaged and
 * untracked, relative to the project directory.
 *
 * Paths are relative to the *project*, not the repository, because a project can be one directory
 * of a larger repository and every other path this plugin handles is project-relative. That is why
 * this asks `git diff --relative` and `git ls-files` (both relative to the working directory)
 * rather than `git status`, whose porcelain paths are always repository-relative.
 *
 * [exec] runs one git command in the project and returns its stdout, or null when it failed; it is
 * a parameter so the parsing and fallback rules are testable without git.
 */
class GitChanges(
    private val projectDir: File,
    private val exec: (List<String>) -> String? = { args -> runGit(projectDir, args) },
) {
    /** The changed files, or null when [projectDir] is not inside a git work tree. */
    fun changedFiles(): List<String>? {
        if (exec(listOf("rev-parse", "--is-inside-work-tree"))?.trim() != "true") return null
        // Against HEAD covers staged and unstaged together. A repository with no commit yet has no
        // HEAD, and then everything tracked counts as changed.
        val tracked =
            exec(listOf("diff", "--name-only", "--relative", "-z", "HEAD"))
                ?: exec(listOf("ls-files", "-z", "--cached"))
                ?: ""
        val untracked = exec(listOf("ls-files", "-z", "--others", "--exclude-standard")).orEmpty()
        return (split(tracked) + split(untracked)).distinct()
    }

    private fun split(nulSeparated: String): List<String> = nulSeparated.split('\u0000').filter { it.isNotBlank() }

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 20L
        const val DRAIN_JOIN_MILLIS = 2_000L

        /**
         * Runs git and returns its stdout, or null on failure or timeout.
         *
         * The output is drained on its own thread while this one waits with the timeout. Reading it
         * to the end first and then calling the timed `waitFor` would make the timeout meaningless:
         * a git blocked on an index lock never closes stdout, so the read alone would hang the run.
         */
        fun runGit(
            dir: File,
            args: List<String>,
        ): String? =
            runCatching {
                val process =
                    ProcessBuilder(listOf("git") + args)
                        .directory(dir)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                val out = StringBuilder()
                val drain =
                    Thread { process.inputStream.bufferedReader().use { out.append(it.readText()) } }
                        .apply {
                            isDaemon = true
                            start()
                        }
                val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                drain.join(DRAIN_JOIN_MILLIS)
                out.toString().takeIf { finished && process.exitValue() == 0 }
            }.getOrNull()
    }
}

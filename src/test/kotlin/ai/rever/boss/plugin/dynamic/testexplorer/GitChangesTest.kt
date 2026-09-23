package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.dynamic.testexplorer.engine.GitChanges
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reading the change set from git's NUL-separated output, with git itself replaced by a script. */
class GitChangesTest {
    private fun changes(vararg answers: Pair<String, String?>): GitChanges {
        val script = answers.toMap()
        return GitChanges(File(".")) { args -> script[args.joinToString(" ")] }
    }

    @Test
    fun `tracked and untracked changes are combined, relative to the project, without duplicates`() {
        val git =
            changes(
                "rev-parse --is-inside-work-tree" to "true\n",
                "diff --name-only --relative -z HEAD" to "pricing/cart.py\u0000tests/test cart.py\u0000",
                "ls-files -z --others --exclude-standard" to "pricing/new.py\u0000pricing/cart.py\u0000",
            )

        assertEquals(listOf("pricing/cart.py", "tests/test cart.py", "pricing/new.py"), git.changedFiles())
    }

    @Test
    fun `a repository with no commit yet treats everything tracked as changed`() {
        val git =
            changes(
                "rev-parse --is-inside-work-tree" to "true",
                "diff --name-only --relative -z HEAD" to null,
                "ls-files -z --cached" to "a.py\u0000",
                "ls-files -z --others --exclude-standard" to "",
            )

        assertEquals(listOf("a.py"), git.changedFiles())
    }

    @Test
    fun `outside a git work tree there is no change set`() {
        assertNull(changes("rev-parse --is-inside-work-tree" to null).changedFiles())
    }
}

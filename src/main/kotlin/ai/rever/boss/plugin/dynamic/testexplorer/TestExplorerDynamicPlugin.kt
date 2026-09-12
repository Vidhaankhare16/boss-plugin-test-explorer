package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Test Explorer - a dynamic BOSS plugin.
 *
 * Runs the open project's test suite (Gradle, Maven, or pytest), shows a pass/fail tree in a
 * sidebar panel, and contributes `test_*` MCP tools so an agent in a terminal can run the tests
 * and read structured results. The panel and the tools drive one shared [TestExplorerSession], so
 * a run either of them starts is the same run.
 */
class TestExplorerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.testexplorer"
    override val displayName: String = "Test Explorer"
    override val version: String = "0.1.0"
    override val description: String = "Run the open project's tests and see a pass/fail tree, plus test_* MCP tools"
    override val author: String = "Vidhaankhare16"
    override val url: String = "https://github.com/Vidhaankhare16/boss-plugin-test-explorer"

    private var session: TestExplorerSession? = null

    override fun register(context: PluginContext) {
        // projectPath is read fresh on every run, so a run always targets whatever project is open
        // at the moment it starts. Blank means no project; the session reports that plainly.
        val session = TestExplorerSession(projectPathSupplier = { context.projectPath })
        this.session = session

        context.panelRegistry.registerPanel(TestExplorerInfo) { ctx, panelInfo ->
            TestExplorerComponent(ctx, panelInfo, session)
        }
        // Contribute test_* MCP tools; auto-removed when this plugin is disabled or unloaded.
        context.registerMcpToolProvider(TestExplorerMcpToolProvider(pluginId, session))
    }

    override fun dispose() {
        session?.dispose()
        session = null
    }
}

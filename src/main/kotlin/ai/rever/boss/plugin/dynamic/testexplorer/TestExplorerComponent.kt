package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * The Test Explorer panel component. Holds nothing of its own: the state lives in the shared
 * [TestExplorerSession] so the panel and the MCP tools show the same run.
 */
class TestExplorerComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val session: TestExplorerSession,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        TestExplorerContent(session)
    }
}

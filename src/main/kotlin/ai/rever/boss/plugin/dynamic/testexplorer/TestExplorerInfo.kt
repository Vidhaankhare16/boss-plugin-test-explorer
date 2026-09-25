package ai.rever.boss.plugin.dynamic.testexplorer

import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckCircle

/**
 * The Test Explorer panel: a run button and a pass/fail tree, in the left sidebar's lower slot
 * next to the other project-oriented panels (files, changes, run configurations).
 */
object TestExplorerInfo : PanelInfo {
    override val id = PanelId("test-explorer", 30)
    // The plugin's own name, not a shorter one: a person told to open "Test Explorer" looks for
    // exactly that in the sidebar, the Toolbox menu and the tools launcher.
    override val displayName = "Test Explorer"
    override val icon = FeatherIcons.CheckCircle
    override val defaultSlotPosition = left.bottom
}

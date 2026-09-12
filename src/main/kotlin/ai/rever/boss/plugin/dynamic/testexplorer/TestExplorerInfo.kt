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
    override val displayName = "Tests"
    override val icon = FeatherIcons.CheckCircle
    override val defaultSlotPosition = left.bottom
}

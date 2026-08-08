package com.dmc.bridge

import ai.kilocode.client.session.SessionManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

private val LOG = logger<DmcSessionResolver>()

object DmcSessionResolver {

    private const val TOOL_WINDOW_ID = "Kilo Code"

    /**
     * Get the [SessionManager] for the currently visible Kilo Code tool window.
     *
     * The tool window's content component is a [DataProvider] registered by
     * [ai.kilocode.client.session.SessionSidePanelManager] that returns itself
     * for [SessionManager.KEY]. We access it directly via the content manager
     * instead of traversing from the tool window root (which looks upward,
     * not into children).
     */
    fun getSessionManager(project: Project): SessionManager? {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (tw == null) {
            LOG.warn("Tool window '$TOOL_WINDOW_ID' not found")
            return null
        }

        val cm = tw.contentManager
        val content = cm.getContent(0) ?: cm.selectedContent
        if (content == null) {
            LOG.warn("No content in Kilo Code tool window")
            return null
        }

        val component = content.component
        LOG.info("Content component: ${component?.javaClass?.name}")

        // SessionSidePanelManager.component IS a DataProvider
        if (component is DataProvider) {
            val result = component.getData(SessionManager.KEY.name)
            if (result is SessionManager) {
                LOG.info("SessionManager resolved via DataProvider: ${result.javaClass.name}")
                return result
            }
            LOG.warn("DataProvider did not return a SessionManager (got: ${result?.javaClass?.name})")
        } else {
            LOG.warn("Content component is not a DataProvider: ${component?.javaClass?.name}")
        }

        return null
    }
}

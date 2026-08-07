package com.dmc.bridge

import ai.kilocode.client.session.SessionManager
import com.intellij.ide.DataManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Resolves the active Kilo Code session from any Action context.
 *
 * Requires the custom_change in frontend/SessionManager.kt and
 * frontend/SessionSidePanelManager.kt to expose activeSessionId().
 */
object DmcSessionResolver {

    private const val TOOL_WINDOW_ID = "Kilo Code"

    /**
     * Get the session ID of the currently visible Kilo Code session.
     * Returns null if the tool window is not open or no session is active.
     */
    fun getActiveSessionId(project: Project): String? {
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
            ?: return null
        val ctx = DataManager.getInstance().getDataContext(tw.component)
        val manager = SessionManager.KEY.getData(ctx) ?: return null
        return manager.activeSessionId()
    }
}

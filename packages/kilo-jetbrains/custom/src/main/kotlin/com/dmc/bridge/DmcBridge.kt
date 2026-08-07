package com.dmc.bridge

import ai.kilocode.rpc.dto.PromptPartDto
import com.intellij.openapi.project.Project

/**
 * Bridge interface for sending content to the active Kilo session.
 *
 * Implemented by [DmcBridgeService] which resolves the active session
 * via [DmcSessionResolver] and sends prompts via
 * [ai.kilocode.backend.app.KiloBackendChatManager.prompt].
 */
interface DmcBridge {

    /**
     * Send a prompt with optional file parts to the active session.
     *
     * @param project The current project (for session resolution).
     * @param text The prompt text.
     * @param parts File/selection parts to attach.
     * @return true if the prompt was sent successfully.
     */
    fun sendToSession(project: Project, text: String, parts: List<PromptPartDto> = emptyList()): Boolean
}

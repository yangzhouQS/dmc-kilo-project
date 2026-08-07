package com.dmc.bridge

import ai.kilocode.rpc.dto.PromptPartDto

/**
 * Bridge interface for sending content to the active Kilo session.
 *
 * Implemented by [DmcBridgeService] which resolves the CLI connection
 * (port + password) from [ai.kilocode.backend.app.KiloBackendAppService]
 * and sends prompts via HTTP POST /session/{id}/prompt_async.
 */
interface DmcBridge {

    /** Whether the CLI backend is connected and a session is available. */
    val isReady: Boolean

    /**
     * Send a prompt with optional file parts to the active session.
     *
     * @param text The prompt text.
     * @param parts File/selection parts to attach (see [PromptPartDto]).
     */
    fun sendToSession(text: String, parts: List<PromptPartDto> = emptyList())
}

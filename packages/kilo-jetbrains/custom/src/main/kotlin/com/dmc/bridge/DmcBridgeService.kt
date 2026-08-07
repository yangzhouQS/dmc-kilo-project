package com.dmc.bridge

import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.rpc.dto.PromptDto
import ai.kilocode.rpc.dto.PromptPartDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<DmcBridgeService>()

/**
 * Service that bridges DMC actions to the active Kilo Code session.
 *
 * Uses [KiloBackendChatManager.prompt] (pre-authenticated HTTP) instead of
 * raw OkHttp + password reflection. Session ID resolved via [DmcSessionResolver].
 */
class DmcBridgeService(
    private val scope: CoroutineScope,
) : DmcBridge {

    override fun sendToSession(project: Project, text: String, parts: List<PromptPartDto>): Boolean {
        val sessionId = DmcSessionResolver.getActiveSessionId(project)
        if (sessionId == null) {
            LOG.warn("No active Kilo session found")
            return false
        }

        val app = app()
        if (app == null || app.port <= 0) {
            LOG.warn("Kilo backend not connected")
            return false
        }

        val dir = project.basePath ?: ""

        scope.launch {
            sendPrompt(app, sessionId, dir, text, parts)
        }
        return true
    }

    @RequiresBackgroundThread
    private suspend fun sendPrompt(
        app: KiloBackendAppService,
        sessionId: String,
        dir: String,
        text: String,
        parts: List<PromptPartDto>,
    ) {
        val allParts = buildList {
            if (text.isNotEmpty()) {
                add(PromptPartDto(type = "text", text = text))
            }
            addAll(parts)
        }
        val dto = PromptDto(parts = allParts)

        withContext(Dispatchers.IO) {
            try {
                app.chat.prompt(sessionId, dir, dto)
                LOG.info("Prompt sent to session $sessionId (${allParts.size} parts)")
            } catch (e: Exception) {
                LOG.warn("Failed to send prompt to session $sessionId: ${e.message}", e)
            }
        }
    }

    private fun app(): KiloBackendAppService? =
        ApplicationManager.getApplication().getService(KiloBackendAppService::class.java)

    companion object {
        fun getInstance(): DmcBridgeService =
            ApplicationManager.getApplication().getService(DmcBridgeService::class.java)
    }
}

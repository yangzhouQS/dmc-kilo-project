package com.dmc.bridge

import ai.kilocode.rpc.dto.PromptPartDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

private val LOG = logger<DmcBridgeService>()

@Service
class DmcBridgeService(
    @Suppress("unused") private val scope: CoroutineScope,
) {

    fun sendToSession(project: Project, text: String, parts: List<PromptPartDto>): Boolean {
        LOG.info("sendToSession: project=${project.name}, text='$text', parts=${parts.size}")

        val manager = DmcSessionResolver.getSessionManager(project)
        if (manager == null) {
            LOG.warn("No active Kilo session manager found")
            return false
        }

        return try {
            LOG.info("Calling manager.sendPrompt...")
            val result = manager.sendPrompt(text, parts)
            LOG.info("manager.sendPrompt returned: $result")
            result
        } catch (e: Exception) {
            LOG.warn("Failed: ${e.message}", e)
            false
        }
    }

    companion object {
        fun getInstance(): DmcBridgeService =
            ApplicationManager.getApplication().getService(DmcBridgeService::class.java)
    }
}

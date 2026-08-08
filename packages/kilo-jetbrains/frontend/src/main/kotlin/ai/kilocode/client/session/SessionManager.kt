package ai.kilocode.client.session

import ai.kilocode.client.app.Workspace
import ai.kilocode.rpc.dto.PromptPartDto // custom_change
import ai.kilocode.rpc.dto.SessionDto
import com.intellij.openapi.actionSystem.DataKey

interface SessionManager {
    companion object {
        val KEY = DataKey.create<SessionManager>("ai.kilocode.client.session.SessionManager")
        val WORKSPACE_KEY = DataKey.create<Workspace>("ai.kilocode.client.session.Workspace")
    }

    fun newSession()

    fun showHistory()

    fun openSession(ref: SessionRef)

    fun activity(): Map<String, SessionActivityKind> = emptyMap()

    fun titles(): Map<String, String> = emptyMap()

    fun activityChanged() {}

    fun focusPrompt() {}

    fun activeSessionId(): String? = null // custom_change

    fun sendPrompt(text: String, parts: List<PromptPartDto> = emptyList()): Boolean = false // custom_change

    fun insertPromptText(text: String) {} // custom_change

    fun openSession(session: SessionDto) {
        openSession(SessionRef.Local(session))
    }
}

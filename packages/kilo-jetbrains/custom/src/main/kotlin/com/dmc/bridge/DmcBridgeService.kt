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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val LOG = logger<DmcBridgeService>()
private val JSON = "application/json; charset=utf-8".toMediaType()

/**
 * Service implementation that bridges DMC actions to the Kilo CLI session.
 *
 * Resolves port from [KiloBackendAppService] (public getter).
 * Password requires a minimal custom_change in backend to expose it.
 * Until that change is applied, [password] returns empty and prompts
 * will be sent without auth (works only if CLI runs unsecured).
 */
class DmcBridgeService(
    private val scope: CoroutineScope,
) : DmcBridge {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override val isReady: Boolean
        get() = app()?.port?.let { it > 0 } ?: false

    override fun sendToSession(text: String, parts: List<PromptPartDto>) {
        val app = app() ?: run {
            LOG.warn("Backend not connected, cannot send prompt")
            return
        }
        if (app.port <= 0) {
            LOG.warn("Backend port not available")
            return
        }

        scope.launch {
            sendPromptInternal(app.port, resolvePassword(app), text, parts)
        }
    }

    @RequiresBackgroundThread
    private suspend fun sendPromptInternal(
        port: Int,
        password: String,
        text: String,
        parts: List<PromptPartDto>,
    ) {
        val body = buildPromptJson(text, parts)
        val url = "http://127.0.0.1:$port"

        withContext(Dispatchers.IO) {
            // TODO: resolve the active session ID from the frontend session service.
            // For now, this sends to the most recent session. A full implementation
            // should query KiloSessionService for the active session.
            val sessionId = "TODO_ACTIVE_SESSION_ID"

            val builder = Request.Builder()
                .url("$url/session/$sessionId/prompt_async")
                .post(body.toRequestBody(JSON))

            if (password.isNotEmpty()) {
                val credentials = okhttp3.Credentials.basic("kilo", password)
                builder.header("Authorization", credentials)
            }

            try {
                http.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        LOG.warn("prompt_async failed: HTTP ${response.code}")
                    } else {
                        LOG.info("prompt_async sent successfully")
                    }
                }
            } catch (e: Exception) {
                LOG.warn("prompt_async error: ${e.message}", e)
            }
        }
    }

    private fun app(): KiloBackendAppService? =
        ApplicationManager.getApplication().getService(KiloBackendAppService::class.java)

    /**
     * Resolve the CLI server password.
     *
     * [KiloBackendAppService] exposes [port] publicly but [password] is private.
     * Apply the custom_change in KiloBackendConnectionService.kt to expose
     * password, or use reflection as a fallback.
     */
    private fun resolvePassword(app: KiloBackendAppService): String {
        // Option A: After applying custom_change, call app.password directly
        // return app.password

        // Option B: Reflection fallback (no upstream change needed)
        return try {
            val connField = app.javaClass.getDeclaredField("connection")
            connField.isAccessible = true
            val connection = connField.get(app)
            val pwdField = connection?.javaClass?.getDeclaredField("password")
            pwdField?.isAccessible = true
            pwdField?.get(connection) as? String ?: ""
        } catch (e: Exception) {
            LOG.warn("Could not resolve password via reflection: ${e.message}")
            ""
        }
    }

    companion object {
        fun getInstance(): DmcBridgeService =
            ApplicationManager.getApplication().getService(DmcBridgeService::class.java)
    }
}

private fun buildPromptJson(text: String, parts: List<PromptPartDto>): String {
    val sb = StringBuilder()
    sb.append("""{"parts":[""")

    var first = true
    if (text.isNotEmpty()) {
        sb.append("""{"type":"text","text":""").append(escape(text)).append("}")
        first = false
    }
    for (part in parts) {
        if (!first) sb.append(",")
        sb.append(buildPartJson(part))
        first = false
    }
    sb.append("]}")
    return sb.toString()
}

private fun buildPartJson(part: PromptPartDto): String {
    val fields = mutableListOf("\"type\":\"${part.type}\"")
    if (part.type == "file") {
        part.url?.let { fields += "\"url\":${escape(it)}" }
        part.filename?.let { fields += "\"filename\":${escape(it)}" }
        part.mime?.let { fields += "\"mime\":${escape(it)}" }
        part.source?.let { src ->
            val srcFields = mutableListOf("\"type\":\"${src.type}\"")
            src.path?.let { srcFields += "\"path\":${escape(it)}" }
            val text = src.text
            srcFields += "\"text\":{\"value\":${escape(text.value)},\"start\":${text.start},\"end\":${text.end}}"
            fields += "\"source\":{${srcFields.joinToString(",")}}"
        }
    } else {
        fields += "\"text\":${escape(part.text ?: "")}"
    }
    return "{${fields.joinToString(",")}}"
}

private fun escape(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) {
                sb.append("\\u%04x".format(c.code))
            } else {
                sb.append(c)
            }
        }
    }
    return sb.append("\"").toString()
}

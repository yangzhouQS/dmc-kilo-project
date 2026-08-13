package com.dmc.prompt

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.UUID

object PromptManager {

    private val storage get() = PromptStorage.getInstance()

    fun getAll(): List<PromptItem> =
        storage.items.sortedWith(
            compareByDescending<PromptItem> { it.isTop }.thenByDescending { it.updateTime }
        )

    fun search(query: String): List<PromptItem> {
        if (query.isBlank()) return getAll()
        val q = query.lowercase()
        return getAll().filter {
            it.name.lowercase().contains(q) ||
                it.content.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                it.tags.any { tag -> tag.lowercase().contains(q) }
        }
    }

    fun getByCategory(category: String): List<PromptItem> =
        getAll().filter { it.category == category }

    fun getCategories(): List<String> =
        storage.items.map { it.category }.distinct().sorted()

    fun add(name: String, content: String, category: String = "通用", tags: List<String> = emptyList()): PromptItem {
        val item = PromptItem(name = name, content = content, category = category, tags = tags)
        storage.add(item)
        return item
    }

    fun update(
        id: String,
        name: String? = null,
        content: String? = null,
        category: String? = null,
        tags: List<String>? = null,
    ) {
        storage.update(id) { item ->
            name?.let { item.name = name }
            content?.let { item.content = content }
            category?.let { item.category = category }
            tags?.let { item.tags = tags }
        }
    }

    fun remove(id: String) = storage.remove(id)

    fun toggleTop(id: String) {
        storage.update(id) { it.isTop = !it.isTop }
    }

    fun exportToJson(): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(storage.items)
    }

    fun importFromJson(json: String): ImportResult {
        val type = object : TypeToken<List<PromptItem>>() {}.type
        val imported: List<PromptItem> = try {
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            return ImportResult(0, 0, "JSON 格式错误: ${e.message}")
        }

        var added = 0
        var skipped = 0
        for (item in imported) {
            val existing = storage.items.find { it.name == item.name }
            if (existing != null) {
                storage.add(item.copy(id = UUID.randomUUID().toString(), name = "${item.name} (导入)"))
                skipped++
            } else {
                storage.add(item.copy(id = UUID.randomUUID().toString()))
                added++
            }
        }
        return ImportResult(added, skipped, null)
    }

    data class ImportResult(
        val added: Int,
        val skipped: Int,
        val error: String?,
    )
}

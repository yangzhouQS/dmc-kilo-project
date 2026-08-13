package com.dmc.prompt

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "com.dmc.kilo.prompt-collection",
    storages = [Storage("kilo-prompt-collection.xml")]
)
@Service
class PromptStorage : PersistentStateComponent<PromptStorage.State> {

    data class State(var items: MutableList<PromptItem> = mutableListOf())

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(loaded: State) {
        myState = loaded
    }

    val items: List<PromptItem> get() = myState.items.toList()

    fun add(item: PromptItem) {
        myState.items.add(item)
    }

    fun find(id: String): PromptItem? = myState.items.find { it.id == id }

    fun update(id: String, block: (PromptItem) -> Unit) {
        val item = find(id) ?: return
        block(item)
        item.updateTime = System.currentTimeMillis()
    }

    fun remove(id: String) {
        myState.items.removeAll { it.id == id }
    }

    fun clear() {
        myState.items.clear()
    }

    companion object {
        fun getInstance(): PromptStorage =
            ApplicationManager.getApplication().getService(PromptStorage::class.java)
    }
}

package com.dmc.prompt

import java.util.UUID

data class PromptItem(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var category: String = "通用",
    var content: String = "",
    var createTime: Long = System.currentTimeMillis(),
    var updateTime: Long = System.currentTimeMillis(),
    var isTop: Boolean = false,
    var tags: List<String> = emptyList(),
)

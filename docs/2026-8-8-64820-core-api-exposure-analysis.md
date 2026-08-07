# 核心能力暴露可行性分析

## 问题

能否一次性暴露所有核心能力，使后续功能迭代完全在 `custom/` 内完成，不再修改上游源码？

## 结论

**可以，且当前已基本实现。** 后端 API 全部 public，custom 模块已依赖 `:backend` + `:frontend`。唯一缺口是前端"立即发送 prompt"能力，补 1 个方法即可完全覆盖。

---

## 能力矩阵：custom 已能访问什么

### 后端（`:backend` — 零改动，全部 public）

custom 模块通过 `KiloBackendAppService` 直接访问所有后端能力：

| 能力 | API | 调用方式 |
|---|---|---|
| 发送 prompt | `app.chat.prompt(id, dir, dto)` | ✅ 已在 DmcBridgeService 使用 |
| 创建会话 | `app.sessions.create(dir)` | ✅ 可用 |
| 列出会话 | `app.sessions.list(dir)` / `recent(dir, n)` | ✅ 可用 |
| 读取消息历史 | `app.chat.messages(id, dir)` | ✅ 可用 |
| 订阅事件流 | `app.chat.events: SharedFlow<ChatEventDto>` | ✅ 可用 |
| 中止生成 | `app.chat.abort(id, dir)` | ✅ 可用 |
| 压缩上下文 | `app.chat.compact(id, dir, model)` | ✅ 可用 |
| 撤销/重做 | `app.chat.revert(id, dir, msg, part)` / `unrevert()` | ✅ 可用 |
| 命令执行 | `app.chat.command(id, dir, cmd, args, dto)` | ✅ 可用 |
| 权限回复 | `app.chat.replyPermission(reqId, dir, reply)` | ✅ 可用 |
| 问题回复 | `app.chat.replyQuestion(reqId, dir, answers)` | ✅ 可用 |
| 增强提示词 | `app.chat.enhancePrompt(dir, text)` | ✅ 可用 (suspend) |
| 连接状态 | `app.appState: StateFlow<KiloAppState>` | ✅ 可用 |
| 应用配置 | `app.config: ConfigDto?` | ✅ 可用 |
| 模型列表 | `app.models` | ✅ 可用 |
| 用户信息 | `app.profile` | ✅ 可用 |
| 端口/HTTP | `app.port` / `app.http` / `app.api` | ✅ 可用 |

**结论：后端 API 面已 100% 可用，无需任何改动。**

### 前端（`:frontend` — 已有 1 处 custom_change）

custom 模块已依赖 `:frontend`，通过 `SessionManager.KEY` DataKey 访问：

| 能力 | API | 状态 |
|---|---|---|
| 获取活跃会话 ID | `manager.activeSessionId()` | ✅ `custom_change` 已加 |
| 新建会话 | `manager.newSession()` | ✅ 接口原有 |
| 打开历史 | `manager.showHistory()` | ✅ 接口原有 |
| 打开指定会话 | `manager.openSession(ref)` | ✅ 接口原有 |
| 聚焦输入框 | `manager.focusPrompt()` | ✅ 接口原有 |
| 获取工作区 | `SessionManager.WORKSPACE_KEY` | ✅ DataKey 原有 |

### 共享层（`:shared` — 零改动）

所有 DTO 和 RPC 接口定义在 shared 模块，custom 可直接引用：

- `PromptDto`、`PromptPartDto`、`PartSourceDto`、`PartSourceTextDto`
- `ChatEventDto` 及所有子类（`MessageUpdated`、`PartDelta`、`TurnOpen` 等）
- `SessionDto`、`SessionListDto`、`MessageWithPartsDto`
- `PermissionReplyDto`、`QuestionReplyDto`、`ConfigUpdateDto`

### IntelliJ Platform（零改动）

| 能力 | API |
|---|---|
| 编辑器选区 | `CommonDataKeys.EDITOR` + `editor.selectionModel` |
| 当前文件 | `CommonDataKeys.VIRTUAL_FILE` / `PSI_FILE` |
| 编译错误 | `ProjectTaskManager` / `DaemonCodeAnalyzer` |
| 控制台输出 | `ToolWindowManager.getToolWindow("Run")` |
| Problems 面板 | `ToolWindowManager.getToolWindow("Problems")` |

---

## 缺口分析

### 缺口 1：前端即时 prompt（可选）

**现状**：custom 通过后端 `app.chat.prompt()` 发送，CLI 处理后 SSE 事件回流更新 UI。

**问题**：
- 如果用户没有打开会话（`activeSessionId()` 返回 null），需要手动 `app.sessions.create(dir)` 再发送
- SSE 回流有极短延迟（本地 HTTP，毫秒级）

**前端 API `SessionController.prompt(text, parts)`** 会：
- 懒创建会话（首次调用时自动创建）
- 立即更新 UI 模型（不等 SSE）
- 处理 agent/model/variant 选择
- 管理重试/离线状态

但 `SessionController` 是 `SessionUi` 的 private 字段，custom 无法直接调用。

**方案**：在 `SessionManager` 接口加 1 个方法：

```kotlin
// SessionManager.kt
fun sendPrompt(text: String, parts: List<PromptPartDto> = emptyList()): Boolean = false
```

```kotlin
// SessionSidePanelManager.kt
override fun sendPrompt(text: String, parts: List<PromptPartDto>): Boolean {
    val ui = current ?: return false
    ui.prompt(text, parts)  // 需要在 SessionUi 加 1 个方法
    return true
}
```

```kotlin
// SessionUi.kt
fun prompt(text: String, parts: List<PromptPartDto> = emptyList()) {
    controller.prompt(text, parts)
}
```

**代价**：3 个上游文件，各加 1 行。加入 PROTECTED_FILES 后 sync 自动处理。

### 缺口 2：无（后端已全覆盖）

不存在其他缺口。所有聊天、会话、模型、配置、权限功能均通过后端 API 可用。

---

## 成本-收益对比

### 方案 A：当前状态（activeSessionId 已有）

| 维度 | 评估 |
|---|---|
| 上游改动 | 2 文件 × 1 行 |
| custom 能力覆盖率 | ~95%（所有后端功能可用） |
| 边界 case | 需手动处理"无活跃会话"场景 |
| 后续迭代 | 零上游改动 |

### 方案 B：加 sendPrompt（推荐）

| 维度 | 评估 |
|---|---|
| 上游改动 | 3 文件 × 1 行（新增 SessionUi.kt） |
| custom 能力覆盖率 | 100% |
| 边界 case | 全部消除（懒创建会话、即时 UI 更新） |
| 后续迭代 | 零上游改动 |

### 方案 C：暴露 SessionController（不推荐）

| 维度 | 评估 |
|---|---|
| 上游改动 | 需将 SessionUi.controller 改为 public（破坏封装） |
| 风险 | 上游重构 controller 时冲突率高 |
| 收益 | 与方案 B 相同，但维护成本更高 |

---

## 建议：方案 B

新增 1 个文件（`SessionUi.kt`）到 PROTECTED_FILES，加 1 个 `prompt()` 方法。总上游改动：

| 文件 | 改动 | 标记 |
|---|---|---|
| `SessionManager.kt:27` | `activeSessionId()` | ✅ 已有 |
| `SessionSidePanelManager.kt:112` | `activeSessionId()` impl | ✅ 已有 |
| `SessionManager.kt` | `sendPrompt(text, parts)` | 🆕 加 1 行 |
| `SessionSidePanelManager.kt` | `sendPrompt()` impl | 🆕 加 1 行 |
| `SessionUi.kt` | `prompt()` 转发方法 | 🆕 加 1 行 |

完成后，custom 模块的 `DmcBridgeService` 可以简化为：

```kotlin
// 不再需要 DmcSessionResolver + KiloBackendChatManager 两步走
// 直接一行调用，前端处理一切
val manager = SessionManager.KEY.getData(ctx)
manager?.sendPrompt(text, parts)
```

后续所有功能（发送选区、发送文件、发送编译错误、自动修复等）完全在 `custom/` 内实现，零上游改动。

## Sync 影响评估

3 个受保护文件各只改 1 行，冲突概率极低：

| 文件 | 行数 | 上游修改频率 | 冲突风险 |
|---|---|---|---|
| SessionManager.kt | 30 行 | 极低（接口稳定） | 几乎为零 |
| SessionSidePanelManager.kt | 246 行 | 中等（UI 功能迭代） | 低（我们的行在固定位置） |
| SessionUi.kt | ~900 行 | 中等 | 低 |

每次 sync 的额外成本：`fix-markers` 自动重建标记，`scan-markers` 校验。人工仅在 3-way merge 报冲突时介入。

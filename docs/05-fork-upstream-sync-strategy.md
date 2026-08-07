# 私有 Fork + 上游同步策略

基于 kilo-jetbrains 插件二次开发，封装自己的插件，同时实时与上游新功能合并。不考虑官方提 PR，不考虑开源。

## 核心策略：隔离 + 标记 + 定期合并

```
你的仓库
├── main 分支  ←── 你的自定义功能在此
└── upstream remote ←── 官方 kilocode 仓库

定期: git fetch upstream → merge upstream/main → 解决冲突
```

关键原则只有一条：**新增文件优先，修改上游文件最小化，修改处必须标记。**

---

## 一、Git 仓库与分支策略

### 初始设置

```bash
# 添加官方为 upstream
git remote add upstream https://github.com/Kilo-Org/kilocode.git

# 创建定制分支（永远不要在 main 上直接开发）
git checkout -b custom main
```

### 上游同步工作流

```bash
# 1. 获取上游最新代码
git fetch upstream

# 2. 在干净的 custom 分支上合并
git checkout custom
git merge upstream/main

# 3. 解决冲突（标记会让这步很快）

# 4. 验证构建
cd packages/kilo-jetbrains && ./gradlew typecheck && ./gradlew test

# 5. 合并回 main
git checkout main && git merge custom
```

**频率**：建议每 1-2 周同步一次。间隔越长冲突越大。

---

## 二、代码隔离原则（减少冲突的核心）

### 原则 1：新增 Gradle 模块，不改现有三模块

最有效的隔离手段——添加第 4 个模块存放全部定制代码：

```
packages/kilo-jetbrains/
├── shared/          ← 上游，尽量不动
├── frontend/        ← 上游，尽量不动
├── backend/         ← 上游，尽量不动
├── custom/          ← 全部定制代码（新建）
│   └── src/main/kotlin/com/yourcompany/custom/
│       ├── editor/          # 编辑器选区/文件读取
│       ├── build/           # 编译错误收集
│       ├── mcp/             # MCP server
│       └── bridge/          # 会话桥接
└── build-tasks/     ← 上游，不动
```

好处：
- `custom/` 目录在官方仓库中**不存在**，merge 时永远不会冲突
- 定制代码完全自包含，可独立编译测试
- 合并时只有极少数 wiring 文件可能冲突

### 原则 2：必须修改上游文件时，用标记

参考 Kilo 自身的 `kilocode_change` 模式，定义自己的标记：

```kotlin
// 单行修改
val port = connection.port // custom_change

// 多行修改
// custom_change start
fun yourCustomBridge() { ... }
// custom_change end

// XML
<!-- custom_change -->
<action id="YourCustomAction" class="com.yourcompany.custom.YourAction"/>
<!-- /custom_change -->
```

合并冲突时，搜索 `custom_change` 即可快速定位所有改动。

### 原则 3：冲突高发文件清单（尽量绕开）

| 文件 | 冲突概率 | 绕开策略 |
|---|---|---|
| `plugin.xml` | **极高** | 只加 `<content>` 行，用单独的 `custom.xml` 模块描述符 |
| `build.gradle.kts`（root） | **高** | 只加一行 `include(":custom")` |
| `kilo.jetbrains.frontend.xml` | **高** | action 注册在 `custom.xml` 中，不编辑此文件 |
| `gradle.properties` | **高** | 用单独的 properties 文件 |
| `package.json` | **中** | 只改 version 字段 |
| `SessionController.kt` | **高** | **不要直接改**，用反射或扩展函数 |
| `KiloBackendChatManager.kt` | **中** | **不要改**，在 custom 模块里自己发 HTTP |

---

## 三、必须修改的文件（插件身份变更）

这些文件**必须改**，且每次合并要重新应用（冲突不可避免，提前接受）：

```
packages/kilo-jetbrains/
├── build.gradle.kts          # pluginConfiguration { id = "com.yourcompany.kilo" }
├── plugin.xml                 # <name>、<vendor>、<description>
├── gradle.properties          # kilo.jetbrains.version
└── package.json               # name、version
```

**关键**：改插件 ID 避免与官方插件冲突——两个不同 ID 的插件可以共存：

```kotlin
// build.gradle.kts
pluginConfiguration {
    id = "com.yourcompany.kilo"  // 不是 ai.kilocode.jetbrains
    name = "Your Kilo"
    vendor = url("https://yourcompany.com") name "YourCompany"
}
```

把这些改动集中管理，合并时优先处理这几个文件。

---

## 四、在 custom 模块中接入现有功能

定制模块（`custom/`）需要访问插件内部的 session 发送能力。有两种方式：

### 方式 A：直接发 HTTP（推荐，零侵入）

`custom/` 模块自己持有一份 HTTP 客户端，直接调 CLI：

```kotlin
// custom/src/.../bridge/CustomSessionBridge.kt
class CustomSessionBridge(val port: Int, val password: String) {
    fun sendPrompt(sessionId: String, dir: String, text: String, parts: List<PromptPartDto>) {
        // POST http://127.0.0.1:$port/session/$sessionId/prompt_async
        // 复用 shared 模块的 PromptDto / PromptPartDto
    }
}
```

获取 port + password：

```kotlin
// custom 模块通过 IntelliJ service 获取（同进程）
val app = ApplicationManager.getApplication().getService(KiloBackendAppService::class.java)
val port = app.port   // public getter，可以读到
```

> 注意：`port` 是 public getter，但 `password` 是 private。需要通过反射拿 password，或用一个极小的 `custom_change` 在 backend 模块暴露它。这是**唯一需要修改上游源码**的地方。

### 方式 B：用极小的 custom_change 暴露一个桥接接口

在 `backend` 模块加**一个文件**（不改现有文件），声明一个公开接口：

```kotlin
// backend/src/.../CustomBridge.kt（新建文件，不编辑任何现有文件）
interface CustomBridge {
    val port: Int
    val password: String
    fun chat(): KiloBackendChatManager
}
```

然后在 `KiloBackendAppService` 中加**一行**实现：

```kotlin
// KiloBackendAppService.kt — custom_change start
class CustomBridgeImpl(...) : CustomBridge { ... }
// custom_change end
```

最小侵入——只在上游文件加一个标记区域，合并时只看这一个位置。

---

## 五、CLI 版本管理策略

```
你的插件构建时 ──下载──> 官方 GitHub Release 的 CLI 二进制
运行时 ──spawn──> kilo serve（官方 CLI）
```

**推荐：直接用官方 CLI Release**，不维护自己的 CLI fork。

- `gradle.properties` 中 `kilo.cli.pinned=true`（默认）
- `package.json` version 对齐想用的官方 CLI 版本
- 插件只是个不同的「客户端壳」，CLI 完全用官方的
- 上游 CLI 的 bug fix、新模型、新工具自动获得

只有当需要修改 CLI 行为时才需要 fork CLI——但需求（错误收集、选区、会话桥接）都在插件侧，不需要动 CLI。

---

## 六、custom 模块的 Gradle 配置

```kotlin
// custom/build.gradle.kts（新建文件）
plugins { id("org.jetbrains.kotlin.jvm") }

dependencies {
    implementation(project(":shared"))    // 复用 DTO
    implementation(project(":backend"))   // 访问 backend 服务（如需要）
    implementation(project(":frontend"))  // 访问 frontend 服务（如需要）
    implementation(libs.kotlinx.coroutines)
    // 你的第三方依赖...
}

// 模块描述符
// custom/src/main/resources/custom.xml（新建文件）
<idea-plugin>
    <dependencies>
        <dependency>ai.kilocode.jetbrains</dependency>  <!-- 依赖主插件 -->
    </dependencies>
    <content>
        <module name="ai.kilocode.custom"/>
    </content>
    <extensions defaultExtensionNs="com.intellij">
        <applicationService serviceImplementation="com.yourcompany.custom.YourService"/>
    </extensions>
    <actions>
        <action id="YourCustom.SendSelection" class="com.yourcompany.custom.SendSelectionAction">
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
        </action>
    </actions>
</idea-plugin>
```

```kotlin
// 根 build.gradle.kts — custom_change
include(":shared", ":frontend", ":backend", ":custom")  // 加 :custom
```

```xml
<!-- plugin.xml — custom_change -->
<content>
    <module name="ai.kilocode.jetbrains.shared"/>
    <module name="ai.kilocode.jetbrains.frontend"/>
    <module name="ai.kilocode.jetbrains.backend"/>
    <module name="ai.kilocode.custom"/>  <!-- 加这一行 -->
</content>
```

合并时，这三处是**确定性的改动**（永远只加一行），冲突解决只需 30 秒。

---

## 七、合并冲突解决速查

```
git merge upstream/main
```

按优先级处理冲突：

| 步骤 | 文件 | 操作 |
|---|---|---|
| 1 | `custom/` 目录 | **无冲突**（上游没有此目录） |
| 2 | `plugin.xml` | 保留 `<content>` 中 custom 模块行 + 接受上游其他改动 |
| 3 | `build.gradle.kts`（root） | 保留 `include(":custom")` + 接受上游改动 |
| 4 | `build.gradle.kts` pluginConfiguration | 保留 plugin ID/name/vendor |
| 5 | `gradle.properties` | 保留 version，或对齐上游 |
| 6 | `package.json` | 保留 name，version 对齐 CLI |
| 7 | 搜索 `custom_change` | 检查所有标记点是否完好 |
| 8 | `git mergetool`（如安装了 mergiraf） | 结构化解决 Kotlin/XML 冲突 |

---

## 八、总结决策表

| 决策 | 推荐选择 |
|---|---|
| Fork 范围 | 整个 monorepo（最简单，merge 最顺） |
| 定制代码位置 | 新建 `custom/` Gradle 模块 |
| 上游文件改动 | 标记（`custom_change`），集中在 ≤5 个文件 |
| 插件 ID | 改为自己的（`com.yourcompany.kilo`） |
| CLI 版本 | 直接用官方 Release（`pinned=true`） |
| 上游同步频率 | 每 1-2 周 |
| 冲突工具 | `git merge` + `mergiraf`（已配置 `zdiff3`） |
| 会话桥接 | custom 模块直发 HTTP + 最小 custom_change 暴露 password |

这套方案让你享受上游全部新功能（新 agent、新模型、新工具、bug fix），同时定制代码零冲突或秒级冲突。

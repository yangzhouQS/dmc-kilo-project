# 独立插件能否与 kilocode 插件通信

## 结论：无法直接通信

独立插件**无法**直接向 kilocode 会话窗口发送内容。三个层面的障碍都是封闭的。

---

## 障碍一：零扩展点，无法调插件内部 API

| 检查项 | 结果 |
|---|---|
| `<extensionPoints>` 声明 | **0 个**（四个 XML 描述符文件全部无） |
| `SessionController` 可访问性 | 普通 `final class`，非 Service，无 `getInstance()`，外部拿不到实例 |
| 服务构造函数 | 全部 `internal` / `private`，跨 classloader 不可调用 |
| `<depends>` 依赖声明 | 无，无导出 API JAR，split-mode 三套独立 classloader |
| `@ApiStatus` 稳定性标记 | 无 |

### 详细证据

**插件 ID**: `ai.kilocode.jetbrains`（`build.gradle.kts:187`）

**服务可见性**（关键类）：

| 类 | 位置 | 外部可访问？ | 原因 |
|---|---|---|---|
| `SessionController` | `frontend/.../session/controller/SessionController.kt:85` | **否** | 普通 final class，非 Service，无 getInstance()，由 `SessionUi` 内部创建 |
| `KiloSessionService` | `frontend/.../app/KiloSessionService.kt:48` | **理论可，实际不可** | `@Service(PROJECT)` 但构造函数 `internal` |
| `KiloBackendChatManager` | `backend/.../app/KiloBackendChatManager.kt:46` | **否** | 非 Service，`private constructor`，文档注释明确写"Not an IntelliJ service" |
| `KiloBackendAppService` | `backend/.../app/KiloBackendAppService.kt` | **否** | `private constructor`，仅 `internal create()` 工厂 |

**Classloader 隔离**：split-mode 插件，三个模块各自独立 classloader：

| 模块 | Classloader | 内容 |
|---|---|---|
| `kilo.jetbrains.shared` | 独立 | RPC 接口 + DTO（fleet.rpc） |
| `kilo.jetbrains.frontend` | 独立 | UI、SessionController、设置 |
| `kilo.jetbrains.backend` | 独立 | CLI 进程管理、HTTP、RPC 实现 |

外部插件的类无法解析这些 classloader 中的类。

---

## 障碍二：端口 + 密码仅在内存，无法发现

| 信息 | 存储位置 | 是否可外部发现 |
|---|---|---|
| 端口 | `KiloConnectionService.port`（内存字段，`--port 0` 随机） | **否** |
| 密码 | `KiloConnectionService.password`（内存，随机 64 位 hex） | **否** |
| 磁盘文件 | 无 lock/PID/state 文件 | **否** |
| `daemon.json` | 仅 `kilo daemon` 写入，**插件用的是 `serve`，不写** | **不适用** |
| 子进程环境变量 | `KILO_SERVER_PASSWORD` 仅在 CLI 进程内存中 | 仅 OS 级 `/proc` 可读（Linux），Windows 不可 |

### 关键代码位置

- 密码生成：`KiloBackendCliManager.kt:333-337` → `generatePassword()`，32 随机 hex 字节
- 端口捕获：`KiloBackendCliManager.kt:546-550` → 从 stdout 正则匹配
- 密码传递：仅通过 `KILO_SERVER_PASSWORD` 子进程环境变量（`buildKiloCliEnv`, `KiloBackendCliManager.kt:597`）
- 字段存储：`KiloBackendConnectionService.kt:112-114` → `port`（public read）/ `password`（private）
- 持久化：**无**——不写入 `PersistentStateComponent`、`PropertiesComponent`、任何文件

---

## 障碍三：API 全量鉴权，无法绕过

密码设置后（JetBrains 总是设置），**所有功能端点都需要 Basic Auth**。

### 端点鉴权矩阵

| 端点 | 需要鉴权？ | 来源 |
|---|---|---|
| `GET /global/event` (SSE) | **是**（RootHttpApi + Authorization 中间件） | `api.ts:81`, `groups/global.ts:88` |
| `GET /global/health` | **是** | `api.ts:81`, `handlers/global.ts:82` |
| `POST /session/{id}/prompt_async`（及所有 `/session/*`） | **是**（`authorizationRouterMiddleware`） | `authorization.ts:111-126` |
| `GET /site.webmanifest`、manifest PNG | **否**（装饰性，渲染图标用） | `public-ui.ts:4-11` |
| `GET /pty/connect?pty-connect-ticket=...` | **否**（单次票据） | `authorization.ts:145-160` |
| 其他所有端点 | **是** | `authorization.ts:120-122` |

**无任何无鉴权的功能端点。** 无法绕过密码调用 prompt API。

### 鉴权实现

- `packages/opencode/src/server/auth.ts:24-26` — `required()` 检查密码是否设置
- Basic Auth: `Authorization: Basic <base64>` header 或 `?auth_token=<base64>` query param
- username 默认 `"kilo"`（`auth.ts:19,40`）

---

## 可行的替代路径

按「不改官方源码」的约束，按现实可行性排序：

### 路径 A：MCP 伴生插件（推荐）

**方向反转**：不是「你的插件推送内容给会话」，而是「agent 主动调你的 MCP 工具拉取 IDE 状态」。

```
你的插件(MCP HTTP Server)  ←CLI连接←  kilo serve
     ↓ 读取
  IntelliJ API（错误/选区/文件）
```

- 用户在 kilocode 设置里添加你的 MCP server URL（**已有 UI**，`McpConfigurable.kt`）
- agent 自动发现 `get_build_errors` / `get_editor_selection` 等工具
- **零修改官方插件**，但有触发方式差异（agent 拉取 vs 用户推送）

### 路径 B：共享文件 + CLI 内置 read 工具（最简单）

```
你的插件 Action  ──写入──>  /tmp/kilo-context.md（错误摘要+选区代码）
                                    ↑
                          agent 用 CLI 自带的 read 工具读取
```

用户工作流：点你的 Action 收集错误 → 在对话里说「读取 /tmp/kilo-context.md 并修复」。

- **零修改官方插件**，零 MCP
- 缺点：多一步手动指令，体验不够顺滑

### 路径 C：在官方插件加一个扩展点（一次性修改）

如果能接受**一次性修改官方插件源码**（维护 fork），只需加一个 extension point + 一个公开 service：

```kotlin
// shared 模块新增公开接口
interface KiloExternalBridge {
    fun sendToSession(text: String, files: List<PromptPartDto>)
}
```

注册为 application service，外部插件 `<depends>ai.kilocode.jetbrains</depends>` 后直接调用。这是最干净的长期方案，但需要维护 fork。

### 路径 D：OS 级进程嗅探（不推荐）

- 找到 `kilo serve` 进程，Linux 读 `/proc/<pid>/environ` 拿密码
- 然后直连 CLI HTTP API 发送 prompt
- **极度脆弱**：跨平台不一致、进程生命周期不确定、密码获取不可靠

---

## 决策矩阵

| 路径 | 改官方源码 | 发送方向 | 体验 | 复杂度 | 可靠性 |
|---|---|---|---|---|---|
| A: MCP 伴生插件 | 否 | agent 拉取 | 中（需引导 agent） | 中 | 高 |
| B: 共享文件 | 否 | agent 读取 | 低（手动指令） | 低 | 高 |
| C: 加扩展点 | **一次性** | 用户推送 | 高 | 低 | 高 |
| D: 进程嗅探 | 否 | 用户推送 | 高 | 高 | **极低** |

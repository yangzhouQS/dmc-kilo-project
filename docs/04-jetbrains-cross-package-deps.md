# kilo-jetbrains 与其他子包的依赖关系

## 总览

| 依赖的子包 | 类型 | 生产发布需要？ | 说明 |
|---|---|---|---|
| `packages/ui/` | 构建时 | **是**（所有构建） | 复制 provider 图标 SVG，无开关 |
| `packages/opencode/` | 构建时 | **否**（仅 dev） | 仅 `pinned=false` 时读取本地源码 |
| 其他所有子包 | — | **否** | 无任何依赖 |

**运行时**：不依赖任何子包——插件下载 GitHub Release 的 CLI 二进制，通过 HTTP 通信。

---

## 依赖一：`packages/ui/` — 唯一的生产构建依赖

`frontend/build.gradle.kts:35-62`：

```kotlin
val providerIcons = tasks.register<Copy>("generateProviderIcons") {
    val src = layout.projectDirectory.dir("../../ui/src/assets/icons/provider")
    // 复制 120 个 SVG，替换 currentColor → #6E6E6E
}
tasks.processResources { dependsOn(providerIcons, providerIconsDark) }
```

- 路径解析：`frontend/../../ui/` → `packages/ui/`
- 读取**源码文件**（`src/assets/icons/provider/*.svg`），不是构建产物，所以不需要先 build `ui` 包
- **无任何 flag 开关**，pinned 和 repo 模式都执行
- 缺失该目录 → Gradle 构建失败

这是唯一一个**无条件**的跨包依赖。

---

## 依赖二：`packages/opencode/` — 仅开发模式

`backend/build.gradle.kts:28` 声明路径：

```kotlin
val repoRootDir = rootProject.layout.projectDirectory.dir("../opencode")
```

三个用途，**全部被 `kilo.cli.pinned` flag 控制**：

| 用途 | pinned=true（生产） | pinned=false（开发） |
|---|---|---|
| OpenAPI spec 生成 | 下载 GitHub Release 的 CLI，运行 `kilo generate` | 本地 `bun run ./src/index.ts generate` |
| CLI 二进制打包 | 不读取 | `buildRepoCli` 编译 + `stageRepoCli` 打包 |
| 读取 `packages/opencode/` 源码 | **否** | **是** |

### pinned 模式开关

`gradle.properties:6` 默认 `kilo.cli.pinned=true`，生产发布强制检查：

```kotlin
// build.gradle.kts:94-96
if (release && !pinned) error("kilo.cli.pinned=false is a dev-only mode and cannot be released...")
```

### CLI 获取方式对比

| 模式 | pinned | CLI 来源 | 是否读 opencode 源码 |
|---|---|---|---|
| 标准生产 | true | GitHub Release 下载 | 否 |
| Bundled 生产 | true + bundled | 打包到 plugin zip | 否 |
| 开发 | false | 本地 `packages/opencode/dist/` | 是 |

### OpenAPI spec 生成路径

生产模式（`GenerateOpenApiSpecTask.kt:120-148`）：
1. 下载 CLI v$version from GitHub
2. 验证 SHA256
3. 运行 `<downloaded-kilo> generate`
4. 生成 Kotlin API client

开发模式（`GenerateOpenApiSpecTask.kt:76-88`）：
1. 运行 `bun run --conditions=browser ./src/index.ts generate`（在 `packages/opencode/`）
2. 同样的输出

**结论**：生产构建完全不碰 `packages/opencode/`。

---

## 依赖三：Turbo 构建编排（保守约束）

`turbo.json:51-54`：

```json
"@kilocode/kilo-jetbrains#build": {
  "dependsOn": ["@kilocode/cli#build"],
  "outputs": ["build/distributions/**"]
}
```

`bun turbo build` 会先构建 CLI 再构建 JetBrains 插件。但在 pinned 模式下 Gradle 忽略 `opencode/dist/`，所以这是一个**保守的安全网**，实际不消费产物。

`typecheck` 和 `test:ci` 任务**不依赖** CLI 构建。

---

## 内部 Gradle 模块（非跨包依赖）

这些是 `packages/kilo-jetbrains` 内部的模块间依赖，非跨包：

| 依赖关系 | 文件 | 行 |
|---|---|---|
| root → `:shared`, `:frontend`, `:backend` | `build.gradle.kts` | 175-177 |
| `:backend` → `:shared` | `backend/build.gradle.kts` | 208 |
| `:frontend` → `:shared` | `frontend/build.gradle.kts` | 21 |

Composite build `build-tasks`：`settings.gradle.kts:8` → `includeBuild("build-tasks")`，完全自包含。

---

## 不依赖的子包

以下子包与 kilo-jetbrains **完全无关**：

| 子包 | 关系 |
|---|---|
| `packages/kilo-vscode/` | 无 |
| `packages/sdk/js/` | 无（插件用自己生成的 OpenAPI Kotlin client，不用 TS SDK） |
| `packages/kilo-gateway/` | 无 |
| `packages/kilo-telemetry/` | 无 |
| `packages/kilo-i18n/` | 无 |
| `packages/util/` | 无 |
| `packages/plugin/` | 无 |
| `packages/kilo-docs/` | 无 |

---

## 完整依赖矩阵

| 依赖 | 包 | 类型 | 生产需要？ | 文件 & 行 |
|---|---|---|---|---|
| Provider 图标 | `packages/ui/` | 构建时 | **是**（所有构建） | `frontend/build.gradle.kts:36,46,60-62` |
| OpenAPI spec（dev） | `packages/opencode/` | 构建时，仅 dev | 否 | `backend/build.gradle.kts:28,63-75` |
| OpenAPI spec（prod） | GitHub Release（外部） | 构建时 | 是（网络） | `GenerateOpenApiSpecTask.kt:120-148` |
| Repo CLI 二进制 | `packages/opencode/dist/` | 构建时，仅 dev | 否 | `backend/build.gradle.kts:77-81,99-105` |
| Bundled CLI 资源 | GitHub Release（外部） | 构建时（可选） | 仅 `-Pkilo.cli.bundled=true` | `backend/build.gradle.kts:107-116` |
| CLI 校验和 | GitHub Release（外部） | 构建时 | 是（网络） | `backend/build.gradle.kts:118-126` |
| Turbo CLI 构建排序 | `packages/opencode/` | 构建编排 | Turbo 强制，但 pinned 不消费 | `turbo.json:51-54` |
| CLI pin 版本 | `package.json:11` | 元数据 | 是（元数据） | `backend/build.gradle.kts:37-40` |
| 内部模块 | `:shared`, `:frontend`, `:backend` | 构建时 | 是（内部） | `build.gradle.kts:175-177` |
| 运行时 CLI 二进制 | GitHub Release（外部） | **运行时** | 是（除非 bundled） | 运行时下载 + spawn |

---

## 对二次开发的意义

kilo-jetbrains 插件高度自包含——运行时只通过 HTTP 与一个外部 CLI 二进制通信，不加载任何 monorepo 代码。这意味着：

1. **无法通过共享 monorepo 子包来打通两个插件**——没有共享的 JAR/模块
2. 插件之间的 classloader 完全隔离（split-mode 三套独立 classloader + 你的插件第四套）
3. 唯一的物理联系是它们运行在**同一个 IntelliJ 进程**中，但无 API 桥接
4. 如果 fork 整个 monorepo，只需要保留 `packages/ui/`（构建图标）和 `packages/kilo-jetbrains/`；`packages/opencode/` 在 pinned 模式下不需要

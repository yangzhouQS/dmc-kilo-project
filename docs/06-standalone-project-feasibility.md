# 独立二次开发项目可行性分析

## 目标

基于 `dmc-kilo-project` 作为独立项目目录，定期同步上游 kilo-jetbrains 官方源码并合并，确保可以正常打包，本地新增功能不受影响，只同步需要的上游包和插件。

---

## 一、结论：完全可行

kilo-jetbrains 是一个**高度自包含**的包。在 pinned 模式下（默认），它只需要：

| 需要的东西 | 来源 | 大小 |
|---|---|---|
| `packages/kilo-jetbrains/` 完整目录 | 上游同步 | ~几 MB |
| `packages/ui/src/assets/icons/provider/` (120 个 SVG) | 上游同步 | 352 KB |
| Java 21 | 本地安装 | — |
| Gradle 9.4.1 | wrapper 自动下载 | — |
| 网络（首次冷构建） | GitHub Release CLI + Maven | — |

**不需要**：bun、node、turbo、`packages/opencode/`、`packages/kilo-vscode/`、`packages/sdk/`、`packages/kilo-ui/` 或任何其他子包。

---

## 二、构建链关键发现

### 1. 唯一硬依赖：Provider 图标

`frontend/build.gradle.kts:36,46` 硬编码了图标路径：

```kotlin
val src = layout.projectDirectory.dir("../../ui/src/assets/icons/provider")
```

路径解析：`frontend/../../ui/` → `packages/ui/src/assets/icons/provider/`

- `processResources` 强依赖此任务，**路径不对则构建失败**
- 只需要 `*.svg` 文件（light + dark 由 Gradle 自动生成）
- 120 个文件，352 KB

### 2. `packages/opencode/` 仅 dev 模式

`backend/build.gradle.kts:28`：

```kotlin
val repoRootDir = rootProject.layout.projectDirectory.dir("../opencode")
```

这是**惰性属性**（lazy），只在 `kilo.cli.pinned=false` 时才解析到文件系统。pinned 模式下（默认 `true`）**永远不会访问**，路径不存在也不报错。

### 3. 版本解析无需 git tag

`build.gradle.kts:86-92` 版本解析优先级：

1. `-Pkilo.version=x`（命令行覆盖）
2. `kilo.jetbrains.version` from `gradle.properties`（**已有 = 7.0.14**）
3. git tag `jetbrains/v*`
4. `0.0.0-dev`（兜底）

因为 `gradle.properties` 已设 `kilo.jetbrains.version=7.0.14`，即使没有 git tag，版本也能正确解析。

### 4. CLI 版本来自 package.json

`backend/build.gradle.kts:37-40` 从 `package.json` 的 `version` 字段（`7.4.20`）读取，用于下载 GitHub Release 的 CLI 二进制。不依赖本地 `packages/opencode/`。

### 5. 构建工具需求矩阵

| 工具 | pinned 模式（生产） | repo 模式（dev） |
|---|---|---|
| Java 21 | **必须** | **必须** |
| Gradle 9.4.1 | wrapper 自动下载 | 同左 |
| Git | 需要（版本解析兜底） | 需要 |
| Bun | **不需要** | 需要（spec 生成 + CLI 编译） |
| Node | **不需要** | **不需要** |
| Turbo | **不需要** | **不需要** |

---

## 三、推荐项目结构：Sparse Clone 方案

### 为什么选 sparse clone

| 方案 | 优点 | 缺点 |
|---|---|---|
| **Sparse Clone（推荐）** | 保留 monorepo 目录结构，gradle 路径零修改，`git merge` 原生支持 | .git 含上游完整历史（可缓解） |
| 文件复制 + 手动 diff | .git 干净 | 合并痛苦，路径不匹配 |
| Vendored 图标 | 完全独立 | 每次 sync 要重新 vendor，gradle 要改 |

Sparse Clone 保持 `packages/kilo-jetbrains/` 和 `packages/ui/` 的相对位置不变，所有 gradle 路径引用**零修改即生效**。

### 目录结构

```
dmc-kilo-project/                         ← git 仓库
├── packages/
│   ├── kilo-jetbrains/                   ← 上游同步（完整插件）
│   │   ├── shared/
│   │   ├── frontend/
│   │   ├── backend/
│   │   ├── build-tasks/
│   │   ├── gradle/
│   │   ├── build.gradle.kts
│   │   ├── settings.gradle.kts
│   │   ├── gradle.properties             ← kilo.cli.pinned=true（默认）
│   │   ├── package.json                  ← CLI 版本 7.4.20
│   │   ├── gradlew / gradlew.bat
│   │   └── ...
│   │
│   └── ui/                               ← 仅图标目录（sparse checkout）
│       └── src/assets/icons/provider/    ← 120 个 SVG
│
├── custom/                                ← 你的定制代码（上游不存在，永不冲突）
│   └── ...
│
└── docs/
    └── ...
```

### Sparse checkout 配置

只检出两个路径到磁盘：

```
# .git/info/sparse-checkout 内容
/packages/kilo-jetbrains/
/packages/ui/src/assets/icons/provider/
```

---

## 四、初始化步骤（PowerShell）

### 步骤 1：创建 sparse clone

```powershell
# 目标目录已存在 docs，先备份
$dmc = "H:\2026code\demo\doc-kilocode\source-kilocode-2026-05-05\dmc-kilo-project"

# 用 sparse clone 方式克隆（仅下载需要的路径）
cd "H:\2026code\demo\doc-kilocode\source-kilocode-2026-05-05"
git clone --no-checkout --filter=blob:none --sparse https://github.com/Kilo-Org/kilocode.git dmc-kilo-project-tmp

# 配置 sparse checkout
cd dmc-kilo-project-tmp
git sparse-checkout set packages/kilo-jetbrains packages/ui/src/assets/icons/provider

# 检出
git checkout main

# 添加你的自定义分支
git checkout -b custom
```

> 注意：如果上游仓库访问受限，可以用本地已有仓库作为 remote：
> ```powershell
> git clone --no-checkout --sparse "H:\2026code\demo\doc-kilocode\source-kilocode-2026-05-05" dmc-kilo-project-tmp
> ```

### 步骤 2：合并已有 docs

```powershell
# 把之前的 docs 复制回来
Copy-Item -Path "$dmc\docs\*" -Destination "dmc-kilo-project-tmp\docs\" -Recurse -Force
```

### 步骤 3：验证构建

```powershell
cd dmc-kilo-project-tmp\packages\kilo-jetbrains

# typecheck（首次冷构建会下载 CLI，需要网络）
.\gradlew typecheck

# 完整构建
.\gradlew buildPlugin
```

构建产物在 `packages/kilo-jetbrains/build/distributions/`。

---

## 五、上游同步工作流

### 定期同步（每 1-2 周）

```powershell
cd dmc-kilo-project-tmp

# 1. 拉取上游最新（sparse checkout 只拉指定路径）
git fetch origin main

# 2. 合并到 custom 分支
git checkout custom
git merge origin/main

# 3. 解决冲突（如有）
#    - custom/ 目录：上游不存在，零冲突
#    - packages/kilo-jetbrains/：只有你标记的 custom_change 处可能冲突
#    - gradle.properties / plugin ID 文件：确定性冲突，30 秒解决

# 4. 验证构建
cd packages\kilo-jetbrains
.\gradlew typecheck

# 5. 提交合并
git add -A
git commit -m "sync upstream"
```

### 同步范围确认

sparse checkout 限制磁盘文件，但 `git fetch/merge` 操作的是完整 git 历史。`merge` 时只有 sparse checkout 内的文件会产生工作区变更，其他路径自动跳过。

**如果上游修改了 `packages/ui/src/assets/icons/provider/` 之外的 ui 文件**：不影响——sparse checkout 不会检出那些文件，merge 时只更新 sparse 路径内的变更。

---

## 六、插件身份变更（必须修改的文件）

改为自己的插件 ID，避免与官方插件冲突：

```kotlin
// packages/kilo-jetbrains/build.gradle.kts — pluginConfiguration
pluginConfiguration {
    id = "com.yourcompany.dmc"      // 不是 ai.kilocode.jetbrains
    name = "DMC Kilo"
    vendor = url("https://yourcompany.com") name "YourCompany"
}
```

```xml
<!-- src/main/resources/plugin.xml -->
<name>DMC Kilo</name>
<vendor email="..." url="...">YourCompany</vendor>
```

每次 sync 上游后，这几个文件的冲突是确定性的——保留你的 ID/name/vendor，接受上游其他改动。

---

## 七、定制代码放置（零冲突区域）

所有定制功能放在上游不存在的路径中：

```
packages/kilo-jetbrains/
├── custom/                               ← 新增 Gradle 模块
│   ├── build.gradle.kts                  ← 新建，不编辑上游文件
│   ├── src/main/resources/custom.xml     ← 新建模块描述符
│   └── src/main/kotlin/com/yourcompany/
│       ├── editor/                        # 编辑器选区/文件读取
│       ├── build/                         # 编译错误收集
│       ├── bridge/                        # 会话桥接
│       └── ...
│
├── shared/                               ← 上游，不动
├── frontend/                             ← 上游，不动
├── backend/                              ← 上游，不动
└── build-tasks/                          ← 上游，不动
```

仅需在上游文件加 3 处确定性标记（详见 [05-fork-upstream-sync-strategy.md](./05-fork-upstream-sync-strategy.md)）：

| 文件 | 改动 | 冲突解决 |
|---|---|---|
| `build.gradle.kts`（root） | `include(":custom")` | 加一行 |
| `plugin.xml` `<content>` | `<module name="yourcompany.custom"/>` | 加一行 |
| `KiloBackendAppService.kt` | 暴露 password 的桥接接口（如需） | 标记区域 |

---

## 八、潜在问题与缓解

| 问题 | 影响 | 缓解 |
|---|---|---|
| `.git` 较大（含 monorepo 完整历史） | 磁盘占用 | `--filter=blob:none` 只拉 tree 不拉 blob；首次 clone 约 50-100 MB |
| 首次冷构建需下载 CLI（网络） | 构建时间 | 缓存在 `build/cli-cache/`，后续增量构建跳过 |
| 上游频繁改 `plugin.xml` | 合并冲突 | 只加一行 `<module>`，解决时 30 秒 |
| 上游改图标路径 | 构建失败 | 极少见；sparse checkout 跟随上游变更自动同步 |
| 上游加新 Gradle 模块 | 需要调整 sparse 路径 | 加一行到 sparse-checkout 配置 |

---

## 九、完整决策表

| 决策 | 推荐选择 |
|---|---|
| 项目结构 | Sparse clone（保留 monorepo 相对路径） |
| 同步方式 | `git fetch origin main` + `git merge` |
| 磁盘范围 | `packages/kilo-jetbrains/` + `packages/ui/.../provider/` |
| 构建方式 | `./gradlew buildPlugin`（无需 bun/turbo） |
| CLI 版本 | 官方 Release（`pinned=true`，自动下载） |
| 定制代码 | `packages/kilo-jetbrains/custom/` 新模块 |
| 上游文件改动 | ≤3 处 `custom_change` 标记 |
| 插件 ID | 改为自己的（`com.yourcompany.dmc`） |
| 同步频率 | 每 1-2 周 |

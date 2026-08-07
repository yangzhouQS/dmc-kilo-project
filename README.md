# DMC Kilo Project

基于 kilo-jetbrains 插件的二次开发项目，定期同步上游官方源码。

## 快速开始

```powershell
# 1. 初始化（从本地 monorepo 复制源码 + git init）
.\scripts\init-project.ps1

# 2. 应用定制修改（插件 ID、custom 模块注册）
.\scripts\apply-custom-changes.ps1

# 3. 验证构建
.\scripts\build-plugin.ps1
```

## 日常操作

| 操作 | 命令 |
|---|---|
| 同步上游 | `.\scripts\sync-upstream.ps1` |
| 预览同步（不修改文件） | `.\scripts\sync-upstream.ps1 -DryRun` |
| 构建插件 | `.\scripts\build-plugin.ps1` |
| 类型检查 | `.\scripts\build-plugin.ps1 -Task typecheck` |
| 运行测试 | `.\scripts\build-plugin.ps1 -Task test` |

## 项目结构

```
dmc-kilo-project/
├── packages/
│   ├── kilo-jetbrains/              ← 上游同步（pinned 模式构建）
│   │   ├── shared/                   ← 上游模块，不动
│   │   ├── frontend/                 ← 上游模块，不动
│   │   ├── backend/                  ← 上游模块，不动
│   │   ├── build-tasks/              ← 上游模块，不动
│   │   ├── custom/                   ← DMC 定制模块（上游不存在，零冲突）
│   │   │   ├── build.gradle.kts
│   │   │   └── src/main/kotlin/com/dmc/
│   │   │       ├── bridge/            # 会话桥接（发送到 Kilo 对话）
│   │   │       └── actions/           # 右键菜单 Action
│   │   ├── build.gradle.kts          ← custom_change: 插件 ID
│   │   ├── settings.gradle.kts       ← custom_change: include :custom
│   │   ├── src/main/resources/
│   │   │   └── plugin.xml            ← custom_change: 名称/vendor/custom 模块
│   │   └── gradle.properties         ← custom_change: 版本
│   │
│   └── ui/src/assets/icons/provider/ ← 上游同步（120 个 SVG 图标）
│
├── scripts/
│   ├── init-project.ps1              ← 初始化项目
│   ├── sync-upstream.ps1             ← 同步上游
│   ├── apply-custom-changes.ps1      ← 应用定制修改
│   └── build-plugin.ps1              ← 构建插件
│
├── docs/                              ← 技术调研文档
├── .upstream-sync                     ← 上游同步点（git commit hash）
├── .gitignore
└── README.md
```

## 上游同步机制

### 保护文件 vs 安全文件

| 类型 | 文件 | 同步行为 |
|---|---|---|
| **安全文件** | shared/, frontend/, backend/, build-tasks/ 等所有非定制文件 | 自动覆盖 |
| **保护文件** | build.gradle.kts, plugin.xml, gradle.properties, package.json | 显示 diff，手动合并 |
| **定制文件** | custom/ 目录 | 永不触碰 |

### 冲突解决

保护文件的冲突是确定性的——保留 `custom_change` 标记区域的定制，接受上游其他改动。

```powershell
# 搜索所有定制标记
Select-String -Path "packages\kilo-jetbrains\**\*.*" -Pattern "custom_change" -Recurse
```

## 构建

- **构建工具**: Gradle 9.4.1 (wrapper 自动下载)
- **JDK**: Java 21
- **CLI**: pinned 模式，从 GitHub Release 自动下载（无需 bun/node/turbo）
- **产物**: `packages/kilo-jetbrains/build/distributions/*.zip`

## CLI 版本

`packages/kilo-jetbrains/package.json` 中的 `version` 字段决定下载哪个 CLI Release。
对齐到你想用的官方 CLI 版本即可，无需 fork CLI。

## 技术调研

详见 `docs/` 目录下的分析文档。


## 本地环境搭建

### gradle 下载地址
https://mirrors.cloud.tencent.com/gradle/



### 启动项目指令


``` 
模式
命令
说明
单进程（推荐开发用）
.\gradlew.bat runIde
所有模块加载在一个进程，最简单
分离模式
.\gradlew.bat runIdeSplitMode
backend + frontend 分进程，模拟远程开发
仅后端
.\gradlew.bat runIdeBackend
调试 CLI 进程管理
仅前端
.\gradlew.bat runIdeFrontend
调试 UI

```
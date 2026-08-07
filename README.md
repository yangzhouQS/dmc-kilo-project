# DMC Kilo Project

基于 kilo-jetbrains 插件的二次开发项目，定期同步上游官方源码。

## 快速开始

```bash
cd scripts && npm install    # 首次安装依赖

# 1. 初始化（从本地 monorepo 复制源码 + git init）
npx tsx src/cli.ts init

# 2. 应用定制修改（插件 ID、custom 模块注册）
npx tsx src/cli.ts apply

# 3. 验证标记完整性
npx tsx src/cli.ts scan-markers

# 4. 验证构建
npx tsx src/cli.ts build
```

## 日常操作

> 以下命令均在 `scripts/` 目录下执行。

| 操作 | 命令 |
|---|---|
| 同步上游（三方合并） | `npx tsx src/cli.ts sync` |
| 预览同步（不修改文件） | `npx tsx src/cli.ts sync --dry-run` |
| 重建 custom_change 标记 | `npx tsx src/cli.ts fix-markers --all` |
| 查找漂移文件 | `npx tsx src/cli.ts reset-candidates --dry-run` |
| 重置漂移文件到上游 | `npx tsx src/cli.ts reset-candidates` |
| 扫描 custom_change 标记 | `npx tsx src/cli.ts scan-markers` |
| 构建插件 | `npx tsx src/cli.ts build` |
| 类型检查 | `npx tsx src/cli.ts build typecheck` |
| 运行测试 | `npx tsx src/cli.ts build test` |

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
│   ├── package.json
│   ├── tsconfig.json
│   ├── src/
│   │   ├── cli.ts                   ← 统一 CLI 入口
│   │   ├── commands/
│   │   │   ├── init.ts              ← 初始化项目
│   │   │   ├── apply.ts             ← 应用定制修改
│   │   │   ├── sync.ts              ← 同步上游（git merge-file 三方合并）
│   │   │   ├── fix-markers.ts       ← 重建 custom_change 标记
│   │   │   ├── reset-candidates.ts  ← 漂移分类 + 自动重置
│   │   │   └── scan-markers.ts      ← 扫描标记
│   │   └── lib/
│   │       ├── paths.ts             ← 路径常量 + 保护文件列表
│   │       ├── git.ts               ← git 操作封装
│   │       ├── merge.ts             ← git merge-file 三方合并
│   │       ├── marker-dsl.ts        ← 标记剥离/对比/重新标注
│   │       ├── markers.ts           ← custom_change 标记扫描
│   │       ├── drift.ts             ← 漂移分类逻辑
│   │       ├── files.ts             ← 文件 I/O
│   │       └── colors.ts            ← ANSI 着色
│   ├── node_modules/                ← gitignore
│   └── dist/                        ← gitignore
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
| **保护文件** | settings.gradle.kts, build.gradle.kts, plugin.xml, gradle.properties, package.json | `git merge-file` 三方合并，冲突时写入冲突标记 |
| **定制文件** | custom/ 目录 | 永不触碰 |

### 同步后工作流

```bash
npx tsx src/cli.ts sync              # 三方合并（安全文件覆盖，保护文件 merge-file）
npx tsx src/cli.ts fix-markers --all # 重建受保护文件的 custom_change 标记
npx tsx src/cli.ts reset-candidates  # 清理漂移文件（markers-only/cosmetic/small-diff 自动重置）
npx tsx src/cli.ts scan-markers      # 校验标记完整性
```

## 构建

- **构建工具**: Gradle 9.4.1 (wrapper 自动下载)
- **JDK**: Java 21
- **CLI**: pinned 模式，从 GitHub Release 自动下载（Gradle 构建不需要 bun/turbo）
- **脚本工具链**: Node.js + tsx（仅 `scripts/` 目录，不影响 Gradle 构建）
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
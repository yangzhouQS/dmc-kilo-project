# DMC Kilo Project — 操作指南

> 所有命令在项目根目录执行：`H:\2026code\demo\doc-kilocode\source-kilocode-2026-05-05\dmc-kilo-project`

---

## 一、环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21（必须） | 不接受 17 或 8 |
| Gradle | 9.4.1（wrapper 自动下载） | 不手动升级 |
| Node.js | ≥ 18（推荐 22） | 用于 CLI 工具和 npm scripts |
| 首次冷构建 | 需要网络 | 下载 IntelliJ SDK ~1.4 GiB + CLI Release |

---

## 二、构建命令

所有命令通过 `npm run` 执行，内部封装了 Gradle 和 CLI 工具调用。

### 2.1 构建与打包

```bash
# 打包插件 ZIP（产物: packages/kilo-jetbrains/build/distributions/*.zip）
npm run build

# 类型检查（编译全部 Kotlin 源码）
npm run typecheck

# 运行测试
npm run test

# 清理构建产物
npm run clean

# 准备沙箱（部署插件到 IDE sandbox）
npm run sandbox
```

### 2.2 运行与调试

```bash
# 启动 IDE 沙箱（Monolithic 模式，--no-daemon 省内存）
npm run run

# Split 模式启动（Backend + Frontend 分进程）
npm run run:split
```

### 2.3 快速开发流程

```bash
# 改完代码后一键部署+启动
npm run sandbox && npm run run

# 清理后冷构建（解决缓存问题）
npm run clean && npm run build
```

---

## 三、上游同步

```bash
# 同步上游变更（交互式，自动处理 custom_change 标记）
npm run sync

# 同步到指定上游 tag
npm run sync:tag -- jetbrains/v7.0.14

# 应用 DMC 定制到上游文件
npm run apply

# 查看同步点
cat .upstream-sync
```

### 同步前检查

```bash
# 扫描验证 custom_change 标记完整性
npm run scan

# 修复标记（对比上游同步点重建）
npm run fix-markers

# 查找与上游差异极小的文件（可安全重置）
npm run reset-candidates
```

---

## 四、安装插件

### 4.1 从 ZIP 安装

```bash
# 1. 打包
npm run build

# 2. 在 IDE 中安装
#    File → Settings → Plugins → ⚙ → Install Plugin from Disk
#    → 选择 packages/kilo-jetbrains/build/distributions/*.zip
```

### 4.2 沙箱开发

```bash
# 直接在沙箱 IDE 中运行调试
npm run run
```

---

## 五、Git 常用操作

```bash
# 查看改动（排除无关文件）
git status -u -- packages/kilo-jetbrains/

# 验证：除受限文件外无上游文件被修改
git diff --name-only HEAD | Where-Object { $_ -notmatch 'custom/' -and $_ -notmatch '(settings\.gradle|plugin\.xml|build\.gradle|gradle\.properties|package\.json|AGENTS\.md|\.kilo/|docs/|scripts/)' }

# 提交前检查 custom_change 标记
npm run scan
```

---

## 六、完整命令速查表

| 命令 | 说明 | 目录 |
|---|---|---|
| `npm run build` | 打包插件 ZIP | 根目录 |
| `npm run typecheck` | 类型检查 | 根目录 |
| `npm run test` | 运行测试 | 根目录 |
| `npm run clean` | 清理构建 | 根目录 |
| `npm run sandbox` | 部署沙箱 | 根目录 |
| `npm run run` | 启动 IDE 沙箱 | 根目录 |
| `npm run run:split` | Split 模式启动 | 根目录 |
| `npm run sync` | 同步上游 | 根目录 |
| `npm run sync:tag -- <tag>` | 同步指定 tag | 根目录 |
| `npm run apply` | 应用定制 | 根目录 |
| `npm run scan` | 扫描标记 | 根目录 |
| `npm run fix-markers` | 修复标记 | 根目录 |
| `npm run reset-candidates` | 查找可重置文件 | 根目录 |
| `npm run cli:help` | CLI 工具帮助 | 根目录 |

---

## 七、注意事项

1. **JDK 21 必须** — Gradle 构建会直接报错如果版本不对
2. **`kilo.cli.pinned=true`** — 默认值，运行时从 GitHub Release 下载 CLI，不要改
3. **内存不足** — IDE 沙箱 OOM 时先杀残留 Java 进程，堆内存配置在 `.intellijPlatform/ides/IU-2026.1/bin/idea64.exe.vmoptions`
4. **插件不能与原始 Kilo Code 共存** — 模块名冲突，使用前需卸载原始插件
5. **首次冷构建** — 需要网络下载 SDK 和 CLI，后续增量构建不需要



## 本地同步tag

```
# 1. 扫描标记
npm run scan

# 2. 同步上游代码到 v7.0.15
npm run sync -- --tag jetbrains/v7.0.15

# 3. 同步后重新扫描标记
npm run scan

# 4. 更新插件版本号（7.0.13 → 7.0.15）
#    编辑 packages/kilo-jetbrains/gradle.properties:
#    kilo.jetbrains.version=7.0.15

# 5. 检查 CLI pin 是否需要更新
bun .kilo/skills/release-jetbrains/script/check-pin.ts

# 6. 编译验证
npm run typecheck

# 7. 如果编译通过，重新构建
npm run build
```


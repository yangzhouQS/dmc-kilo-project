# 上游同步原理

## 问题背景

DMC 项目从 kilocode monorepo 中提取 `packages/kilo-jetbrains/` 子集进行二次开发。需要定期将上游变更同步到本地，同时保留 DMC 定制修改。

核心矛盾：**DMC 项目与上游 monorepo 没有共享 git 历史**（首 commit 是文件导入），无法直接使用 `git merge`。

---

## 架构总览

```
kilocode monorepo (上游源码)
  │
  │  git diff <syncPoint> <targetTag>
  │  只看两个路径前缀:
  │    packages/kilo-jetbrains/**
  │    packages/ui/src/assets/icons/provider/**
  │
  ▼
┌─────────────────────────────────────────┐
│  文件分类引擎                            │
│                                         │
│  custom/**  ─────────────→ 跳过，永不触碰 │
│  5 个保护文件 ──────────→ git merge-file  │
│  其余变更文件 ──────────→ 直接覆盖拷贝     │
│  上游已删除文件 ────────→ 本地也删除       │
└─────────────────────────────────────────┘
```

---

## 文件三级分类

| 级别 | 文件 | 数量 | 同步行为 |
|---|---|---|---|
| **安全** | shared/, frontend/, backend/, build-tasks/ 下的所有非保护文件 | ~数千 | 直接覆盖（本应与上游一致） |
| **保护** | settings.gradle.kts, plugin.xml, build.gradle.kts, gradle.properties, package.json | 5 | `git merge-file` 三方合并 |
| **定制** | custom/ 目录 | 不定 | 完全跳过（上游不存在） |

---

## 三方合并机制（保护文件）

对每个保护文件，使用 `git merge-file` 执行单文件三方合并：

```
          base (上游 sync-point 版本)
         /              \
   ours (本地)        theirs (上游 target tag)
         \              /
          git merge-file
                │
         ┌──────┴──────┐
         ▼             ▼
    干净合并        冲突标记
    (exit 0)    (exit >0, <<<<<<<)
```

**为什么用 `git merge-file` 而非 `git merge`：**

- `git merge` 需要共享 git 历史（common ancestor），DMC 没有
- `git merge` 会尝试合并整个 monorepo 树（DMC 只有子集）
- `git merge-file` 对单个文件做三方 diff/merge，不需要仓库级历史
- 基础版本（base）从 monorepo 的 sync-point commit 读取，不需要 DMC 自己的 git 历史记录它

**合并策略：**

| 场景 | base | theirs | 结果 |
|---|---|---|---|
| 上游和本地都改了 | 有 | 有 | 三方合并，可能冲突 |
| 上游新增文件 | null | 有 | 直接取 theirs |
| 上游删除文件 | 有 | null | 删除本地文件 |
| 文件无变化 | 有 | 有 | 跳过 |

---

## 标记系统

### 标记格式

```
// 单行内联
val x = 1 // custom_change

// 多行块
// custom_change start
val x = 1
val y = 2
// custom_change end

// XML 内联
<name>DMC Kilo</name> <!-- custom_change -->

// XML 块
<!-- custom_change -->
<module name="com.dmc.kilo.custom"/>
<!-- /custom_change -->

// 新文件标记
// custom_change - new file
```

### 标记风格映射

| 扩展名 | 风格 | 内联标记 |
|---|---|---|
| `.kt`, `.kts` | slash | `// custom_change` |
| `.xml` | xml | `<!-- custom_change -->` |
| `.properties`, `.toml` | hash | `# custom_change` |
| `.json` | 不支持 | （JSON 无法添加注释） |

### 标记重建算法（fix-markers）

```
1. clean(file, localContent)
   → 剥离所有 custom_change 标记，得到纯代码 + 标记元数据

2. 读取上游 sync-point 版本

3. changed(upstreamClean, localClean)
   → git diff --no-index --unified=0
   → 解析 @@ hunks，收集变更行号

4. ranges(lineSet)
   → 连续行号合并为范围 [{start, end}]

5. annotate(file, clean, ranges)
   → 在变更范围重新插入标记
   → 单行：行尾追加内联标记
   → 多行：前后包裹块标记
```

用途：sync 合并后，自动重建标记确保格式一致。

---

## 漂移分类算法（reset-candidates）

同步后部分文件可能产生无意义的"漂移"（标记格式差异、空白变化）。分类算法判断每个文件是否可以安全重置：

```
对每个变更文件:
  1. 从 monorepo 读取上游版本（sync-point）
  2. 比较本地与上游:

     local === upstream                         → identical
     stripMarkers(local) === stripMarkers(up)   → markers-only    [自动重置]
     approxDiff(忽略空白) === 0                  → cosmetic-only   [自动重置]
     approxDiff ≤ reviewLimit                   → small-diff      [自动重置]
     approxDiff > reviewLimit                   → large-diff      [跳过]
```

**approxDiff（多集合差异）：** 对两段文本的每一行计数，差异 = 计数绝对值之和。行顺序变化不算差异（移动一行不算改了一行）。

---

## 版本追踪

`.upstream-sync` 文件格式：

```
bfb123760b0c9b84aecea05958fa3e6051b7ddb3 jetbrains/v7.0.12
```

- 第一字段：commit hash（必须）
- 第二字段：tag 名称（可选，sync --tag 时写入）
- 向后兼容：只有 commit hash 的旧格式仍可读取

---

## 与 kilocode 官方方案的差异

| 方面 | 官方 kilocode | DMC |
|---|---|---|
| 上游来源 | opencode（同构仓库） | kilocode monorepo（子集提取） |
| 合并方式 | `git merge`（有共享历史） | `git merge-file`（无共享历史） |
| 预变换 | 10 层 branding transform | 无需（DMC 无品牌差异） |
| 冲突解决 | mergiraf + rerere | `git merge-file` 原生 |
| 标记名 | `kilocode_change` | `custom_change` |
| 标记重建 | `fix-kilocode-markers.ts` | `fix-markers.ts`（同算法） |
| 漂移分类 | `find-reset-candidates.ts` | `reset-candidates.ts`（同算法） |
| 版本文件 | `.opencode-version`（tag） | `.upstream-sync`（commit + tag） |

DMC 的方案是 kilocode 的简化版：去掉了品牌变换和 git merge 复杂度，保留了标记重建和漂移分类两个核心能力。

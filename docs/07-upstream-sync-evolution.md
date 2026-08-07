# DMC 上游同步机制演进规划

> 基于 git merge-file 的三方合并 + 标记重建 + 漂移分类

## 背景

当前 DMC 同步使用文件拷贝方式，存在的问题：
- 受保护文件（5 个）只能显示 diff 让人工合并，无三方合并能力
- 无法自动重建 `custom_change` 标记
- 无法识别"漂移不大的文件"并自动重置

## 架构决策

### 为什么不用完整 `git merge`

DMC 项目与上游 monorepo **没有共享 git 历史**（首 commit 是文件导入）。完整 `git merge` 需要 `--allow-unrelated-histories`，且会把整个 monorepo 树引入，与 DMC 只含 `packages/kilo-jetbrains/` 子集的结构冲突。

### 选择 `git merge-file`

`git merge-file` 是 git 内置的**单文件三方合并**工具：
- Base = 上游 sync-point 版本
- Ours = 本地当前版本
- Theirs = 上游目标 tag 版本

对 5 个受保护文件逐个执行，得到带冲突标记的三方合并结果。安全文件继续直接覆盖（它们本就该与上游一致）。

---

## 迭代计划

### Iteration 1: 三方合并 sync（替换文件拷贝）

**新增文件：**
- `scripts/src/lib/merge.ts` — `git merge-file` 封装

**修改文件：**
- `scripts/src/lib/git.ts` — 新增 `showFile`、`blobSize`
- `scripts/src/commands/sync.ts` — 受保护文件改用三方合并

**核心逻辑 `merge.ts`：**

```typescript
// 三方合并单个文件
// 返回 { merged, conflicts }
// conflicts > 0 表示有冲突标记，需人工处理
export function threeWayMerge(
  ours: string,    // 本地当前内容
  base: string,    // 上游 sync-point 版本
  theirs: string,  // 上游目标 tag 版本
): { merged: string; conflicts: number }
```

使用 `spawnSync('git', ['merge-file', ours.tmp, base.tmp, theirs.tmp])`。
exit code 0 = 干净合并，>0 = 冲突数。

**sync 流程变更（受保护文件）：**

```
旧流程: 显示 diff → 人工编辑
新流程: 
  1. 从 monorepo 提取 base (sync-point) 和 theirs (target tag) 版本
  2. git merge-file ours base theirs
  3. conflicts=0 → 自动写入合并结果
  4. conflicts>0 → 写入带冲突标记的文件，提示人工解决
```

### Iteration 2: 标记重建（fix-markers）

**新增文件：**
- `scripts/src/lib/marker-dsl.ts` — 标记剥离/对比/重新标注
- `scripts/src/commands/fix-markers.ts` — 单文件标记重建

**核心算法（移植自 kilocode `markers.ts`，适配 `custom_change`）：**

```
1. clean(file, content)
   - 识别并剥离所有 custom_change 标记（inline/block/xml）
   - 返回 { text: 无标记的纯文本, marks: 标记元数据 }

2. changed(baseText, headText)
   - git diff --no-index base.tmp head.tmp
   - 解析 @@ hunks，收集变更行号集合
   - 返回 { lines: Set<number>, deleted: number }

3. ranges(lineSet)
   - 连续行号 → 范围 [{start, end}, ...]
   - 相邻范围合并

4. annotate(file, clean, ranges)
   - 在变更范围两侧插入标记
   - 单行: 行尾追加 ` // custom_change`
   - 多行: 前后包裹 `// custom_change start` ... `// custom_change end`
   - XML: `<!-- custom_change -->` ... `<!-- /custom_change -->`

5. fresh(file, clean)
   - 文件不存在于上游 → 添加 `// custom_change - new file`
```

**标记风格映射（DMC）：**

| 扩展名 | 风格 | 行内标记 | 块标记 |
|---|---|---|---|
| `.kt`, `.kts` | slash | `// custom_change` | `// custom_change start/end` |
| `.xml` | xml | `<!-- custom_change -->` | `<!-- custom_change -->/<!-- /custom_change -->` |
| `.properties`, `.toml` | hash | `# custom_change` | `# custom_change start/end` |
| `.json` | unsupported | 不支持注释，跳过 | — |

**使用方式：**
```bash
npx tsx src/cli.ts fix-markers packages/kilo-jetbrains/build.gradle.kts
npx tsx src/cli.ts fix-markers packages/kilo-jetbrains/build.gradle.kts --dry-run
```

### Iteration 3: 漂移分类（reset-candidates）

**新增文件：**
- `scripts/src/lib/drift.ts` — 漂移分类逻辑
- `scripts/src/commands/reset-candidates.ts` — 批量查找 + 自动重置

**分类算法（移植自 kilocode `reset.ts`）：**

```
对每个文件:
  1. 从 monorepo 读取上游版本（sync-point commit）
  2. 比较本地与上游:
     - local === upstream → identical
     - stripMarkers(local) === stripMarkers(upstream) → markers-only
     - approxDiff(stripMarkers(local), stripMarkers(upstream), ignoreWhitespace=true) === 0 → cosmetic-only
     - approxDiff ≤ reviewLimit → small-diff
     - approxDiff > reviewLimit → large-diff
     - upstream 不存在该文件 → upstream-missing
     - 二进制文件 → binary-identical / binary-diff
```

**approxDiff（多集合差异，纯 JS 无子进程）：**
```
对 base 和 head 的每一行计数
差异 = 所有行计数的绝对值之和
行顺序变化不算差异
```

**分类桶与动作：**

| 桶 | 含义 | 动作 |
|---|---|---|
| identical | 完全一致 | 无 |
| markers-only | 剥离标记后一致 | **自动重置** |
| cosmetic-only | 仅空白差异 | **自动重置** |
| small-diff | ≤ N 行差异 | **自动重置** |
| large-diff | > N 行差异 | 跳过（人工） |
| upstream-missing | 上游无此文件 | 跳过 |
| binary-* | 二进制 | 跳过 |

**使用方式：**
```bash
npx tsx src/cli.ts reset-candidates --dry-run           # 预览分类
npx tsx src/cli.ts reset-candidates --review-limit 3    # 更严格
npx tsx src/cli.ts reset-candidates                     # 执行自动重置
```

### Iteration 4: 集成与文档

**修改文件：**
- `scripts/src/cli.ts` — 新增 `fix-markers`、`reset-candidates` 子命令
- `scripts/src/commands/sync.ts` — sync 完成后自动提示运行 `fix-markers`
- `README.md` — 更新操作指南
- `AGENTS.md` — 更新同步机制说明

**新增 sync 后工作流：**
```bash
npx tsx src/cli.ts sync                 # 三方合并
npx tsx src/cli.ts fix-markers --all    # 重建受保护文件标记
npx tsx src/cli.ts reset-candidates     # 清理漂移文件
npx tsx src/cli.ts scan-markers         # 校验标记完整性
```

---

## 文件清单

| 文件 | 操作 | 迭代 |
|---|---|---|
| `scripts/src/lib/merge.ts` | 新增 | 1 |
| `scripts/src/lib/git.ts` | 修改（加 showFile/blobSize） | 1 |
| `scripts/src/commands/sync.ts` | 重写受保护文件处理 | 1 |
| `scripts/src/lib/marker-dsl.ts` | 新增 | 2 |
| `scripts/src/commands/fix-markers.ts` | 新增 | 2 |
| `scripts/src/lib/drift.ts` | 新增 | 3 |
| `scripts/src/commands/reset-candidates.ts` | 新增 | 3 |
| `scripts/src/cli.ts` | 修改（加子命令） | 4 |
| `README.md` | 修改 | 4 |
| `AGENTS.md` | 修改 | 4 |

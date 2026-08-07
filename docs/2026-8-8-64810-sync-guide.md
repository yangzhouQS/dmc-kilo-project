# 上游同步操作手册

## 前置条件

| 条件 | 说明 |
|---|---|
| Node.js ≥ 18 | scripts 工具链运行环境 |
| 依赖安装 | `cd scripts && npm install` |
| 本地 monorepo | kilocode 源码克隆（`upstream-local` remote 指向它） |
| `.upstream-sync` | 记录上次同步的 commit（`init` 自动创建） |

所有命令在 `scripts/` 目录下执行。

---

## 命令速查

| 命令 | 用途 |
|---|---|
| `npx tsx src/cli.ts sync -t <tag>` | 按 tag 同步上游 |
| `npx tsx src/cli.ts sync` | 按 monorepo HEAD 同步 |
| `npx tsx src/cli.ts sync --dry-run` | 预览同步，不修改文件 |
| `npx tsx src/cli.ts fix-markers --all` | 重建受保护文件的标记 |
| `npx tsx src/cli.ts reset-candidates --dry-run` | 查找漂移文件 |
| `npx tsx src/cli.ts reset-candidates` | 自动重置低漂移文件 |
| `npx tsx src/cli.ts scan-markers` | 校验标记完整性 |

---

## Tag 同步（推荐）

```bash
# 1. 在 monorepo 拉取最新 tag
cd /path/to/kilocode-monorepo
git fetch origin --tags

# 2. 回到 DMC 项目预览
cd scripts
npx tsx src/cli.ts sync -t jetbrains/v7.0.12 --dry-run

# 3. 正式同步
npx tsx src/cli.ts sync -t jetbrains/v7.0.12

# 4. 重建标记
npx tsx src/cli.ts fix-markers --all

# 5. 清理漂移
npx tsx src/cli.ts reset-candidates

# 6. 校验
npx tsx src/cli.ts scan-markers

# 7. 提交
cd ..
git add -A
git commit -m "sync upstream jetbrains/v7.0.12"
```

Tag 写法兼容三种格式，自动补全：

| 输入 | 解析为 |
|---|---|
| `jetbrains/v7.0.12` | `jetbrains/v7.0.12` |
| `v7.0.12` | `jetbrains/v7.0.12` |
| `7.0.12` | `jetbrains/v7.0.12` |

---

## HEAD 同步

不指定 `--tag` 时，同步 monorepo 当前 HEAD：

```bash
cd /path/to/kilocode-monorepo
git checkout main && git pull

cd /path/to/dmc-kilo-project/scripts
npx tsx src/cli.ts sync
```

---

## 冲突处理

同步时受保护文件（5 个）使用 `git merge-file` 三方合并。如果产生冲突：

```
[CONFLICT] build.gradle.kts — 2 conflict(s) need manual resolution
    Existing custom_change markers:
      [inline] line 187  id = "com.dmc.kilo" // custom_change
```

冲突标记（`<<<<<<<` / `=======` / `>>>>>>>`）已写入文件。处理步骤：

1. 在编辑器中打开冲突文件
2. 解决每个冲突区域，保留 DMC 定制 + 接受上游改动
3. 删除冲突标记
4. 重建标记：
   ```bash
   npx tsx src/cli.ts fix-markers --all
   ```
5. 校验：
   ```bash
   npx tsx src/cli.ts scan-markers
   ```

---

## 受保护文件清单

以下 5 个文件是唯一允许包含 `custom_change` 标记的文件：

| 文件 | 定制内容 |
|---|---|
| `settings.gradle.kts` | `include("custom")` |
| `src/main/resources/META-INF/plugin.xml` | custom 模块注册 + 插件名 |
| `build.gradle.kts` | 插件 ID |
| `gradle.properties` | 版本号 |
| `package.json` | name、version |

`custom/` 目录在上游不存在，同步时完全跳过，永不产生冲突。

---

## 漂移清理

每次同步后，部分文件可能因标记格式差异或空白变化产生"漂移"。`reset-candidates` 按漂移程度分类：

| 分类 | 含义 | 动作 |
|---|---|---|
| markers-only | 剥离标记后与上游一致 | 自动重置 |
| cosmetic-only | 仅空白差异 | 自动重置 |
| small-diff | ≤ 5 行差异（可调） | 自动重置 |
| large-diff | > 5 行差异 | 跳过，留人工 |
| identical | 完全一致 | 无 |
| upstream-missing | 上游无此文件 | 跳过 |

```bash
# 调整阈值
npx tsx src/cli.ts reset-candidates --review-limit 3 --dry-run
```

---

## 回滚

同步修改的是工作区文件，未提交前可随时回退：

```bash
git checkout -- .
# 或指定文件
git checkout -- packages/kilo-jetbrains/build.gradle.kts
```

同步点（`.upstream-sync`）在 sync 成功时更新。如需回退到旧同步点，手动编辑该文件。

---

## 首次初始化

```bash
cd scripts
npx tsx src/cli.ts init -m /path/to/kilocode-monorepo
npx tsx src/cli.ts apply --plugin-id com.dmc.kilo --plugin-name "DMC Kilo"
npx tsx src/cli.ts scan-markers
```

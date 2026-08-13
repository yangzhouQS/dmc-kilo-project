# Skill: Upstream Sync Guard — 同步前强制检查

## 触发条件
当用户要求执行 `npm run sync` 或 `npm run sync:tag` 或任何上游同步操作时，**必须**先完成本检查清单。

## 同步前强制检查清单

### 1. PROTECTED_FILES 完整性检查

所有含有 `custom_change` 标记的上游文件**必须**在 `scripts/src/lib/paths.ts` 的 `PROTECTED_FILES` 数组中注册。

**检查命令**：
```bash
# 扫描所有上游文件中的 custom_change 标记
rg -l "custom_change" packages/kilo-jetbrains/frontend packages/kilo-jetbrains/backend packages/kilo-jetbrains/shared packages/kilo-jetbrains/src packages/kilo-jetbrains/build.gradle.kts packages/kilo-jetbrains/settings.gradle.kts packages/kilo-jetbrains/gradle.properties packages/kilo-jetbrains/package.json --glob "!**/custom/**"
```

**对比**：每个文件路径（去掉 `packages/kilo-jetbrains/` 前缀）必须在 `PROTECTED_FILES` 中存在。

**如果不一致**：
- 停止同步
- 将缺失的文件添加到 `PROTECTED_FILES`
- 通知用户确认后再继续

### 2. custom_change 标记完整性检查

```bash
npm run scan
```

所有标记必须完整（无未闭合的 `start/end` 块，无损坏的 inline 标记）。

**如果有错误**：停止同步，先修复标记。

### 3. 上游仓库 Tag 状态检查

确认上游仓库（默认 `H:\2026code\demo\doc-kilocode\source-kilocode-2026-05-05\`）已 checkout 到目标 tag：

```bash
git -C <upstream-path> describe --tags
```

如果上游不在目标 tag：
```bash
cd <upstream-path>
git fetch origin
git checkout <target-tag>
```

### 4. 新目录预创建

如果上游有新增目录（同步脚本不自动创建目录），检查 git diff：

```bash
git -C <upstream-path> diff <old-tag>..<new-tag> --name-only --diff-filter=A | xargs -I{} dirname {} | sort -u
```

提前在 fork 中创建所有新目录：
```bash
# 示例：如果上游新增了 context/ 目录
mkdir -p packages/kilo-jetbrains/frontend/src/main/kotlin/ai/kilocode/client/session/context
```

## 同步操作流程

```
1. npm run scan                    ← 检查标记完整性
2. 检查 PROTECTED_FILES             ← 确认所有 custom_change 文件已注册
3. 检查上游仓库 tag                 ← 确认 checkout 到目标
4. 预创建新目录                     ← 避免 ENOENT 错误
5. npm run sync -- --tag <tag>     ← 执行同步
6. npm run scan                    ← 同步后再次检查标记
7. npm run typecheck               ← 编译验证
8. 如果编译失败 → 重新应用丢失的 custom_change
9. npm run build                   ← 打包验证
```

## 同步后验证

同步完成后，**必须**验证以下文件保留了 custom_change：

| 文件 | 预期标记数 |
|---|---|
| `settings.gradle.kts` | ≥1 |
| `plugin.xml` | ≥2 |
| `build.gradle.kts` | ≥2 |
| `gradle.properties` | ≥1（版本号） |
| `package.json` | ≥1 |
| `SessionManager.kt` | ≥4 |
| `SessionSidePanelManager.kt` | ≥2 |
| `SessionUi.kt` | ≥1 块 |
| `PromptPanel.kt` | ≥3 处 |

**验证命令**：
```bash
for f in settings.gradle.kts src/main/resources/META-INF/plugin.xml build.gradle.kts; do
  count=$(rg -c "custom_change" "packages/kilo-jetbrains/$f" 2>/dev/null || echo 0)
  echo "  $f: $count markers"
done
for f in frontend/src/main/kotlin/ai/kilocode/client/session/SessionManager.kt \
         frontend/src/main/kotlin/ai/kilocode/client/session/SessionSidePanelManager.kt \
         frontend/src/main/kotlin/ai/kilocode/client/session/SessionUi.kt \
         frontend/src/main/kotlin/ai/kilocode/client/session/ui/prompt/PromptPanel.kt; do
  count=$(rg -c "custom_change" "packages/kilo-jetbrains/$f" 2>/dev/null || echo 0)
  echo "  $f: $count markers"
done
```

## 当前 PROTECTED_FILES 清单

```
packages/kilo-jetbrains/ 前缀去掉后：
1. settings.gradle.kts
2. src/main/resources/META-INF/plugin.xml
3. build.gradle.kts
4. gradle.properties
5. package.json
6. frontend/.../SessionManager.kt
7. frontend/.../SessionSidePanelManager.kt
8. frontend/.../SessionUi.kt
9. frontend/.../ui/prompt/PromptPanel.kt
```

## 铁律

1. **每次给新的上游文件添加 `custom_change` 时，必须同步更新 `PROTECTED_FILES`**
2. **同步前必须运行 `npm run scan` 确认标记完好**
3. **同步后必须运行 `npm run typecheck` 确认编译通过**
4. **如果编译失败，优先检查 custom_change 是否被覆盖**

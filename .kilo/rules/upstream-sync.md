# 上游同步与合并冲突防护规则

本文件是 AI agent 在本项目中编辑代码时的强制约束。
违反任何一条都可能导致上游同步时产生难以解决的合并冲突。

## 规则 1：定制代码隔离

所有新增的 Kotlin/Java/XML 文件必须创建在 `packages/kilo-jetbrains/custom/` 目录下。

```
正确: packages/kilo-jetbrains/custom/src/main/kotlin/com/dmc/xxx.kt
错误: packages/kilo-jetbrains/frontend/src/main/kotlin/com/dmc/xxx.kt
错误: packages/kilo-jetbrains/backend/src/main/kotlin/ai/kilocode/xxx.kt
```

## 规则 2：上游文件保护

以下目录是上游代码，**只读**：

- `packages/kilo-jetbrains/shared/`
- `packages/kilo-jetbrains/frontend/`
- `packages/kilo-jetbrains/backend/`
- `packages/kilo-jetbrains/build-tasks/`
- `packages/kilo-jetbrains/script/`
- `packages/kilo-jetbrains/.run/`
- `packages/kilo-jetbrains/gradle/`
- `packages/ui/`

## 规则 3：受限文件修改

只有以下 5 个文件允许修改，且每处改动必须标记 `custom_change`：

| 文件 | 只允许的改动 |
|---|---|
| `settings.gradle.kts` | `include("custom")` |
| `src/main/resources/META-INF/plugin.xml` | custom 模块 `<module>` 行 + `<name>` |
| `build.gradle.kts` | `pluginConfiguration` ID |
| `gradle.properties` | 版本号 |
| `package.json` | name、version |

标记格式：
```kotlin
// 单行
val x = 1 // custom_change

// 多行
// custom_change start
val x = 1
val y = 2
// custom_change end
```

```xml
<!-- custom_change -->
<action id="DmcAction" .../>
<!-- /custom_change -->
```

## 规则 4：禁止的 Gradle 改动

- 不修改 `gradle/libs.versions.toml`（如需新依赖，在 `custom/build.gradle.kts` 中硬编码版本）
- 不修改 `build-tasks/` 复合构建
- 不修改 `settings.gradle.kts` 的 `pluginManagement` 块
- 不修改 `gradle/wrapper/gradle-wrapper.properties`（除非更换 Gradle 版本）
- 不添加新的 `includeBuild`

## 规则 5：同步前检查

在提交任何代码前，确认：

1. `git diff --stat` 中没有 `custom/` 之外的**新增**文件
2. `custom/` 之外的**修改**文件仅限上述 5 个受限文件
3. 搜索 `custom_change` 确认所有标记完好

```powershell
# 验证：除受限文件外无上游文件被修改
git diff --name-only | Where-Object { $_ -notmatch 'custom/' -and $_ -notmatch '(settings\.gradle|plugin\.xml|build\.gradle|gradle\.properties|package\.json|AGENTS\.md|\.kilo/|docs/|scripts/)' }
# 输出应为空
```

# PRD：Problems 面板错误「Kilo一键修复」功能需求文档

## 1\. 业务目标

在 IDE 原生**Problems（问题）面板** 的错误/警告条目上，新增自定义右键菜单【Kilo一键修复】。

用户点击后自动唤起 Kilo Code 插件对话窗口，自动填充标准化修复提示词，将当前代码错误、报错信息、代码片段一键投递至 AI 对话，辅助用户解释问题、自动修复代码。

**触发入口**：Problems 工具窗口 → 选中单条 Problem 条目 → 右键菜单 → Kilo一键修复

## 2\. 功能详细需求

### 2\.1 菜单显示规则

- **菜单位置**：Problems 工具窗口列表条目上下文右键菜单

- **菜单标识**：唯一 Action ID `kilo.problem.fix.action`，展示文本：**Kilo一键修复**

- **生效范围**：支持 TS/JS 编译错误、IDE 代码检查警告、外部工具（tsc/eslint）产生的所有 Problem 问题项

- **显示约束规则**：


    - 选中**单条有效错误/警告**：菜单可用、可点击

    - 未选中任何条目：菜单隐藏

    - 多选多条问题条目：菜单置灰禁用，仅支持单条处理

- **菜单排序**：挂载在原生菜单最底部，不抢占原生功能位置，无业务冲突

### 2\.2 核心执行流程

为保证所有编辑器兼容，采用**纯文本标准化流程**（无图表渲染报错）：

1. 用户在 Problems 面板右键选中单条问题条目，点击【Kilo一键修复】

2. 程序读取当前选中 Problem 对象，解析：错误码、错误描述、文件路径、代码偏移位置

3. 根据代码偏移，提取错误位置周边代码片段

4. 调用 `DmcSessionResolver` 获取当前 Kilo 活跃会话 ID

5. 会话状态判断：


    - 无活跃会话（SessionId 为空 / 停留在欢迎页）：自动新建 Kilo 会话，获取新会话 ID

    - 存在活跃会话：直接复用当前会话

6. 按照固定模板组装完整 AI 修复提示词

7. 自动激活并展开 Kilo Code 侧边工具窗口，切换至目标会话

8. 将组装好的提示词自动填充至对话输入框

9. **核心约定**：仅填充内容，**不自动发送消息**，由用户手动确认发送

### 2\.3 固定 Prompt 模板

统一固定模板，所有错误场景通用：

```Plain Text
请解释以下代码问题并进行修复

【文件路径】：{fileAbsolutePath}
【错误代码】：{errorCode}
【错误描述】：{errorMessage}
【出错代码片段】
```
{codeSnippet}
```
```

填充示例：

```Plain Text
请解释以下代码问题并进行修复

【文件路径】：H:\2026code\demo\test-demo.ts
【错误代码】：TS1389
【错误描述】：'var' is not allowed as a variable declaration name.
【出错代码片段】
```
const var=123;
```
```

### 2\.4 异常兜底逻辑

|异常场景|处理策略|
|---|---|
|无法获取 Problem 信息 / 文件已删除|弹出 IDE 气泡通知，提示「无法获取当前错误代码信息」，终止流程|
|Kilo 工具窗口初始化失败|弹出通知：Kilo Code 工具窗口打开失败，请重试|
|会话创建失败、无有效 SessionId|弹出通知：创建 Kilo 会话失败，请手动打开面板重试|
|代码片段读取为空|保留错误基础信息模板，空缺代码片段，正常填充输入框，不中断流程|

### 2\.5 UI 表现规范

- **正常流程**：自动展开 Kilo 侧边面板、切换对应会话、输入框预填完整修复提示词，等待用户手动发送

- **异常流程**：右下角 IDE 气泡友好提示，无弹窗崩溃、无后台报错炸日志

## 3\. 技术方案与扩展点

### 3\.1 核心扩展点

Problems 面板属于 IDE 原生 ToolWindow，使用工具窗口右键菜单扩展点：

扩展点：`com.intellij.toolWindow.contextMenu`

目标菜单组 ID：`ProblemsViewPopupMenu`

### 3\.2 plugin\.xml 注册配置

```Plain Text
<action id="kilo.problem.fix.action" class="xxx.KiloProblemFixAction" text="Kilo一键修复">
    <add-to-group group-id="ProblemsViewPopupMenu" anchor="last"/>
</action>
```

### 3\.3 核心数据获取方式

- 不使用普通编辑器数据 Key，采用 Problems 专属数据 Key：`ProblemsViewKeys.SELECTED_PROBLEM`

- 通过 Problem 对象可获取：错误级别、错误描述、所属文件、文本高亮偏移范围

- 通过文件路径 \+ 文本偏移，解析错误上下文代码片段

### 3\.4 现有能力复用

- 复用 `DmcSessionResolver.kt` 会话解析能力，获取活跃 SessionId

- 复用已有会话新建、窗口唤起、对话赋值能力

- 统一复用 IDE 通知、线程调度工具类

### 3\.5 线程约束

- UI 操作（唤起窗口、赋值输入框）必须在 **EDT 主线程** 执行（invokeLater）

- 文件读取、代码解析、会话查询可在后台线程执行

### 3\.6 核心代码结构

```Plain Text
class KiloProblemFixAction : AnAction() {
    override fun update(e: AnActionEvent) {
        // 控制菜单显隐与置灰
        val problem = e.getData(ProblemsViewKeys.SELECTED_PROBLEM)
        e.presentation.isEnabledAndVisible = problem != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        // 1. 获取选中的 Problem 错误对象
        // 2. 解析错误码、文案、文件、代码片段
        // 3. 获取/新建 Kilo 会话
        // 4. 组装标准化 Prompt
        // 5. 唤起窗口并填充输入框
    }
}
```

### 3\.7 项目依赖

需在 `build.gradle.kts` 引入 Problems 视图模块依赖，保证 `ProblemsViewKeys` 可正常引用。

## 4\. 测试用例清单

|测试场景|预期结果|
|---|---|
|单条 TS 编译错误（TS1389）右键触发|自动打开 Kilo 面板，输入框完整填充错误信息\+代码片段，等待手动发送|
|Kilo 面板打开、无活跃会话（欢迎页）|自动新建空白会话，再填充修复提示词|
|Kilo 面板未打开|自动唤起侧边面板、新建会话、填充内容|
|多选多条 Problem 错误|菜单置灰不可点击，防止批量异常|
|文件已删除、残留错误条目|弹出友好提示，流程终止，无崩溃报错|
|IDE 原生代码检查警告（非编译错误）|正常抓取警告信息，填充模板，功能完全兼容|

## 5\. 明确非需求范围

- ❌ 不会自动发送 AI 请求，仅填充输入框，需用户手动确认

- ❌ 不会自动修改本地代码文件，仅提供修复对话参考

- ❌ 本次迭代不实现编辑器悬浮 QuickFix 按钮，仅做 Problems 面板右键菜单

## 6\. 后续迭代规划（可选）

- 迭代1：新增编辑器 QuickFix 悬浮「Kilo一键修复」入口

- 迭代2：支持批量多选错误，汇总批量修复 Prompt

- 迭代3：增加配置页，支持用户自定义修复提示词模板

> （注：部分内容可能由 AI 生成）

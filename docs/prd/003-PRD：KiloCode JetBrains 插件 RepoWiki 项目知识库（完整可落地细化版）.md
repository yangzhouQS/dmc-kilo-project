# KiloCode JetBrains 插件 RepoWiki 项目知识库（完整可落地细化版）

# PRD：KiloCode JetBrains 插件 RepoWiki 项目知识库（完整可落地细化版）

## 文档说明

1. 完全对标 Qoder RepoWiki / KnowledgeCard / Memory 三层知识引擎，深度适配 JetBrains IDE 插件体系；

2. 拆分**产品需求、IDE 原生依赖能力、KiloCode CLI 依赖、分层技术实现、完整代码骨架、异常、测试、依赖清单**；

3. 所有实现逻辑、扩展点、线程模型、文件存储、交互入口全部明确，后端 / 插件开发可直接落地；

4. 区分：仅 IDE 插件本地能力、需要复用 CLI 能力、两者联动边界。

# 一、整体架构总览

## 1\.1 三层知识存储（本地 Git 可提交）

项目根目录固定路径，全 UTF\-8 无 BOM，Git 可追踪同步团队知识

```Plain Text
项目根目录/.kilocode/
├─ rules/                     # 已有：项目编码规则，Wiki生成自动读取
├─ repowiki/
│  ├─ wiki_plan.yaml          # Wiki生成全局配置（黑白名单、模板、引导文案）
│  ├─ zh/                     # 默认中文知识库
│  │  ├─ wiki/                # RepoWiki 结构化Markdown文档
│  │  ├─ knowledge_cards/     # 架构/规约/技术栈知识卡片
│  │  └─ memory/              # 项目级对话记忆（当前仓库专属）
│  └─ en/                     # 英文知识库（可选切换）
└─ global_memory/             # 全局个人记忆（存储IDE配置目录，不提交Git）
```

## 1\.2 核心模块分层（插件内部）

1. FileWatchService：监听文件变更、Git 提交，触发增量 Wiki 更新

2. WikiManager：全量 / 增量生成、解析 wiki\_plan\.yaml、读写本地 Markdown

3. KnowledgeCardService：与 Wiki 联动，自动生成三类知识卡片

4. MemoryManager：项目记忆 \+ 全局记忆读写、检索、`/remember` 命令处理

5. KnowledgeRetriever：会话前置检索服务，自动聚合 Wiki / 卡片 / 记忆注入 Prompt

6. ChatSlashCommandProvider：聊天框斜杠命令扩展 `/wiki /knowledge /remember`

7. WikiToolWindowFactory：侧边知识库 Tab 自定义 UI 面板

8. GitHelper：封装 JetBrains 原生 Git API，分支切换、文件变更检测

## 1\.3 依赖能力总表（IDE 原生 \+ KiloCode CLI）

### 1\.3\.1 JetBrains IDE 原生必须依赖能力

|依赖 IDE 能力|用途|对应 SDK 包 / 扩展点|
|---|---|---|
|VirtualFile 文件系统监听|监听源码保存、新增、删除，触发增量 Wiki 更新|`com.intellij.openapi.vfs`、`VirtualFileListener`|
|PsiIndex / PsiFile 代码语义索引|解析类、函数、接口、模块结构，生成 Wiki 架构文档|`com.intellij.psi`、`PsiManager`|
|Git API（GitRepositoryManager）|判断是否 Git 仓库、提交监听、分支切换、文件 diff 哈希比对|`com.intellij.openapi.vcs`、`git4idea`|
|ToolWindow 自定义侧边面板|新增「项目知识库」独立 Tab，展示 Wiki 列表、卡片、记忆管理|`com.intellij.toolWindow` 扩展点|
|Chat 输入框自定义斜杠命令|解析 `/wiki /knowledge /remember` 对话指令|`com.intellij.editor.actionSystem`、自定义输入框处理器|
|Balloon 通知系统|生成进度、文件过滤提示、异常告警|`com.intellij.notification`|
|ReadAction / WriteAction|后台安全读取 PSI / 文件、写入本地知识库|`com.intellij.openapi.application`|
|EDT 线程调度 invokeLater|所有 UI 渲染、弹窗、面板刷新强制主线程|Application\.invokeLater|
|ProjectView 右键扩展|右键文件 / 目录「刷新对应 Wiki 文档」|`com.intellij.projectView.popup`|
|Plugin 持久化配置|存储知识库语言、扫描阈值、文件黑白名单默认值|`com.intellij.openapi.options`|
|Markdown 预览组件|知识库 Tab 内直接预览 Wiki md 文件|`org.intellij.plugins.markdown` 内置组件|

### 1\.3\.2 KiloCode CLI 依赖能力（插件与 CLI 联动边界）

> 插件本体优先复用**插件内部 LLM/Orchestrator**；仅批量全量超大项目生成时调用 CLI 子进程加速
> 
> 

1. `kilocode wiki-generate` 子命令

    - 能力：全项目代码扫描、批量生成 RepoWiki\+KnowledgeCard；支持传入`wiki_plan.yaml`配置文件

    - 传参：项目根路径、配置文件路径、语言 zh/en、扫描黑白名单

    - 输出：直接写入`/.kilocode/repowiki`目录，插件无需二次转换

2. `kilocode wiki-update` 增量更新命令

    - 传入变更文件列表，仅刷新受影响 Wiki 页面与知识卡片

3. `kilocode knowledge-modify`

    - 接收修改指令、参考文件路径，局部编辑 / 重写 Wiki / 知识卡片

4. CLI 统一配置打通：

    - CLI 读取项目`.kilocode`配置，与 JetBrains 插件共享同一套 API Key、模型、代理、项目规则

5. 调用约束：

    - 小型项目（文件 \< 2000）：纯插件内存内生成，不唤起 CLI；

    - 大型项目（文件 \> 2000）：后台异步拉起 CLI 子进程，避免 IDE 卡顿；

    - 会话实时检索、斜杠轻量编辑：全部插件内部逻辑，不依赖 CLI。

### 1\.3\.3 已有插件内部可复用模块（无需新增开发）

1. `DmcSessionResolver.kt`：获取当前活跃会话 ID、新建会话、激活 ToolWindow

2. 文件过滤工具：二进制检测、500KB 大文件过滤、黑名单目录过滤（node\_modules/\.git/dist 等）

3. IDE 通知工具类、EDT 线程封装工具

4. LLM Orchestrator：内部推理入口，小型 Wiki 生成直接复用，不走 CLI

5. `.kilocode/rules` 规则读取服务，自动注入 Wiki 生成 Prompt

# 二、模块 1：RepoWiki 结构化文档（完整细化实现）

## 2\.1 wiki\_plan\.yaml 配置解析实现

### 2\.1\.1 文件读取逻辑

1. 项目打开时，`WikiManager` 自动检测 `项目根/.kilocode/repowiki/wiki_plan.yaml`

2. 文件不存在：生成默认模板写入本地，提供基础黑白名单与架构模板

3. 解析依赖：引入 `snakeyaml` 轻量解析，插件内置依赖，无需用户额外安装

4. 配置热更新：监听该 yaml 文件变更，修改后重新加载配置，下次生成 Wiki 生效

### 2\.1\.2 扫描范围过滤实现

1. scope\.include/scope\.exclude 支持标准\.gitignore 通配符语法

2. 过滤规则执行顺序：exclude \> include

3. 内置强制黑名单（不可删除，仅可追加）：
`.git, node_modules, dist, build, out, target, .idea, .vscode`

4. 文件上限硬编码：单项目最大 10000 个可扫描文件，超限弹窗引导补充 exclude 配置

5. 单文件阈值：\>500KB 直接跳过，记录过滤清单，右下角气泡通知用户

## 2\.2 三种 Wiki 生成触发机制（完整实现逻辑）

### 触发 1：手动全量生成（知识库 Tab 按钮 / `/wiki create`）

#### 插件内小型项目流程（文件 \< 2000，不调用 CLI）

1. 后台开启 ReadAction，遍历项目 VirtualFile，依据 wiki\_plan 过滤有效源码文件

2. 通过 PsiIndex 批量解析模块、类、接口、函数、数据实体、API 定义

3. 将 PSI 结构化数据 \+ wiki\_plan 引导文案 \+ `.kilocode/rules` 项目规则拼接 Prompt

4. 调用插件内部`CommitMessageOrchestrator`同款 LLM 推理入口，批量生成 Wiki 页面 md 文本

5. WriteAction 写入`/.kilocode/repowiki/zh/wiki/`，生成元数据`.meta`记录每个文件哈希值（用于增量比对）

6. 同步触发`KnowledgeCardService`生成全套知识卡片

7. EDT 线程弹出 Balloon：Wiki 生成完成，展示扫描文件总数、过滤文件数量

#### 大型项目流程（文件 \> 2000，调用 KiloCode CLI 子进程）

1. 收集过滤后的文件列表，写入临时 json 传参给 CLI

2. 异步 ProcessBuilder 拉起`kilocode wiki-generate`，传入项目根目录、配置路径、语言标识

3. 监听 stdout 进度输出，实时同步进度至知识库 Tab 进度条

4. CLI 执行完成后，插件直接读取本地生成好的 md 与 meta 元数据，无需转换

5. 异常捕获：CLI 未安装 / 版本过低，降级使用插件内置轻量生成逻辑，弹窗提示用户安装新版 CLI

### 触发 2：代码变更自动增量更新（后台 FileWatchService）

1. 扩展 `VirtualFileListener`，注册全局文件监听，仅监听 src 源码目录（过滤黑名单）

2. 文件保存 / 新增 / 删除时，后台线程读取文件哈希，对比`.meta`元数据

3. 筛选出发生变更的文件集合，调用增量生成逻辑：

    - 小型项目：内部 LLM 仅重生成变更文件关联的 Wiki 页面

    - 大型项目：调用`kilocode wiki-update --changed-files=xxx.json`

4. 增量更新约束：**人工修改标记段落不会被覆盖**

    - 人工编辑内容前后插入固定注释标记 `<!-- kilocode-manual-edit-start -->` / `<!-- kilocode-manual-edit-end -->`

    - WikiManager 生成逻辑识别标记，仅重写无标记的自动生成段落

### 触发 3：本地手动编辑 md 文件，同步 Wiki

1. FileWatch 监听`repowiki/zh/wiki/`目录 md 文件变更

2. 用户手动修改后，知识库 Tab 出现【同步 Wiki】按钮

3. 点击后执行：更新 meta 哈希，标记所有新增内容为人工保护段，同步刷新关联知识卡片

## 2\.3 聊天框斜杠命令 `/wiki` 完整实现

### 扩展点：`ChatSlashCommandProvider`（自定义输入框指令解析器）

支持指令：

```Plain Text
/wiki create          全量生成Wiki
/wiki update          增量刷新变更代码Wiki
/wiki edit 页面名称   局部修改指定Wiki文档
/wiki append 页面名称 追加内容至Wiki页面
/wiki rewrite 页面名称 完全重写页面
```

### 执行流程（代码逻辑）

1. 用户输入指令 \+ 需求描述，可拖拽本地设计文档作为附件传入

2. 插件收集：目标 Wiki 页面原始 md、当前项目 wiki\_plan、项目 rules、变更文件哈希

3. 轻量修改走内部 LLM；大批量重写调用 CLI `kilocode knowledge-modify`

4. 写入本地 md，自动包裹人工编辑保护标记

5. 聊天面板返回修改预览，同步刷新知识库 Tab 文档列表

## 2\.4 多语言切换实现

1. 插件设置面板增加下拉框：知识库语言（中文 / English）

2. 切换逻辑：读取对应`zh/`或`en/`目录知识库；无对应语言目录时自动触发生成

3. 元数据隔离：不同语言目录独立 meta 哈希，互不干扰

# 三、模块 2：KnowledgeCard 知识卡片实现细节

## 3\.1 三类卡片自动生成规则

1. **架构卡片**
触发时机：全量 / 增量 Wiki 生成后同步生成
数据来源：PSI 模块分层、类依赖、服务调用关系、系统入口
存储路径：`repowiki/zh/knowledge_cards/architecture_xxx.md`

2. **编码规约 Spec 卡片**
数据来源：`.kilocode/rules` 规则文件、项目现有接口 / 命名 / 异常处理代码样本
用途：代码生成、Problems 一键修复、代码评审时优先注入上下文

3. **技术栈卡片**
自动解析 package\.json/build\.gradle/pom\.xml 等依赖配置，提取框架、版本、第三方组件

## 3\.2 联动更新逻辑

1. 代码变更增量更新 Wiki → 自动刷新对应模块架构 / 规约卡片

2. `/wiki edit` 修改架构 Wiki 页面 → 同步更新关联架构卡片

3. `/knowledge` 斜杠命令手动新增 / 修改卡片，独立存储，不受自动生成覆盖

## 3\.3 会话检索优先级

`KnowledgeRetriever` 检索权重：知识卡片 \> Wiki 文档片段 \> 记忆
目的：卡片轻量化结构化，减少 Token 消耗，提升 LLM 响应速度

# 四、模块 3：双层记忆 Memory 完整实现

## 4\.1 存储路径隔离（核心区分）

1. **项目记忆（可 Git 提交）**
路径：`/.kilocode/repowiki/zh/memory/*.md`
内容：当前仓库业务踩坑、模块决策、项目专属约束

2. **全局个人记忆（不进 Git）**
路径：IDE 全局配置目录 `$APP_DATA/KiloCode/global_memory/`
内容：跨项目通用编码偏好、通用技术踩坑、个人常用命令

## 4\.2 记忆沉淀两种渠道

### 渠道 1：Agent 任务自动沉淀

1. Agent 完成新增功能 / 修复 Bug / 代码评审任务后

2. 后台轻量 LLM 提炼对话关键结论、踩坑点、业务约束

3. 自动写入项目记忆，附带标签：`bug_fix / architecture / spec`

### 渠道 2：用户手动录入斜杠命令 `/remember`

示例：`/remember 本项目禁止使用var作为变量名，TS1389错误必须替换为let/const`
实现逻辑：

1. `ChatSlashCommandProvider` 捕获指令，提取记忆文本

2. 生成带标签的 md 记忆文件存入项目记忆目录

3. 知识库记忆面板可编辑、删除、搜索

## 4\.3 记忆管理 UI

知识库 Tab 新增 Memory 子面板：

- 搜索框：按标签 / 文本检索记忆

- 条目编辑、单条删除、批量清空项目记忆 / 全局记忆

- 切换项目 / 分支自动加载对应项目记忆

# 五、模块 4：KnowledgeRetriever 会话自动检索注入（核心联动能力）

## 5\.1 执行时机

用户在聊天框发送提问前，后台异步执行检索（不阻塞发送）

## 5\.2 检索完整流程

1. 获取当前编辑器打开文件、选中代码、当前活跃会话附加文件列表

2. 检索匹配规则：
1）匹配文件所属模块的**知识卡片**
2）检索对应模块 Wiki 文档相关片段
3）检索同模块标签的项目记忆
4）检索全局通用个人记忆

3. 去重、精简文本，控制总 Token 上限（可配置阈值）

4. 自动拼接入 System Prompt 头部，无需用户手动 @文件 / 知识库

## 5\.3 全功能联动（已有需求打通）

1. Problems 面板右键「Kilo 一键修复」
检索：错误对应文件模块 Spec 规约卡片 \+ 相关 Wiki

2. 资源管理器「添加文件 / 目录到会话」
附加文件同时自动检索文件所属架构卡片、Wiki 业务文档

3. Commit 对话框生成 commit message
读取技术栈卡片、模块业务记忆，生成贴合业务规范的提交信息

4. 编辑器右键发送代码片段
自动注入当前模块编码规约卡片，AI 生成代码对齐团队规范

# 六、UI 所有入口完整实现（JetBrains 扩展点）

## 6\.1 侧边 ToolWindow「项目知识库」Tab

扩展点注册 plugin\.xml

```xml
<toolWindow factoryClass="com.kilocode.toolwindow.WikiToolWindowFactory" id="KiloWiki" anchor="right">
    <title>Kilo知识库</title>
</toolWindow>
```

面板三分页：Wiki 文档、知识卡片、记忆管理
能力：Markdown 实时预览、一键生成 / 增量更新、同步 Git 修改、删除页面、跳转本地文件目录

## 6\.2 聊天输入框斜杠命令扩展

自定义输入框拦截器，匹配`/wiki /knowledge /remember`前缀，弹出指令提示下拉框

## 6\.3 ProjectView 项目视图右键菜单

扩展点 `com.intellij.projectView.popup`

```xml
<action id="kilo.wiki.refresh.file" class="xxx.RefreshFileWikiAction" text="刷新当前文件对应Wiki文档">
    <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
</action>
```

update 方法控制：选中源码文件才启用，二进制 / 目录过滤置灰

## 6\.4 IDE 设置面板

新增 KiloCode 配置项：

1. 知识库默认语言 zh/en

2. 文件扫描最大数量阈值

3. 单文件大小过滤阈值

4. 自定义额外 exclude 目录（追加内置黑名单）

5. 自动增量更新开关（可关闭后台监听）

# 七、线程模型规范（IDE 插件强制约束，避免卡顿崩溃）

1. **VFS 文件监听、PSI 解析、文件 IO、CLI 子进程调用**：后台异步线程 \+ ReadAction

2. **写入本地知识库 md/yaml**：后台线程 \+ WriteAction，禁止主线程写文件

3. **LLM 推理（内部 / CLI）**：独立线程池，设置全局超时（60s）

4. **所有 UI 操作（弹窗、Tab 刷新、进度条、通知）**：必须 `ApplicationManager.getApplication().invokeLater {}` 切换至 EDT 主线程

5. 禁止任何同步阻塞 IO、同步 CLI 调用，全部异步 \+ 进度通知

# 八、Git 团队同步完整实现逻辑

1. `.kilocode/repowiki` 目录全部为文本文件，用户可自由提交至 Git 远程仓库

2. 团队成员 git pull 拉取后，打开项目自动加载本地知识库，无需额外生成

3. 多人工修改同一 Wiki 页面：

    - Git 原生合并 Markdown 文本

    - 合并冲突检测：知识库 Tab 红色告警，内置简易 md 冲突对比编辑器

    - 人工编辑标记段落永久保留，AI 增量更新不会覆盖手写内容

4. 分支切换监听：通过 GitRepositoryManager 检测分支变更，自动加载当前分支独立知识库

# 九、异常兜底完整处理逻辑

|异常场景|插件实现处理方案|
|---|---|
|当前项目非 Git 仓库（无提交记录）|点击生成 Wiki 弹窗阻断：仅支持 Git 仓库；终止流程|
|扫描文件超过 10000 上限|停止全量生成，弹窗打开 wiki\_plan\.yaml，引导配置 exclude 过滤|
|单文件读取损坏 / 编码异常|跳过该文件，记录日志，气泡通知过滤文件名称，不中断整体生成|
|KiloCode CLI 未安装 / 版本过低|自动降级使用插件内置轻量 LLM 生成逻辑，弹窗提示安装新版 CLI 提升大项目性能|
|CLI 子进程启动超时、进程崩溃|销毁进程，切换内置生成，记录错误日志|
|Git 拉取 Wiki 文件存在合并冲突|知识库 Tab 展示冲突页面，提供可视化合并工具|
|知识库目录文件丢失、meta 元数据损坏|自动修复元数据，提供「重建元数据」按钮，无需重新全量生成|
|会话知识库检索 LLM 超时|降级：仅使用当前手动附加文件上下文，不阻断提问发送|
|写入知识库无文件权限|右下角红色 Balloon 提示：目录无写入权限，引导修改项目目录权限|

# 十、非需求范围（明确不实现，规避开发冗余）

1. 不依赖外部向量数据库，纯本地文件 \+ 元数据哈希检索，无额外部署依赖

2. 无强制云端知识同步，仅本地 Git 文件同步；企业云端团队知识中心预留扩展接口，本期不开发

3. 无 CI/CD 流水线自动更新 Wiki 能力，后续迭代规划

4. 记忆无自动过期清理逻辑，所有记忆由用户手动编辑删除

5. 不支持多人在线实时协同编辑 Wiki，完全基于 Git 文本合并实现团队协作

6. 不自动消耗用户 Credits 弹窗，生成 Wiki / 卡片前增加确认弹窗展示预估消耗

# 十一、测试覆盖核心用例（开发自测标准）

1. 全新 Git 项目，点击知识库 Tab 一键生成 Wiki，校验 md、知识卡片、meta 文件正常写入

2. 修改源码保存，验证后台自动增量更新对应 Wiki 页面，人工标记内容不被覆盖

3. 编辑 wiki\_plan\.yaml 新增 exclude 目录，重新生成后对应目录文件全部过滤

4. 聊天框输入`/wiki rewrite 系统架构概览`，校验页面重写并添加人工保护标记

5. 切换 Git 分支，验证加载当前分支独立知识库，分支间内容隔离

6. 将`.kilocode/repowiki`提交 Git，新成员拉取代码打开项目自动加载完整知识库

7. Problems 面板执行一键修复，校验自动注入对应模块知识卡片上下文

8. 项目为纯文件夹无 Git，点击生成 Wiki 弹窗拦截，无法执行生成

9. 单文件 600KB 源码，生成时自动跳过，气泡提示过滤信息

10. 切换知识库语言英文，自动生成 en 目录独立 Wiki 与卡片

> （注：部分内容可能由 AI 生成）

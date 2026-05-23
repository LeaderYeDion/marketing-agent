# 本项目与 langchain-ai/deepagents 的架构对比笔记

> 参考对象：
> - Deep Agents 官方文档：https://docs.langchain.com/oss/python/deepagents/overview
> - Deep Agents context engineering 文档：https://docs.langchain.com/oss/python/deepagents/context-engineering
> - Deep Agents subagents 文档：https://docs.langchain.com/oss/python/deepagents/subagents
> - Deep Agents skills 文档：https://docs.langchain.com/oss/python/deepagents/skills
> - Deep Agents API reference：https://reference.langchain.com/python/deepagents/graph/create_deep_agent
> - deepagents GitHub：https://github.com/langchain-ai/deepagents

## 1. 总体判断

本项目已经不是简单的 keyword router 或单 agent demo。它已经有比较清晰的 agent harness 方向：

- `MarketingHarness` 统一负责会话锁、上下文装配、task graph 规划、校验、调度、Observation、Recovery、HITL、审计和 telemetry。
- `TaskPlanner` 通过 LLM 生成 DAG，而不是由 Java 代码直接做自然语言意图路由。
- `CapabilityDescriptor` / `SkillDescriptor` 已经在尝试把能力描述为可组合、可评估、可授权的能力目录。
- `PendingActionStateMachine` 把高风险副作用动作关进确定性审批边界，这一点比很多 agent demo 更成熟。

但如果拿 deepagents 这种成熟 harness 项目来对照，本项目的主要差距不在“有没有 agent / tool / skill 这些名词”，而在这些概念是否已经进入运行时闭环：

| 维度 | deepagents | 本项目现状 | 主要差距 |
| --- | --- | --- | --- |
| harness | `create_deep_agent` 默认组装 Todo、Filesystem、SubAgent、Summarization、Patch、Memory、HITL 等中间件栈 | `MarketingGraphFactory` 只是 START -> harness -> END，核心逻辑集中在 `MarketingHarness` | 缺少可插拔 middleware 生命周期，很多能力是单体服务方法而不是运行时层 |
| 上下文管理 | 文件系统卸载、自动摘要、evicted history 可恢复、subagent 隔离、长期 memory | `ContextAssembler` 取最近消息、handoff summary、visible objects、state keys，然后拼接 compressedContext | 还停留在“截断和拼接”，缺少自动压缩、可检索上下文、文件/artifact 工作区 |
| 任务规划 | `write_todos` 是模型可主动维护的规划工具，计划随执行动态更新 | `TaskPlanner` 一次性生成 DAG，失败后可 replan，但 planner 与执行状态耦合较弱 | DAG 更工程化，但模型没有一等的持续 todo/plan 操作界面 |
| 子 agent | 主 agent 通过 `task` 工具动态派发，子 agent 独立上下文，返回简短结果 | sub-agent 多数作为 capability provider 后端；`SubAgentRegistry` 只是 registry | 子 agent 不是主模型的可调用协调工具，隔离价值和动态委派价值没有充分释放 |
| skill | `SKILL.md` + scripts/references/assets，前置信息先读 metadata，命中后渐进披露 | `manifest.yaml` + `skill.md` 静态资源；registry 用简易 YAML 解析 | skill 更像 capability manifest，不是可导航、可执行、可按需加载的技能包 |
| 文件系统/工件 | `ls/read_file/write_file/edit_file/glob/grep` 是内置工具，可换 backend，可做上下文卸载 | 有 `VisibleObject`、`artifactMemory` 字段和少量 FileTools，但不是统一虚拟文件系统 | 缺少 agent 可读写的工作目录抽象，工件不可被模型主动整理和重访 |
| HITL | 可对敏感 tool operation 配置 LangGraph interrupt | 有 PendingAction 状态机，审批边界清楚 | 本项目 HITL 业务状态更强，但还不是统一 tool-level interrupt middleware |
| 观测/前端 | stream 可显示 subagent、todos、sandbox、HITL | metadata 包含 taskGraph、observations、trace、risk | 观测数据有，但 UI/streaming 协议还未变成深度 agent 工作台 |

一句话：本项目有较强的“业务 harness 骨架”，deepagents 强在“agent harness 运行时产品化”。本项目偏 deterministic orchestration，deepagents 偏模型可操作的通用工作台。

## 2. deepagents 的核心设计思想

deepagents 官方把它称为 agent harness：仍然是模型工具调用循环，但默认带上复杂任务所需的运行时能力。官方 overview 提到它内置 task planning、file system context management、subagent spawning、long-term memory，并基于 LangGraph 获得 durable execution、streaming、HITL 等能力。

`create_deep_agent` 的默认工具和中间件很关键。API reference 说明默认工具包括：

- `write_todos`：维护 todo list。
- `ls/read_file/write_file/edit_file/glob/grep`：文件操作。
- `execute`：sandbox backend 支持时执行 shell。
- `task`：调用 subagents。

默认 middleware 栈包括：

- `TodoListMiddleware`
- `SkillsMiddleware`
- `FilesystemMiddleware`
- `SubAgentMiddleware`
- `AsyncSubAgentMiddleware`
- `SummarizationMiddleware`
- `PatchToolCallsMiddleware`
- profile extra middleware / tool exclusion / prompt caching
- `MemoryMiddleware`
- `HumanInTheLoopMiddleware`

这说明 deepagents 的核心不是某个复杂 graph 写死了很多节点，而是通过 middleware 把“计划、上下文、文件、子 agent、压缩、权限、审批、记忆”这些能力放进每次模型调用和工具调用的生命周期。

这和本项目最大不同：本项目目前把大部分逻辑集中在 `MarketingHarness.runLocked()`、`executePlannedGraph()`、`applyRecoveryIfNeeded()` 等方法中。它已经有 harness，但还不是一个可组合 middleware harness。

## 3. 上下文管理差距

### 3.1 deepagents 的上下文管理

deepagents 的上下文管理有几个层次：

1. 虚拟文件系统  
   模型可以把长工具结果、草稿、分析中间产物写入文件，再用 `grep/glob/read_file` 重访。这样大上下文不是全部塞回 message history，而是变成可导航工作区。

2. 自动摘要  
   context engineering 文档强调，当上下文接近窗口限制时，会截断或摘要旧消息。API reference 还提到 deepagents 的 summarization 会把被逐出的历史写入 `/conversation_history/{thread_id}.md`，摘要中保留路径，让 agent 后续可用 `read_file` 找回。

3. 子 agent 隔离  
   子 agent 在独立上下文中完成重型检索、阅读、分析，主 agent 只拿最终简短报告，避免主上下文被工具调用污染。

4. 长期 memory  
   通过 LangGraph store / memory middleware 把跨线程、跨会话知识接入运行时。

### 3.2 本项目的上下文管理

本项目的 `ContextAssembler` 做了一个简洁的上下文装配：

- 最近可见消息：`recentVisibleMessages(session)` 只保留最近 12 条。
- handoff summary：只取最近 8 条。
- visible objects、pending actions、working state、task/artifact/decision memory。
- `compressedContext()` 将 capability 列表、visible object ids、pending action ids、state keys 拼成字符串。

相关代码：

- `ContextAssembler.assemble()`：`marketing-agent-core/src/main/java/com/example/marketing/core/memory/ContextAssembler.java`
- `HarnessMemory`：`marketing-agent-core/src/main/java/com/example/marketing/core/memory/HarnessMemory.java`

### 3.3 欠缺点

本项目目前的“压缩”主要是选择性截断和摘要字段拼接，不是运行时自动上下文工程：

- 没有 token budget 感知：不知道当前上下文占比，也不会在过长时自动压缩、重试。
- 没有 evicted history 的可恢复路径：被截掉的消息没有进入可读文件或可检索 memory。
- `artifactMemory` 只是字段，不是模型可操作的工作区。
- RAG 是工具能力，不是统一上下文后端；模型不能像操作文件系统一样浏览、整理、搜索当前工作材料。
- capability catalog 每次被完整拼进 planner prompt，随着能力变多会膨胀。

### 3.4 可借鉴改造

1. 引入 `AgentWorkspace` / virtual filesystem 抽象  
   给每个 conversation/thread 一个工作区，支持：
   - `/conversation_history/`
   - `/artifacts/`
   - `/observations/`
   - `/plans/`
   - `/skills/`
   - `/memory/`

2. 工具化文件访问  
   为 sub-agent 和 planner 提供受控的 `list/read/write/search` 工具，而不是把所有东西塞进 prompt。

3. 做 token-aware context policy  
   `ContextAssembler` 不只按条数裁剪，而是根据 token budget 决定：
   - 保留最近关键消息。
   - 将旧消息摘要到 summary。
   - 将原文归档到 workspace，并在 summary 中保留路径。
   - 对超长 tool result / observation 做文件卸载。

4. capability catalog 分层披露  
   先暴露能力索引和摘要，planner 需要时再读取完整 manifest/schema/skill 文档。

## 4. 主子 agent 协调差距

### 4.1 deepagents 的模式

deepagents subagents 文档强调，subagent 的核心价值是 context quarantine。主 agent 通过 `task` 工具委派任务，子 agent 独立执行，最后只返回简洁结果。这样主 agent 专注高层协调。

典型流程：

1. 主 agent 建立高层计划。
2. 调用 `task(name="researcher", task="...")`。
3. 子 agent 用自己的 prompt、tools、model、skills 完成任务。
4. 子 agent 返回最终报告。
5. 主 agent 合成结果并继续下一步。

### 4.2 本项目的模式

本项目有 `SubAgentRegistry`、`SubAgent`、`InquiryAgent`、`AgenticSubAgentSupport`。其中 `InquiryAgent` 内部会创建 Alibaba Graph `ReactAgent`，接入 `SkillsAgentHook`、`ModelCallLimitHook`、tool retry/error interceptor，并暴露知识检索工具。

这说明本项目已经能运行 agentic sub-agent。但在系统层面，子 agent 更多是 capability provider 的实现细节，而不是主 agent 可动态调用的协调工具：

- `TaskPlanner` 选择的是 capabilityName，不是选择“派发一个 task 给某个子 agent”。
- `SubAgentRegistry` 只有 find/capabilities，没有被包装成模型可用的 `task` 工具。
- 子 agent 的上下文由上层 invocation 组装，主模型不能在执行中自发地把复杂子任务隔离出去。
- 子 agent 返回的是 `SubAgentResult`，会被 provider 转换为 observation，但中间工作流对主模型不是一种一等委派机制。

### 4.3 欠缺点

- 缺少 general-purpose subagent。deepagents 默认提供通用子 agent，用来做不需要专门角色但需要隔离上下文的复杂任务。
- 缺少统一 task delegation 协议。现在是“planner 规划 capability DAG”，不是“coordinator 在工具循环中动态派发子任务”。
- 子 agent 隔离不够系统化。虽然 `InquiryAgent` 有自己的 ReactAgent，但其他 capability 未必都隔离，主 harness 也没有统一约束“子 agent 只能返回 summary，不返回原始大上下文”。
- 子 agent 工具面和权限继承/覆盖机制不足。deepagents 支持 subagent 继承或覆盖 filesystem permissions，本项目还没有这层抽象。

### 4.4 可借鉴改造

1. 增加 `delegate_task` capability/tool  
   让 planner 或 coordinator 能把任务交给 `general_purpose` 或专业子 agent。输入包括 agentName、task、expectedOutput、contextRefs、maxWords。

2. 将 sub-agent invocation 结果统一写入 workspace  
   主 harness 只接收 summary + artifact refs；详细过程、证据、工具输出写入 `/subagents/{invocationId}/`。

3. 区分两类子 agent  
   - capability-backed subagent：用于稳定业务能力，如规则查询、文案生成。
   - context-isolation subagent：用于临时深挖、分析、阅读、清洗资料。

4. 为子 agent 定义 permission profile  
   比如 inquiry agent 只读知识库，enrollment execution agent 只能在 HITL 后调用写操作。

## 5. 复杂任务编排差距

### 5.1 本项目的优势

本项目的 `TaskGraph` 比 deepagents 的 todo list 更工程化：

- 节点有 `dependsOn`。
- `readyNodes()` 可以计算可执行节点。
- `MarketingHarness` 会用 `CompletableFuture` 并行执行 ready nodes。
- 节点状态可以进入 waiting_for_user / waiting_for_approval / failed / succeeded。
- `TaskGraphValidator`、`ObservationEvaluator`、`RecoveryPolicyEngine` 形成执行闭环。

这在业务流程编排上是优势。deepagents 的 `write_todos` 更像模型操作的计划板，不天然等价于一个可验证 DAG。

### 5.2 本项目的不足

问题在于，本项目的 DAG 目前偏“一次性计划产物”，而 deepagents 的 todo 是“模型持续维护的工作记忆”。

本项目的表现：

- `TaskPlanner.plan()` 生成初始 DAG。
- 执行中有 `replan()`，但触发和融合仍由 harness 决定。
- 模型没有一个可随时更新的 plan/todo tool。
- task graph 与 workspace、子 agent 结果、用户审批之间的引用关系还不够自然。

deepagents 的思路是：让模型主动维护 todo list，运行时负责让 todo 状态可见、可流式展示、可持久化。它不一定比 DAG 更严谨，但更适合长任务中持续调整。

### 5.3 可借鉴改造

不要直接放弃 DAG。更好的路线是“双层计划”：

- `TaskGraph`：工程执行层，负责依赖、并行、审批、幂等、恢复。
- `Todos`：模型工作层，负责可读计划、进度、阶段性调整、前端展示。

可以增加：

- `PlanMemory`：存储当前 todo、DAG 映射、阶段目标。
- `write_todos` 风格 capability：允许 planner/coordinator 更新 todo。
- DAG node 与 todo item 的双向引用。
- 当 observation 失败或信息不足时，先更新 todo，再 replan DAG。

## 6. harness 架构差距

### 6.1 deepagents 的 harness 是 middleware-first

deepagents 的架构重点是 middleware：

- 在每次 model call 前注入工具说明、技能说明、权限提示、memory。
- 在 tool call 前后做权限、HITL、patch、error handling。
- 在上下文超限时压缩并重试。
- 在子 agent 创建时自动套默认 middleware。

这类设计的好处：

- 新增横切能力不用改主 harness 主流程。
- 同一套能力可复用于主 agent 和子 agent。
- 可以根据 profile 排除或替换 middleware。
- 模型、backend、memory、permissions 都有比较清晰的插拔点。

### 6.2 本项目的 harness 是 service-first

`MarketingHarness` 是一个业务运行时大类。它的优点是流程清楚、确定性强、业务状态好控；缺点是横切能力容易堆在同一个类里：

- context assemble
- planning
- validation
- scheduling
- risk
- execution
- observation commit
- evaluation
- recovery
- HITL feedback
- metadata projection

`MarketingGraphFactory` 目前只把 Spring AI Alibaba Graph 用成一个单节点 wrapper：START -> harness -> END。真正的 agent graph 没有被拆成可观测可替换的节点。

### 6.3 欠缺点

- 缺少 model-call middleware：prompt injection、memory injection、tool filtering、context compaction 不能按层组合。
- 缺少 tool-call middleware：权限、HITL、重试、结果卸载、trace 不是统一围绕 tool/capability call 实现。
- 缺少 harness profile：不同场景无法声明式启用/禁用文件系统、摘要、子 agent、执行工具、审批策略。
- Graph runtime 利用不充分：现在图只有一个 harness 节点，durable execution、interrupt、streaming 粒度不够细。

### 6.4 可借鉴改造

可以不用照搬 LangChain middleware API，但应该引入本项目自己的 harness extension points：

```text
beforeContextAssemble
afterContextAssemble
beforePlan
afterPlan
beforeCapabilityCall
afterCapabilityCall
beforeObservationCommit
afterObservationCommit
beforeModelCall
afterModelCall
onContextOverflow
onHumanApprovalRequired
```

优先拆出的 middleware：

1. `ContextBudgetMiddleware`
2. `WorkspaceOffloadMiddleware`
3. `ToolPermissionMiddleware`
4. `HumanApprovalMiddleware`
5. `TodoMiddleware`
6. `SubAgentDelegationMiddleware`
7. `SkillProgressiveDisclosureMiddleware`
8. `TraceMiddleware`

## 7. skill 落地差距

### 7.1 deepagents 的 skill

deepagents skills 文档说明，skill 是一个目录：

- `SKILL.md`：包含说明和 metadata。
- 可选 scripts。
- 可选 references/docs。
- 可选 assets/templates。

关键点是 progressive disclosure：agent 启动时先读每个 `SKILL.md` 的 frontmatter；只有任务命中时，再读取完整 skill 内容以及它引用的脚本、文档、模板。

skill 不是单纯“能力描述”，而是一个可导航、可执行、可复用的知识和工作流包。

### 7.2 本项目的 skill

本项目当前 skill 是：

- `/skills/index.txt`
- 每个 skill 的 `manifest.yaml`
- 每个 skill 的 `skill.md`
- `SkillRegistry` 启动时读取 index 和 manifest。
- `load(skillName)` 直接读 skill.md。

`rule_inquiry/manifest.yaml` 已经包含 name、summary、intentHints、entryAgent、requiredInputs、canEmit、permissions、riskLevel、composableWith、preconditions、postconditions 等字段。

这很像 capability manifest，优点是结构化；但和 deepagents 的 skill 相比，它缺少“技能包”能力：

- 没有 scripts/references/assets 的一等目录约定。
- manifest parser 是简单按冒号切分，不支持完整 YAML、嵌套 schema、多行说明。
- progressive disclosure 不完整：planner 会看到 capability catalog，但 skill.md 何时被读、读多少、是否按需读取，还主要依赖具体 agent hook。
- skill 和 tool binding 没有强关系：读了 skill 后不会动态暴露某些工具或脚本。
- 没有 skill lifecycle：版本、校验、权限扫描、eval、owner 虽有字段但没有运行时治理。

### 7.3 可借鉴改造

建议把当前 skill 升级成两层：

1. `CapabilityManifest`：给 planner 看，用于能力选择和组合。
2. `AgentSkillPackage`：给执行 agent 看，用于具体工作流。

推荐目录：

```text
skills/
  rule_inquiry/
    SKILL.md
    manifest.yaml
    references/
      rule_sources.md
      citation_policy.md
    scripts/
      normalize_query.py
    eval/
      golden-cases.yaml
    assets/
      answer_template.md
```

运行时策略：

- planner 只看 manifest 摘要。
- sub-agent 命中 skill 后读取 `SKILL.md`。
- `SKILL.md` 中显式引用 references/scripts/assets。
- harness 校验 skill 权限，不允许 skill 随意启用高风险工具。
- eval harness 根据 skill 的 eval cases 做回归测试。

## 8. 文件系统和 artifact 体系差距

deepagents 把文件系统作为上下文管理核心，而不是“文件工具的一个补充”。这点对本项目很值得借鉴。

本项目已经有：

- `VisibleObject`
- `artifactMemory`
- `Observation.artifacts`
- `FileTools`
- `PendingAction.visibleObjectId`

但这些对象主要面向响应和业务状态，还不是 agent 的工作内存。模型不能主动说：

- 把这次规则查询证据写到 `/artifacts/rule_check.md`。
- 在 `/observations/` 里 grep 某个商品 ID。
- 把 Excel 解析结果保存成中间 JSON。
- 从 `/conversation_history/` 取回上周用户确认过的口径。

建议新增 workspace backend：

```text
interface AgentWorkspace {
    list(path)
    read(path, range)
    write(path, content, metadata)
    edit(path, patch)
    search(path, query)
    stat(path)
}
```

实现可以先用 DB/内存，不必真落磁盘。关键是给 agent 一个稳定的“可命名、可重访、可检索”的外部工作记忆。

## 9. HITL 与权限边界

本项目在业务 HITL 上其实比 deepagents 的通用描述更贴业务：

- `PendingAction`
- `PendingActionStateMachine`
- approve/reject/edit/expire/executed
- visible object 状态联动
- idempotency key
- audit event

这是本项目应该保留和强化的优势。

欠缺的是 tool-level / capability-level 的统一 interrupt 模型：

- 当前审批多围绕业务 pending action，而不是所有敏感工具调用。
- 风险策略已经有 `RiskPolicyEngine`，但还可以更接近 middleware：任何 capability/tool call 前都经过同一权限判断。
- 子 agent 内部如果有高风险工具，也应继承审批机制，而不是只在上层 capability 做。

建议：

- 所有 sideEffects=true 或 permission 包含 write/execute/external_send 的 capability 都自动进入 approval middleware。
- sub-agent 内部工具也要走统一 `ToolPermissionMiddleware`。
- PendingAction payload 应包含 diff、preview、rollback/compensation hint、idempotency key、source evidence refs。

## 10. 本项目比 deepagents 做得好的地方

对比不是单向批评。本项目有几处很适合继续发展：

1. 业务状态机更明确  
   PendingAction 的状态迁移比通用 HITL 更贴真实业务审批。

2. TaskGraph 比 todo 更适合严肃业务流程  
   DAG、依赖、并行、节点状态、recovery 都是营销任务编排的必要骨架。

3. CapabilityDescriptor 比普通 tool doc 更业务化  
   requiredInputs、outputContract、risk、sideEffects、composableWith、fallback、pre/postconditions 都是 planner 需要的语义。

4. Observation 对后续 eval/recovery 友好  
   统一 observation 比直接拼工具字符串更工程化。

所以不建议“重写成 deepagents”。更好的方向是：保留本项目的业务 harness 和 DAG，把 deepagents 的 middleware、workspace、subagent isolation、progressive skill disclosure 借进来。

## 11. 建议演进路线

### Phase 1：补上下文工作区

- 引入 `AgentWorkspace` 接口。
- 将 long observation、tool result、旧消息归档到 workspace。
- `ContextAssembler` 输出 refs，而不是只输出字符串。
- 给 inquiry/copywriting agent 增加只读 workspace search/read 工具。

### Phase 2：补 Todo/PlanMemory

- 增加 `TodoItem` 和 `PlanMemory`。
- `TaskPlanner` 生成 DAG 的同时生成 todo。
- `MarketingHarness` 执行节点时同步更新 todo 状态。
- SSE/metadata 输出 todo，用于前端展示。

### Phase 3：补 subagent delegation

- 增加 `delegate_task` capability。
- 增加 `general_purpose_agent`。
- 子 agent 结果写 workspace，主 harness 只收 summary 和 refs。
- 为每个子 agent 定义 tools/model/permission profile。

### Phase 4：skill 包升级

- 支持 `SKILL.md` frontmatter。
- 支持 references/scripts/assets/eval 子目录。
- 改用标准 YAML parser。
- 增加 skill validation 和 permission scan。
- planner 只读 skill 摘要，执行 agent 按需读取全文。

### Phase 5：middleware 化

- 从 `MarketingHarness` 中抽出 context、permission、approval、trace、workspace offload、skill disclosure、subagent delegation middleware。
- 将 `MarketingGraphFactory` 从单节点图升级为更细粒度 graph，至少能在 plan/execute/approval/resume 上做 durable interrupt。

## 12. 架构笔记：理想形态

目标形态不是“很多 agent”，而是一个可持续工作的营销智能体运行时：

```text
User Request
  -> Conversation Lock
  -> ContextBudgetMiddleware
  -> Workspace / Memory load
  -> Capability + Skill index disclosure
  -> Planner
       -> TaskGraph
       -> TodoList
  -> Graph Validator
  -> Scheduler
       -> CapabilityProvider
       -> delegate_task SubAgent
       -> Tool calls
  -> ToolPermissionMiddleware
  -> WorkspaceOffloadMiddleware
  -> Observation
  -> ObservationEvaluator
  -> Recovery / Replan / Ask User / Approval
  -> Workspace + Conversation commit
  -> Stream todo/subagent/observation/artifact state
  -> Final answer
```

核心原则：

1. prompt 不承载全部上下文，workspace 承载可重访上下文。
2. 主 agent 不吞下所有中间过程，subagent 隔离重型任务。
3. skill 不只是说明文字，而是按需加载的工作流包。
4. DAG 负责确定性执行，todo 负责模型可读的持续计划。
5. 所有副作用都经过统一 permission/HITL middleware。
6. observation 是执行闭环的事实单元，workspace refs 是事实可追溯路径。

## 13. 最关键的差距总结

如果只选三个最值得补的点：

1. 上下文管理从“截断拼 prompt”升级为“workspace + 自动摘要 + refs”。  
   这是 deepagents 最核心的工程价值，也是长任务能不能稳定的分水岭。

2. 子 agent 从“capability 后端实现”升级为“主模型可调用的 context isolation 工具”。  
   这能显著降低主上下文污染，让复杂检索、分析、表格处理、规则核验等任务更稳。

3. skill 从“manifest + prompt 文档”升级为“SKILL.md + references/scripts/assets/eval 的渐进披露包”。  
   这能让能力扩展从写 Java provider 变成可治理的 agent 能力包生态。

本项目当前方向是对的，但还像一个“业务编排 harness 雏形”。deepagents 的启发是：把复杂 agent 需要的上下文、计划、技能、子任务、权限、摘要、文件系统全部做成默认运行时能力，让模型在这些能力之间工作，而不是让每个业务 provider 自己零散实现。

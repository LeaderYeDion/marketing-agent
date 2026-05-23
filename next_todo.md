# Marketing Agent Next TODO

## 文档定位

`project_goal.md` 定义项目终极蓝图：本项目不是固定 workflow、关键词路由或 sub-agent 委派系统，而是以 LLM 自然语言理解为决策内核、以 harness engineering 作为工程约束和运行时底座的营销通用智能体。

`comparing_deep_agents.md` 进一步指出：本项目已经有业务 harness 骨架，但相比 `langchain-ai/deepagents`，仍需持续补齐成熟 agent harness 运行时常见的几类基础能力。当前代码已补上 workspace / filesystem context 的基础入口，以及 harness middleware lifecycle 的基础栈；后续重点转向 todo plan memory、统一副作用权限、subagent context isolation、progressive skill disclosure、schema-aware validation 与 eval。

本文档记录下一步迭代方向。优先级不按改动量、短期紧急程度或业务演示效果排序，而按架构合理性排序：先补运行时地基，再补能力契约，再补具体 provider 质量。

## 当前总体判断

本项目当前方向是正确的，并且已经超过普通 agent demo。基于当前代码重新核查后，运行时基线已经发生变化：

- `MarketingHarness` 仍是统一运行入口，但已经接入 `HarnessMiddlewareChain`，context assemble、model planning、capability call、approval、observation commit 都有生命周期钩子。
- `AgentWorkspace`、`InMemoryAgentWorkspace`、`WorkspaceTools` 已存在；observation、evidence、artifact、conversation history 已能写入 workspace，并通过 `workspaceRefs` 回到 metadata、session state、provider/sub-agent 上下文。
- `WorkspaceOffloadMiddleware` 已承接 observation/evidence/artifact offload；`TraceMiddleware` 已承接生命周期 trace；`ToolPermissionMiddleware` 已承接 capability 执行前风险评估；`ContextBudgetMiddleware` 已记录上下文预算状态。
- `TaskPlanner` 已通过 LLM 生成 DAG，避免在 Java 代码里做自然语言关键词路由。
- `TaskGraph` 已能表达节点依赖、并行 ready node、waiting_for_user、waiting_for_approval、failed、succeeded 等状态。
- `CapabilityDescriptor` / `SkillDescriptor` 已经在尝试把能力暴露为可组合、可治理、可评估的能力目录。
- `PendingActionStateMachine` 已把高风险副作用动作关进确定性审批边界。
- `ObservationEvaluator`、`RecoveryPolicyEngine` 和 `TaskGraphValidator` 已有第一版执行闭环。

因此，已实现的 P0-1 / P0-2 不再保留为 TODO 主体。剩余差距集中在“运行时能力是否能规模化复用”和“能力契约是否足够机器可验证”：

- DAG 是一次性计划产物，缺少模型可持续维护的 todo / plan memory。
- 副作用审批已有状态机和 capability 前置风险评估，但 operation、pending action、observation、audit 的双向引用仍不完整；sub-agent 内部工具调用也还没有继承统一 permission middleware。
- sub-agent 多数是 capability provider 的内部实现，没有作为 context isolation 工具被主运行时统一调度。
- skill 仍更像 capability manifest 载体，不是 `SKILL.md + references/scripts/assets/eval` 的渐进披露技能包。
- manifest schema、plan validation、observation contract、eval harness 仍不足以支撑能力规模化扩展。
- workspace 当前是内存实现，历史归档是确定性归档；token-aware summarization、持久化 backend、context overflow retry 属于后续工程增强，不再视为运行时地基阻塞项。

因此，后续迭代的架构主线应调整为：

```text
PlanMemory / Todo + TaskGraph
  -> ToolPermission / HITL / Operation Lineage
  -> SubAgent Delegation / Context Isolation
  -> Capability Manifest / Skill Package 分层
  -> Schema-aware Validation / Observation Evaluation / Recovery
  -> RAG / Provider / Answer / UI 质量增强
```

## 架构优先级说明

- P0：运行时地基。若不先做，后续能力越多，系统越容易变成大 harness 方法、prompt 拼接和 provider 孤岛。
- P1：能力契约和编排闭环。若不做，系统能演示复杂任务，但无法稳定规划、验证、恢复和评估。
- P2：领域能力质量和证据层。若不做，架构是对的，但业务回答和真实执行质量不够。
- P3：体验、工程治理和持续演进。若不做，不会立刻破坏架构，但会影响长期可用性和可维护性。

## 不变原则

1. 模型负责语义，代码负责约束。
2. task graph 是复杂任务的工程执行表达，不是某个 agent 的内部细节。
3. capability 是系统能力地图，不允许用 hidden agent 或关键词路由绕过 catalog。
4. observation 是执行闭环的事实单元，所有 provider / sub-agent / tool 结果都要转成 observation。
5. 所有副作用必须经过 policy、HITL、idempotency、audit 和状态机。
6. skill 只能增强模型理解和执行质量，不能成为隐形权限入口。
7. context 不能永远靠 prompt 承载，必须进入可命名、可检索、可恢复的工作记忆。

## P0：运行时地基优先

### P0-1 建立 PlanMemory / Todo，与 TaskGraph 形成双层计划

架构原因：

本项目的 `TaskGraph` 比 deepagents 的 todo 更适合严肃业务流程，因为它能表达依赖、并行、审批和恢复。但 DAG 偏工程执行层，缺少模型可持续维护的“工作计划”。deepagents 的 `write_todos` 强在让模型持续更新任务进度和下一步。最佳路线不是二选一，而是双层计划。

目标状态：

```text
TaskGraph
  -> 工程执行层：依赖、并行、状态、审批、幂等、恢复

TodoList / PlanMemory
  -> 模型工作层：阶段目标、用户可读进度、动态调整、前端展示
```

下一步行动：

1. 新增 `TodoItem`、`PlanMemory`、`PlanMemoryStore`。
2. `TaskPlanner.plan()` 生成 DAG 的同时生成初始 todo list。
3. `MarketingHarness` 执行节点时同步更新 todo 状态，并写入 `/todos/current.json`。
4. DAG node 与 todo item 建立双向引用：`taskNodeId`、`todoId`。
5. recovery / replan 前先更新 todo，记录失败、等待用户、等待审批或新计划。
6. metadata / SSE 输出 todo 状态，支持前端展示复杂任务进度。

完成标准：

- 复杂任务不仅有内部 DAG，还有用户和模型都能读懂的持续计划。
- replan 后能看出哪些 todo 已完成、哪些被替换、哪些新增。
- todo 不替代 task graph；它服务于长任务推进和可解释性。

### P0-2 统一副作用权限、HITL 与 Operation Lineage

架构原因：

本项目的 PendingAction 状态机是优势，应保留。但当前 HITL 更偏业务 pending action，而不是所有敏感 tool / capability call 的统一 interrupt 模型。随着 sub-agent 和 Re-Act provider 增多，副作用边界必须在 capability/tool 调用层统一生效。

当前状态：

- high-risk capability 已经在 provider 执行前由 `ToolPermissionMiddleware` 调用 `RiskPolicyEngine` 拦截。
- `PendingActionStateMachine` 已管理 approve/reject/edit/expire/executed。
- `enrollment_execute` 已要求 `pending_action_approved=true`。
- `TraceMiddleware` 已能记录 `on_human_approval_required`，但 pending action 创建、operation record、audit payload 仍主要在 `MarketingHarness` 内完成。

仍需补齐：

1. `OperationRecord` 标准字段化引用：
   - `pending_action_id`
   - `task_graph_id`
   - `task_node_id`
   - `capability_name`
   - `observation_id`
   - `approved_by`
2. pending action、operation、observation、audit 双向关联。
3. sub-agent 内部工具调用也必须继承 permission middleware。
4. sideEffects=true 或 permission 包含 write/execute/external_send 的能力必须自动进入审批边界。

下一步行动：

1. 抽出 `PendingActionFactory`，从 `MarketingHarness` 中分离 pending action 绑定、不可变字段、idempotency key、audit payload。
2. 扩展 `OperationRecord` 标准字段。
3. 在 `ToolPermissionMiddleware` 中统一判断 sideEffects、permissions、riskLevel、approval requirement，避免只依赖 provider 私有约定。
4. 增加回归：payload 篡改、重复确认、过期确认、错误 provider 产出执行卡、sub-agent 内部敏感工具调用。

完成标准：

- 任意 provider 或 sub-agent 都不能绕过副作用边界。
- 可以从 operation 反查 pending action、task node、observation、audit event。
- HITL 不只是某个业务 provider 的约定，而是运行时统一机制。

### P0-3 引入 SubAgent Delegation 作为 Context Isolation 工具

架构原因：

此前把 sub-agent 从流程中心降级为 capability provider 是正确的，因为不能让任意 sub-agent 接管系统。但 deepagents 的启发是：sub-agent 还有另一个重要价值，即 context isolation。当前 workspace 和 middleware 基线已经具备，主运行时应能把重型检索、阅读、表格分析、规则核验委派给隔离上下文，主上下文只接收 summary + refs。

目标状态：

区分两类 sub-agent：

1. capability-backed sub-agent  
   作为明确 capability provider 的内部实现，例如 `rule_inquiry`。

2. context-isolation sub-agent  
   作为主运行时可调用的 `delegate_task` 能力，用于临时深挖、分析、阅读、清洗资料。

下一步行动：

1. 新增 `delegate_task` capability，输入包含：
   - `agentName`
   - `task`
   - `expectedOutput`
   - `contextRefs`
   - `allowedTools`
   - `maxSteps`
   - `maxTokens`
2. 新增 `general_purpose_agent`，只允许读 workspace、读知识库、做分析总结，不允许副作用。
3. 子 agent 详细工具输出写入 `/subagents/{invocationId}/`。
4. 主 harness 只接收 summary、confidence、workspace refs 和 observation。
5. 为每个 sub-agent 定义 permission profile，并接入 `ToolPermissionMiddleware`。

完成标准：

- 主上下文不再吞下所有重型检索和分析过程。
- 子 agent 不能绕过 capability catalog、schema、policy 和 HITL。
- complex task 可以通过 `delegate_task` 获得隔离分析，但最终仍回到 task graph / observation 闭环。

## P1：能力契约、skill 分层和子任务隔离

### P1-1 将 Capability Manifest 与 Skill Package 分层

架构原因：

当前 `SkillDescriptor` 实际承担的是 capability manifest 的职责：provider、requiredInputs、permissions、sideEffects、risk、fallback、composableWith。这些是运行时契约，不是 skill。deepagents 的 skill 更像可按需加载的过程性知识包：`SKILL.md + references + scripts + assets + eval`。

目标状态：

```text
Capability Manifest
  -> 机器可读运行时契约
  -> 给 planner / validator / policy / harness / eval 使用

Skill Package
  -> LLM 可读过程性知识包
  -> 给 planner / react provider / sub-agent 使用
```

建议目录：

```text
skills/{capability_name}/
  manifest.yaml
  SKILL.md
  references/
  scripts/
  assets/
  eval/
```

下一步行动：

1. 引入 `CapabilityManifestDescriptor`，逐步承接当前 `SkillDescriptor` 中的运行时契约字段。
2. manifest 只保留机器契约：name、provider、inputSchema、outputSchema、permissions、risk、sideEffects、approval、fallback、composableWith、budget、pre/postconditions。
3. `SKILL.md` 承担过程性知识：参数抽取、工具调用步骤、失败修复、证据判断、few-shot、反例。
4. 支持 `SKILL.md` frontmatter 和 references/scripts/assets/eval 子目录。
5. planner 默认只看 compact manifest；复杂能力命中后再按需加载 skill 摘要或全文。
6. react provider 可以读取对应 `SKILL.md`，但工具调用仍必须来自 provider allowlist。

完成标准：

- manifest 可被 validator/eval 独立校验。
- skill 可被模型按需加载，但不改变权限、安全和审计边界。
- 新增能力时不需要修改 planner 基础 prompt，只需要更新 manifest / provider / skill / eval。

### P1-2 强化字段级 Schema、Manifest Validator 和 Observation Contract

架构原因：

Planner 能否稳定组合能力，取决于它看到的能力契约是否足够结构化。当前 `inputSchema/outputSchema` 主要由 `requiredInputs/outputContract` 自动生成，无法判断上游输出是否满足下游输入，也无法验证 provider 输出。

下一步行动：

1. 用标准 YAML parser 替代当前手写 key/value + CSV parser。
2. manifest 支持字段级 `inputSchema` / `outputSchema`。
3. 新增 `CapabilityManifestValidator`，校验：
   - provider 是否存在
   - schema 是否合法
   - sideEffects 与 permissions / approval 是否一致
   - fallback / composableWith 是否引用合法 capability
   - riskLevel 是否符合策略
4. 新增 `ObservationContractValidator`，校验 provider 输出是否满足 output schema。
5. 将 evidence、artifacts、visibleObjects、missingInputs、confidence、workspaceRefs 纳入 observation contract。

完成标准：

- planner 能基于 schema 判断依赖是否成立。
- provider 输出不符合 contract 时会被 runtime 或测试发现。
- capability catalog 从“说明文字”升级为可机器校验的能力地图。

### P1-3 建立 Skill Progressive Disclosure

架构原因：

能力多了以后，不可能把所有 skill 文档完整塞进 planner prompt。deepagents 的做法是先读 metadata，命中后渐进披露。这个机制也依赖 P0 的 workspace 和 P1 的 manifest/skill 分层。

下一步行动：

1. 新增 `SkillKnowledgeLoader`：
   - load index
   - load frontmatter summary
   - load full `SKILL.md`
   - load referenced files
2. `ContextAssembler` 只注入 skill index / relevant summaries。
3. planner 需要复杂能力时，可通过受控方式读取 skill 摘要。
4. react provider / sub-agent 命中 skill 后读取完整 `SKILL.md` 和必要 references。
5. 对 skill references 做 token budget 控制和权限扫描。

完成标准：

- 能力数量增加时，planner prompt 不线性爆炸。
- 复杂能力能按需获得过程性知识。
- skill 不能隐式启用未授权工具或副作用。

### P1-4 增加 Validation-driven Plan Repair

架构原因：

`TaskGraphValidator` 当前主要做结构校验。随着 manifest schema 完整化，validator 应该能判断 plan 是否满足 schema、权限、审批、依赖和业务目标基本约束，并把错误反馈给 deterministic repair 或 LLM repair。

下一步行动：

1. 增加 schema-aware dependency validation。
2. 判断 side-effect execution 前是否有 proposal/preview/approval 边界。
3. 区分 deterministic repair 和 LLM repair：
   - deterministic repair：修重复 ID、非法依赖、缺少审批边界等明确错误。
   - LLM repair：对遗漏节点、目标不满足、schema 不匹配进行重规划。
4. metadata 区分 original planner output、validation result、deterministic repair、LLM repair。
5. 不可修复错误必须进入 ask user 或 failed，不允许静默执行。

完成标准：

- 错误 plan 不会静默执行。
- 常见 plan 错误能自动修复。
- 修复过程可追踪、可 eval。

### P1-5 升级 ObservationEvaluator 和 Recovery / Replan Lineage

架构原因：

复杂 agent 的可靠性不取决于一次工具是否成功，而取决于系统能否判断结果是否充分、grounded、可用于下游。当前 evaluator 规则较粗，replan lineage 也不够稳定。

下一步行动：

1. evaluator 升级为 schema/evidence/workspace-ref aware。
2. groundedness 至少检查：
   - evidence source
   - citation
   - artifact id
   - upstream observation id
   - workspace path
3. 明确 `usable`、`sufficient`、`needsFallback`、`needsReplan` 的策略边界。
4. replan 增加 graph lineage：
   - `previous_graph_id`
   - `new_graph_id`
   - `reason`
   - `carried_observations`
   - `superseded_nodes`
5. replan 后稳定合并已完成证据和 continuation graph。

完成标准：

- 工具成功但证据不足时，不会被当作成功完成。
- replan 能保留已完成证据，不会重跑整个任务或丢失上下文。
- metadata 能解释为什么 fallback / ask user / replan。

### P1-6 引入 Capability-scoped Re-Act 执行模型

架构原因：

并非所有能力都适合一次性确定性工具调用。表格查询、规则判断、RAG 检索、参数修复等能力需要有限步数的 observe-reason-act 循环。但这个循环必须被 capability contract、tool allowlist、budget、trace、policy 约束，不能退回粗粒度业务 agent。

下一步行动：

1. 在 manifest / descriptor 中增加 `executionMode`：
   - `deterministic`
   - `react`
2. 新增 `ReactCapabilityRuntime`，提供有限循环：

```text
inputs
  -> reason
  -> choose allowed tool
  -> call tool
  -> observe
  -> repair / continue / finish
  -> emit Observation
```

3. 增加 execution budget：max steps、timeout、token/cost。
4. 所有中间 tool call 写 trace 和 workspace。
5. 优先改造：
   - `spreadsheet_query_product`
   - `activity_rule_check`
   - `rule_inquiry`
6. `enrollment_execute` 等高风险副作用能力默认保持 deterministic。

完成标准：

- 可修复工具错误先在 capability 内局部恢复。
- Re-Act provider 仍输出统一 observation。
- 内部循环不能绕过 policy、HITL、schema 和 audit。

## P2：证据层、评估和领域能力质量

### P2-1 升级 RAG / Embedding 为真实证据层

架构原因：

规则判断、活动解释、历史决策复用都依赖可追踪证据。RAG 不能只是 agent 内部工具字符串，应成为 observation evidence 和 workspace artifact 的来源。

下一步行动：

1. 接入真实 embedding model。
2. 设计 chunk schema：
   - tenant_id
   - document_id
   - chunk_id
   - title
   - content
   - source_uri
   - version
   - effective_from / effective_to
   - metadata
   - embedding
3. 检索结果包含 score、citation、version、source。
4. 增加 domain filter：rule、promotion、enrollment、case、risk、metric。
5. 检索结果写入 observation evidence 和 workspace refs。
6. 增加 groundedness eval。

完成标准：

- 规则类回答能说明证据来源。
- 过期知识不会被用于当前决策。
- 检索质量可以被离线评估。

### P2-2 升级 Eval Harness 为架构护栏

架构原因：

架构优先的迭代必须有 eval 护栏，否则很容易在 prompt、provider 或 manifest 调整中退回关键词路由、单 agent 接管或绕过 HITL。

下一步行动：

1. eval case schema 改为结构化 YAML/JSON。
2. 支持断言：
   - expected capabilities set
   - expected dependency edges
   - expected parallel groups
   - forbidden direct side effects
   - expected waiting_for_user fields
   - expected waiting_for_approval before execution
   - expected fallback / replan
   - expected citations / grounded evidence
   - expected validation warnings / repairs
   - expected observation evaluation results
   - expected workspace refs
   - expected todo state
3. 增加中文自由表达、顺序打乱、多目标混合案例。
4. 增加 regression suite，防止新增能力导致架构退化。

完成标准：

- 架构级退化能被测试发现。
- planner、manifest、skill、provider 的变化有可量化反馈。

### P2-3 提升 Provider 业务质量

架构原因：

Provider 质量应该建立在 P0/P1 的运行时和契约之上。否则 provider 越强，越容易把逻辑私有化，破坏统一 harness。

下一步行动：

1. `RuleInquiryCapabilityProvider` 增加 evidence-aware 输出约束。
2. `activity_rule_check` 接入真实规则/RAG provider。
3. `copywriting` 接入 LLM provider，并基于 upstream observations 生成内容。
4. `spreadsheet_query_product` 增强表格解析、列类型推断、商品 ID 精确匹配和别名处理。
5. `enrollment_execute` 接入真实业务 API 前，先完善 dry-run、diff、rollback/compensation 和幂等模型。
6. 所有 provider 输出统一 observation schema。

完成标准：

- 替换 provider 不需要改变 planner 和 harness 主流程。
- provider 质量提升不引入隐形路由、隐形状态或绕过 observation 的输出。

### P2-4 完善 Trace、Audit 和 Replay

架构原因：

复杂 agent 最终必须能解释和复盘：为什么规划、为什么调用某能力、证据是什么、哪里暂停、谁确认、哪个操作产生副作用。

下一步行动：

1. 为每个 model call、capability call、tool call、observation commit 记录统一 trace id。
2. pending action、operation record、observation、workspace artifact 双向引用。
3. metadata 区分：
   - planner output
   - validation result
   - execution trace
   - recovery trace
   - HITL trace
   - graph lineage
   - workspace refs
4. audit 记录 side-effect boundary 的所有状态迁移。
5. 增加 trace replay 所需的最小数据结构。

完成标准：

- 可以通过一次 response metadata 还原主要决策路径。
- 副作用操作可以被审计和排查。

## P3：体验、工程治理和前端工作台

### P3-1 AnswerSynthesizer

当前最终回答主要拼接 observation summary。复杂任务需要更结构化的综合回答。

下一步行动：

1. 新增 `AnswerSynthesizer`。
2. 支持 final answer、clarification question、approval request、partial progress summary、failure explanation。
3. 基于 answerStrategy、todos、observations、evaluations、visibleObjects、workspace refs 生成回答。
4. 输出已完成事项、关键证据、风险限制、等待用户确认/补充内容、下游计划。

完成标准：

- 多节点任务的回答对用户可读，而不是内部 observation 串联。

### P3-2 并发、预算和取消控制

当前使用默认 `CompletableFuture.supplyAsync`，缺少显式 executor、超时、取消和预算。

下一步行动：

1. 为 capability execution 配置专用 executor。
2. 增加 node timeout、retry budget、cost budget。
3. 支持取消 paused / obsolete graph。
4. 并行节点写 session 时统一在主线程 commit observation。

完成标准：

- 单个 provider 卡住不会拖死整个会话。
- 并发执行可控、可审计、可取消。

### P3-3 前端/流式工作台

deepagents 的产品形态会流式展示 subagent、todos、sandbox、HITL。本项目已有 metadata，但还没有变成深度 agent 工作台体验。

下一步行动：

1. SSE 输出 todo 状态变化。
2. SSE 输出 workspace artifact refs。
3. 展示 task graph、current node、waiting_for_user、waiting_for_approval。
4. 展示 sub-agent invocation summary 和 refs。
5. 展示 evidence/citation 和 approval diff。

完成标准：

- 用户能看见复杂任务正在怎么推进。
- 审批前能看到清晰 preview、diff、证据和风险。

### P3-4 Context Runtime 持久化、摘要和预算治理

架构原因：

Workspace 和 refs 已经建立，context budget middleware 也已接入，但当前实现仍偏内存和确定性归档。它不再阻塞运行时地基成立，却会影响长期会话、生产部署和成本控制。

下一步行动：

1. 将 `AgentWorkspace` backend 从内存实现扩展到数据库或对象存储。
2. `ContextBudgetMiddleware` 接入 token-aware budget，而不是只按字符数记录。
3. 对过长 conversation history 做摘要 + 原文归档，摘要中保留 workspace path。
4. context overflow 时支持压缩后重试 planner/model call。
5. 扩展 workspace read/search 到更多只读 provider，但保持写入仍由 runtime/middleware 控制。

完成标准：

- 长会话跨进程可恢复。
- context budget 触发后有确定性压缩和重试策略。
- provider 可以通过 refs 找证据，但不能随意写 workspace 或绕过 runtime。

## 建议迭代顺序

第一阶段：剩余运行时地基。

1. `PlanMemory` / `TodoItem` 与 TaskGraph 双向引用。
2. `ToolPermissionMiddleware` 统一 side-effect / approval / operation lineage 边界。
3. `delegate_task` + `general_purpose_agent`，把 sub-agent 用作受控 context isolation。

第二阶段：契约和知识包。

1. `CapabilityManifestDescriptor` 与 `SkillPackage` 分层。
2. 标准 YAML parser + 字段级 schema。
3. `CapabilityManifestValidator`。
4. `ObservationContractValidator`。
5. `SkillKnowledgeLoader` + progressive disclosure。

第三阶段：复杂任务稳定性。

1. validation-driven deterministic repair。
2. validation-driven LLM repair。
3. schema/evidence aware `ObservationEvaluator`。
4. graph lineage 和 replan 合并策略。
5. `ReactCapabilityRuntime`，先改造 `spreadsheet_query_product`、`activity_rule_check`、`rule_inquiry`。

第四阶段：证据和评估。

1. RAG / embedding / citation 升级。
2. eval harness 结构化断言。
3. trace / audit / replay 完善。
4. provider 真实业务质量提升。

第五阶段：用户体验和工程治理。

1. `AnswerSynthesizer`。
2. 执行预算、并发、取消。
3. 前端流式 agent 工作台。
4. workspace 持久化、token-aware summarization、context overflow retry。

## 每次改动的判断标准

未来任何架构或功能改动，都应回答以下问题：

1. 是否减少了代码层自然语言关键词路由？
2. 是否让 planner 更清楚地看到能力空间？
3. 是否让 context 从 prompt 堆叠升级为可检索、可恢复的工作记忆？
4. 是否让 task graph 更能表达依赖、并行、暂停和恢复？
5. 是否让 todo / plan memory 更好地支撑长任务推进？
6. 是否让 sub-agent 成为受控的 context isolation 工具，而不是新的流程中心？
7. 是否把副作用更牢地关进 capability/tool-level HITL 和 policy 边界？
8. 是否让 observation 更可评估、更 grounded、更可复用？
9. 是否增加了 trace、audit、eval、workspace refs 或 replay 能力？
10. 是否能在用户换说法、换顺序、混合多个目标时仍然成立？

如果一个改动不能改善以上任意一项，或者让其中某项倒退，应谨慎合入。

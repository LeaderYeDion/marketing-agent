# Marketing Agent Next TODO

## 文档定位

`project_goal.md` 定义项目的终极蓝图和架构原则；本文档定义当前实现距离蓝图的主要缺口，以及未来迭代时可直接执行的行动路径。

本文档不关注早期开发阶段可接受的细枝末节，也不优先讨论横向扩展能力，而是聚焦会影响终极目标的架构问题：

- 意图识别是否真正由 LLM planner 承担。
- 任务拆解是否以 task graph 为第一等对象。
- 能力是否通过 capability catalog 被清晰暴露、组合和治理。
- RAG / embedding 是否能支撑 grounded 决策。
- 主 agent、sub-agent、capability provider 之间的职责边界是否清晰。
- 高风险动作是否被 harness 和 HITL 确定性控制。
- 失败后是否能观测、恢复、续跑和重新规划。

## 终极目标摘要

项目最终要演进为一个营销领域通用智能体运行时，而不是固定工作流或关键词路由系统。

目标状态下，用户只需要用自然语言表达营销目标，系统应能：

1. 理解用户自由表达的复合目标。
2. 自主拆解为结构化 task graph。
3. 从 capability catalog 中选择和组合能力。
4. 根据 DAG 依赖执行可并行或有依赖的复杂任务。
5. 将每个执行结果转为 observation。
6. 基于 observation 判断继续、追问、fallback、暂停、重试或重新规划。
7. 在高风险副作用前进入人类确认边界。
8. 在 memory、trace、audit、metadata 和 eval 中留下可恢复、可解释、可评估的过程。

## 当前总体判断

当前项目方向正确，已经具备 TaskPlanner、TaskGraph、CapabilityRegistry、CapabilityProvider、Observation、RecoveryPolicyEngine、PendingActionStateMachine、审计和遥测等关键骨架。

但当前实现仍处于“harness 骨架 + 粗粒度 sub-agent 能力适配”的阶段，距离终极目标还有几个架构级缺口：

- 高风险动作的强制拦截还没有完全上移到 harness。
- 业务 sub-agent 仍承载过多流程理解和工具编排职责。
- capability catalog 还不足以让 planner 稳定推理输入输出、依赖和组合关系。
- RAG / embedding 当前更像占位实现，尚不足以支撑真实 grounded 决策。
- recovery 主要是局部 retry/fallback，尚未形成 observation -> evaluation -> re-plan 闭环。
- 用户补充信息后的原图续跑能力还不完整。
- eval 尚未真正约束 planner、DAG、HITL、observation 和恢复质量。

## 优先级说明

- P0：架构方向或安全边界问题。若不解决，未来能力越多，系统越容易退回固定 workflow 或 sub-agent 孤岛。
- P1：架构不变但核心实现需要大改。若不解决，系统可以演示，但难以稳定支撑复杂任务。
- P2：运行质量、可评估性和可演进性问题。若不解决，迭代会越来越难判断是否变好。
- P3：体验、工程完善和扩展性问题。重要，但可以在核心闭环稳定后推进。

## P0 待办：必须优先处理的架构边界

### P0-1 将高风险动作强制拦截上移到 Harness

当前问题：

`RiskPolicyEngine` 已能判断能力是否需要审批，但 `MarketingHarness` 目前主要记录风险评估，仍会直接调用 provider。真正禁止未确认执行的逻辑主要依赖 `ActivityEnrollAgent` 内部 prompt 和工具判断。这会让高风险边界依赖 sub-agent 自律，而不是 harness 的确定性策略。

目标状态：

任何 `sideEffects=true` 或 `requiresHumanApproval=true` 的 capability，在没有合法 pending action approval 前，都不能进入真实副作用执行路径。模型和 sub-agent 只能产生 action proposal / confirmation card，真实执行必须由 `PendingActionStateMachine` 迁移后续跑。

行动路径：

1. 在 `MarketingHarness.executePlannedGraph` 中，在 provider 执行前根据 `RiskAssessment.requiresApproval` 做强制分流。
2. 未确认的高风险节点只能执行“prepare/propose”类能力，或直接生成 `waiting_for_approval` observation。
3. 将“已确认”定义为来自 `PendingActionStateMachine.approve` 的状态迁移，而不是普通输入里的 `confirmed=true`。
4. pending action payload 中必须包含 `task_graph_id`、`task_node_id`、`capability_name`、幂等键和待执行 diff。
5. approval 续跑时只允许执行与 pending action 绑定的 capability 和 payload。
6. 增加 eval：高风险请求不得直接产生 executed operation；确认后才能执行。

完成标准：

- 任意 sub-agent 即使错误调用执行工具，也无法绕过 harness 边界。
- 所有副作用都有 pending action、audit、idempotency key 和状态机记录。

### P0-2 将 Sub-agent 从“流程中心”降级为 Capability Provider

当前问题：

`activity_enroll_agent` 内部同时承担自然语言理解、Excel 查询、报名预览、确认卡生成和确认后执行等职责。上层 task graph 只能看到一个粗粒度 `activity_enroll` 节点，无法稳定组合、并行、恢复或替换其中的子步骤。

目标状态：

task graph 是复杂任务的中心表达。sub-agent 可以作为某些 capability 的执行后端，但不应该私有化关键业务流程。

行动路径：

1. 将 `activity_enroll` 拆分为更细 capability，例如：
   - `spreadsheet_summarize`
   - `spreadsheet_query_product`
   - `activity_rule_check`
   - `enrollment_preview_create`
   - `enrollment_execute`
   - `notification_copywriting`
2. `activity_enroll_agent` 可暂时保留，但应改为这些 capability 的 provider 或 adapter，而不是端到端流程 owner。
3. planner 负责把用户复合目标拆成多个节点，harness 负责调度依赖。
4. 子能力之间只能通过 observation、artifact、visible object 和 state patch 交换信息。
5. 新增组合型 golden cases，要求同一用户目标必须产生多节点 task graph，而不是一个大 agent 节点。

完成标准：

- 典型复合任务能在 metadata 中看到明确的多节点 DAG。
- Excel 查询失败时可以只恢复查询节点，而不是整个报名 agent 失败。
- 文案生成可以依赖报名预览 observation，而不依赖某个 sub-agent 私有状态。

## P1 待办：核心实现需要大改

### P1-1 强化 Capability Manifest 和 Schema

当前问题：

当前 capability manifest 已包含 summary、requiredInputs、permissions、risk、composableWith 和 fallback，但输入输出契约仍是字符串列表，preconditions/postconditions 没有充分暴露给 planner，也缺少字段级 schema 和 artifact 契约。

目标状态：

capability catalog 应成为 planner 可推理的能力地图，而不是能力名列表。

行动路径：

1. 为每个 capability 增加结构化 input schema 和 output schema。
2. 将 preconditions、postconditions、side effects、risk policy、fallback policy 纳入 `CapabilityDescriptor`。
3. 区分 capability 类型：
   - read-only query
   - transformation
   - proposal/action planning
   - side-effect execution
4. 明确 observation 输出字段，例如 evidence、artifacts、visibleObjects、missingInputs、confidence。
5. planner prompt 中暴露完整能力契约，而不是只暴露 required/risk/provider。
6. 增加 manifest validator，校验 provider 是否能满足 schema 和风险声明。

完成标准：

- planner 能基于 schema 判断上游输出是否能满足下游输入。
- 新增 capability 时不需要改 harness 路由代码。

### P1-2 增加 Plan Validation 和 Plan Repair

当前问题：

`TaskPlanner` 会校验 capability 是否存在并移除非法依赖，但还没有真正判断 DAG 是否满足业务目标、是否遗漏必要节点、是否错误跳过 HITL、是否存在输入输出不匹配。

目标状态：

planner 产出的 task graph 必须经过结构校验和语义校验。可修复问题应进入 plan repair，而不是直接 fallback 到单个安全能力。

行动路径：

1. 增加 `TaskGraphValidator`：
   - capability 是否存在。
   - dependency 是否有效且无环。
   - 高风险执行节点前是否存在 approval/proposal 节点。
   - required inputs 是否来自 request、context 或上游 observation。
   - 下游节点是否依赖必要上游证据。
2. 增加 deterministic repair：
   - 补齐缺失依赖。
   - 插入 clarification 节点。
   - 将未确认 side-effect 节点改为 proposal 节点。
3. 增加 LLM based plan repair：
   - 将 validation errors、capability catalog、原始用户目标交给模型重新生成计划。
4. metadata 中记录 validation errors、repair trace 和最终计划来源。

完成标准：

- 错误计划不会静默执行。
- 常见可修复错误能自动修复。
- 不可修复错误能明确追问用户或返回计划失败原因。

### P1-3 支持 Waiting-for-user 后续跑原 Task Graph

当前问题：

approval feedback 已有续跑路径，但普通缺失信息的 `waiting_for_user` 场景主要是暂停并回答缺失输入。用户补充后容易重新规划，而不是回到原 task graph 的未完成节点。

目标状态：

用户补充缺失信息后，系统应恢复原 task graph，将补充信息绑定到等待节点，并继续执行下游任务。

行动路径：

1. 在 `waiting_for_user` observation 中保存 `task_graph_id`、`task_node_id`、missing input keys 和 expected schema。
2. 在 session state 中保存 paused graph。
3. 用户下一轮输入进入 `ContextAssembler` 后，先检测是否有可恢复的 waiting node。
4. 使用 LLM 或 deterministic extractor 将用户补充信息映射到 missing inputs。
5. 恢复该节点为 pending，并继续执行原图。
6. 若补充信息仍不足，更新同一个 waiting node，不创建新项目。

完成标准：

- 多轮补充不会丢失原计划。
- 下游节点仍能依赖补充后的上游 observation。

### P1-4 建立 Observation Evaluation 和 Re-plan 闭环

当前问题：

当前 `RecoveryPolicyEngine` 主要处理 retry、fallback 和 ask user。系统还不能判断“工具成功但证据不足”“计划本身错误”“需要改用另一组能力”等复杂情况。

目标状态：

每个 observation 都应进入 evaluation，判断是否 sufficient、grounded、usable、needs_replan。

行动路径：

1. 增加 `ObservationEvaluator`：
   - 是否满足 completionCriteria。
   - evidence 是否充分。
   - missingInputs 是否可由上下文补齐。
   - 是否需要 fallback。
   - 是否需要 re-plan。
2. 将 `RecoveryPolicyEngine` 从简单规则升级为 policy + evaluator 组合。
3. 支持 LLM based re-plan：
   - 输入原始目标、当前 task graph、已有 observations、失败原因。
   - 输出 repaired graph 或 continuation graph。
4. re-plan 后保留旧 graph 和新 graph 的 trace 关系。
5. eval 覆盖“工具返回成功但内容不足”的场景。

完成标准：

- 失败恢复不只依赖异常 retryable。
- observation 质量能影响后续路径。

## P2 待办：RAG、记忆、评估与可观测性

### P2-1 将 RAG / Embedding 从占位实现升级为可用知识体系

当前问题：

默认 RAG 是内置少量文档加关键词包含排序；pgvector profile 下的 embedding 是 hashing 向量。这只能用于早期演示，不能支撑真实规则、活动、商品、案例和历史决策检索。

目标状态：

RAG 应提供可追踪、可引用、可评估的证据层，服务于规则判断、活动解释、历史决策复用和 planner 上下文构建。

行动路径：

1. 接入真实 embedding model。
2. 设计知识 chunk schema：
   - tenant_id
   - document_id
   - chunk_id
   - title
   - content
   - source_uri
   - version
   - effective_from/effective_to
   - metadata
   - embedding
3. 检索结果必须包含 score、citation、version 和 source。
4. 增加 domain filter，例如 rule、promotion、enrollment、case、risk、metric。
5. 将检索结果转成 observation evidence，而不是只作为 sub-agent 内部工具字符串。
6. 增加 groundedness eval。

完成标准：

- 规则类回答能说明证据来源。
- 过期知识不会被用于当前决策。
- 检索质量可以被离线评估。

### P2-2 强化 ContextAssembler 和 Memory

当前问题：

当前 compressed context 只包含用户目标、能力简表、visible object id、pending action id 和 state keys。planner 难以看到历史判断依据、未完成任务、artifact 摘要和关键 observation。

目标状态：

上下文应是可推理的工作记忆，而不是 state key 列表。

行动路径：

1. 在 compressed context 中加入：
   - 最近关键 observation 摘要。
   - active task graph 状态。
   - pending/waiting nodes。
   - 可用 artifacts 摘要。
   - visible object 的标题、状态和摘要。
   - pending action 的风险和动作摘要。
2. 区分短期会话记忆、项目级记忆、artifact memory、decision memory。
3. 增加 memory compaction 策略，避免上下文无限增长。
4. 给 planner 和 sub-agent 提供不同视角的 context。

完成标准：

- 多轮项目推进时，模型能知道“我们做到哪一步了”。
- 用户追问或补充信息时，不需要重复输入全部背景。

### P2-3 升级 Eval Harness

当前问题：

当前 golden cases 主要检查答案文本和 metadata 中是否包含能力名。它还不能有效验证 DAG 质量、HITL 边界、并行识别、恢复路径和 groundedness。

目标状态：

eval 应成为架构演进的护栏，覆盖 planner、runtime、recovery、HITL、RAG 和 observation。

行动路径：

1. 将 eval case schema 扩展为结构化 YAML/JSON。
2. 支持断言：
   - expected capabilities set。
   - expected dependency edges。
   - expected parallel groups。
   - forbidden direct side effects。
   - expected waiting_for_user fields。
   - expected waiting_for_approval before execution。
   - expected fallback or re-plan。
   - expected citations / grounded evidence。
3. 修正当前 `minTaskNodes` 对 metadata map 的读取问题。
4. 增加中文自由表达、顺序打乱、多目标混合的案例。
5. 增加 regression suite，避免新增业务能力时退回关键词路由。

完成标准：

- 架构级退化能被测试发现。
- planner prompt 或 capability manifest 调整有可量化反馈。

### P2-4 完善 Trace、Audit 和 Runtime Metadata

当前问题：

当前已有 trace、audit、metadata，但还需要进一步统一 run、task graph、node、observation、pending action、operation 之间的关联。

目标状态：

任何一次用户请求都能完整追踪：模型为什么规划、执行了什么、证据是什么、哪里暂停、谁确认、哪个操作产生副作用。

行动路径：

1. 为每个 node execution 记录统一 trace id。
2. pending action、operation record、observation 之间保存双向引用。
3. metadata 中明确区分：
   - planner output
   - validation result
   - execution trace
   - recovery trace
   - HITL trace
4. audit 记录 side-effect boundary 的所有状态迁移。
5. 增加 trace replay 所需的最小数据结构。

完成标准：

- 可以通过一次 response metadata 还原主要决策路径。
- 副作用操作可以被审计和排查。

## P3 待办：工程完善和体验优化

### P3-1 优化 Answer Synthesis

当前问题：

最终回答目前主要拼接 observation summary。对于复杂多节点任务，用户可能需要更结构化的总结、证据和下一步建议。

目标状态：

最终回答应基于 answerStrategy、observations 和 visibleObjects 生成，而不是简单拼接。

行动路径：

1. 增加 `AnswerSynthesizer`。
2. 支持不同响应模式：
   - final answer
   - clarification question
   - approval request
   - partial progress summary
   - failure explanation
3. 对复杂任务输出：
   - 已完成事项。
   - 关键证据。
   - 风险或限制。
   - 等待用户确认/补充的内容。
   - 下游计划。

完成标准：

- 多节点任务的回答对用户可读，而不是内部 observation 串联。

### P3-2 Provider 执行模型和并发控制完善

当前问题：

当前使用默认 `CompletableFuture.supplyAsync`，没有显式 executor、超时、取消、预算或并发上限。

目标状态：

runtime 应能控制每个 capability 的执行预算、超时、并发和失败影响范围。

行动路径：

1. 为 capability execution 配置专用 executor。
2. 增加 node timeout、retry budget、cost budget。
3. 支持取消 paused/obsolete graph。
4. 并行节点写 session 时统一在主线程 commit observation，避免共享状态风险。

完成标准：

- 并发执行可控。
- 单个 provider 卡住不会拖死整个会话。

### P3-3 渐进替换早期占位能力

当前问题：

部分能力仍是演示实现，例如 copywriting 是模板生成，报名执行是模拟 operation。

目标状态：

保持架构稳定的前提下，逐步替换为真实 provider。

行动路径：

1. copywriting 接入 LLM provider，并基于 upstream observations 生成内容。
2. enrollment execution 接入真实业务 API 前，先完善 dry-run、diff 和 rollback/compensation 设计。
3. FileTools 增加结构化表格查询、列类型推断、商品 ID 精确匹配。
4. 所有 provider 输出统一 observation schema。

完成标准：

- 替换 provider 不需要改变 planner 和 harness 主流程。

## 建议迭代顺序

第一阶段：收紧安全和架构中心。

1. P0-1 Harness 强制 HITL 边界。
2. P0-2 拆分粗粒度 sub-agent 能力。
3. P1-1 capability schema 和 manifest 升级。

第二阶段：让 task graph 真正可靠。

1. P1-2 plan validation / repair。
2. P1-3 waiting_for_user 原图续跑。
3. P1-4 observation evaluation / re-plan。

第三阶段：让知识和记忆可支撑真实决策。

1. P2-1 RAG / embedding 升级。
2. P2-2 context / memory 升级。
3. P2-4 trace / audit 关联完善。

第四阶段：让项目可持续演进。

1. P2-3 eval harness 升级。
2. P3-1 answer synthesis。
3. P3-2 并发和执行预算。
4. P3-3 替换占位 provider。

## 每次改动的判断标准

未来任何架构或功能改动，都应回答以下问题：

1. 是否减少了代码层自然语言关键词路由？
2. 是否让 planner 更清楚地看到能力空间？
3. 是否让 task graph 更能表达依赖、并行、暂停和恢复？
4. 是否让 sub-agent 更像 provider，而不是系统中心？
5. 是否把副作用更牢地关进 HITL 和 policy 边界？
6. 是否让 observation 更可评估、更 grounded、更可复用？
7. 是否能在用户换说法、换顺序、混合多个目标时仍然成立？
8. 是否增加了 trace、audit、eval 或 replay 能力？

如果一个改动不能改善以上任意一项，或者让其中某项倒退，应谨慎合入。

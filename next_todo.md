# Marketing Agent Next TODO

## 文档定位

`project_goal.md` 定义项目的终极蓝图和架构原则；本文档记录当前实现距离蓝图的真实差距，以及后续迭代时可直接执行的行动路径。

本文档已基于当前代码重新校准。当前仓库已经完成了一轮 P0/P1 方向的架构改造，但不能简单判定 P0/P1 全部完成。更准确的结论是：

- P0-1 已经完成核心强制拦截路径，但审计、pending action 绑定完整性和覆盖面仍需收口。
- P0-2 已经拆出了细粒度报名 capability，但旧 `activity_enroll_agent` 和通用 `SubAgentCapabilityProvider` 仍是架构退化口，尚未彻底降级或移除。
- P1 已经具备 schema 字段、validator、waiting_for_user 续跑和 observation evaluator/re-plan 雏形，但多数仍是第一版骨架，未达到“稳定支撑复杂任务”的完成标准。

本文档的判断依据包括：

- `project_goal.md`
- 当前 `next_todo.md` 历史内容
- 当前实现中的 `MarketingHarness`
- `TaskPlanner`
- `TaskGraphValidator`
- `CapabilityDescriptor` / `SkillDescriptor` / `SkillRegistry`
- `ActivityEnrollmentCapabilityProvider`
- `SubAgentCapabilityProvider`
- `ObservationEvaluator`
- `RecoveryPolicyEngine`
- 当前 skill manifests 和应用级测试

## 终极目标摘要

项目最终要演进为一个营销领域通用智能体运行时，而不是固定工作流、关键词路由系统或 sub-agent 委派系统。

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

当前项目方向正确，并且相比上一版已经明显前进：

- `MarketingHarness` 已在 provider 执行前根据 `RiskPolicyEngine` 强制拦截需要审批的高风险 capability。
- `RiskPolicyEngine` 已不再把普通输入里的 `confirmed=true` 当成授权依据，而是依赖 `pending_action_approved`。
- `PendingAction` 和 feedback 续跑逻辑已保护 `task_graph_id`、`task_node_id`、`capability_name`、`idempotency_key` 等绑定字段。
- 已新增 `ActivityEnrollmentCapabilityProvider`，并把报名流程拆成 `spreadsheet_summarize`、`spreadsheet_query_product`、`activity_rule_check`、`enrollment_preview_create`、`enrollment_execute` 等细粒度 capability。
- `CapabilityDescriptor` 已暴露 `capabilityType`、`inputSchema`、`outputSchema`、`preconditions`、`postconditions`。
- `TaskGraphValidator` 已提供基础结构校验和 deterministic repair。
- `MarketingHarness` 已支持 `waiting_for_user` observation，并能在用户补充变量后恢复原 task graph 的等待节点。
- `ObservationEvaluator` 和 `RecoveryPolicyEngine` 已形成 observation evaluation / fallback / re-plan 的第一版闭环。
- metadata 中已经包含 task graph、validation、observations、observation evaluations、risk assessments、harness trace 和 capability catalog。
- 应用级测试已覆盖高风险直执拦截、细粒度 capability catalog 暴露、waiting_for_user 原图续跑、非法依赖修复。

但当前仍不能判定为最终形态下的 P0/P1 完成。主要原因：

- `activity_enroll` 旧粗粒度 capability 仍在 catalog 中，`ActivityEnrollAgent` 仍存在，且 `SubAgentCapabilityProvider` 仍可作为任意 registered sub-agent 的通用 adapter。
- `SubAgentCapabilityProvider` 当前仍是“万能 agent-backed capability 入口”，没有显式 provider mode、schema enforcement 或 agent-backed 能力边界约束。
- `enrollment_preview_create` 创建的 pending action 会绑定 `enrollment_execute`，但当前 payload 中 `task_node_id` 为空，说明“approval 续跑只执行原 pending action 绑定节点”的数据完整性仍不够严。
- `CapabilityDescriptor.inputSchema/outputSchema` 目前主要由 requiredInputs/outputContract 默认生成，manifest 尚未支持真正字段级 schema。
- `TaskGraphValidator` 主要是结构校验，还不能判断 DAG 是否满足业务目标、是否遗漏必要节点、上下游 schema 是否真正匹配，也没有把 validation errors 交给 LLM 做 plan repair。
- `waiting_for_user` 续跑主要依赖 request variables 和单字段 query 填充；多字段、自然语言补充、仍缺信息时更新同一 waiting node 的能力还不完整。
- `ObservationEvaluator` 仍是简单规则，groundedness、充分性和 hallucination 检查都很粗糙。
- re-plan 只有雏形，尚未形成可靠的 old graph / new graph trace 关系和 eval 约束。
- eval harness 仍以文本和 metadata 包含检查为主，不能系统性约束 DAG、HITL、recovery、groundedness。

## 优先级说明

- P0：架构方向或安全边界问题。若不解决，未来能力越多，系统越容易退回固定 workflow 或 sub-agent 孤岛。
- P1：架构不变但核心实现需要大改。若不解决，系统可以演示，但难以稳定支撑复杂任务。
- P2：运行质量、可评估性和可演进性问题。若不解决，迭代会越来越难判断是否变好。
- P3：体验、工程完善和扩展性问题。重要，但可以在核心闭环稳定后推进。

## P0 状态：部分完成，仍需收口

### P0-1 将高风险动作强制拦截上移到 Harness

当前状态：核心路径已完成，但未完全达到完成标准。

已完成：

1. `MarketingHarness.executePlannedGraph` 已在 provider 执行前评估风险。
2. `RiskAssessment.requiresApproval=true` 时，harness 会直接生成 `waiting_for_approval` observation，不调用 provider。
3. `confirmed=true` 不再被视为合法授权，`RiskPolicyEngine` 只认可 `pending_action_approved=true`。
4. `PendingActionStateMachine.approve` 后，feedback 续跑由 harness 注入 `pending_action_approved=true`。
5. `PendingAction.withEditedPayload` 和 `MarketingHarness.feedbackInputs` 已禁止用户编辑覆盖 `task_graph_id`、`task_node_id`、`capability_name`、`idempotency_key`、`approval_source`。
6. `enrollment_execute` provider 内部也会拒绝没有 `pending_action_approved=true` 的执行请求。
7. 应用级测试已覆盖 planner 直接选择 `enrollment_execute` 时不能绕过 harness。

仍未完成：

1. `enrollment_preview_create` 当前创建的 pending action payload 中 `task_node_id` 为空。它绑定了 `capability_name=enrollment_execute`，但没有绑定到一个明确 task node。
2. pending action、operation record、observation、audit 之间的双向关联仍不完整。
3. pending action 创建本身主要来自 `commitObservation`，还没有统一的 audit event 记录。
4. 状态机迁移已有 approved/rejected/edited 的 audit，但 executed、expired 等边界事件还不够完整。
5. 当前只对已有测试场景验证了 direct side-effect block，还缺少更系统的 eval，例如 sub-agent 错误产出执行卡、payload 篡改、重复确认、过期确认等。

下一步行动：

1. 将 `enrollment_preview_create` 改为创建明确的 continuation node，或在 pending action payload 中保存一个可恢复的 execution node descriptor，确保 `task_node_id` 非空。
2. 抽出统一 `PendingActionFactory` 或 harness 内部 pending-action builder，禁止 provider 自己随意拼接 pending payload。
3. 为 pending action created / approved / rejected / edited / expired / executed 全部写入 audit。
4. operation record、pending action、observation、task graph node 之间保存稳定引用。
5. 增加 eval：任何 `sideEffects=true` 或 `requiresHumanApproval=true` 的 capability 在未审批前不得产生 operation receipt。

完成标准：

- 任意 provider 或 sub-agent 即使错误尝试执行副作用，也无法绕过 harness 边界。
- 所有副作用都有 pending action、audit、idempotency key、operation record 和状态机记录。
- approval 续跑只能执行 pending action 绑定的 capability 和 payload，且绑定信息完整可追踪。

### P0-2 将 Sub-agent 从“流程中心”降级为 Capability Provider

当前状态：部分完成，但仍有明显历史遗留。

已完成：

1. 已新增细粒度 capability：
   - `spreadsheet_summarize`
   - `spreadsheet_query_product`
   - `activity_rule_check`
   - `enrollment_preview_create`
   - `enrollment_execute`
   - `notification_copywriting`
2. 已新增 `ActivityEnrollmentCapabilityProvider`，这些能力可作为 task graph 的独立节点执行。
3. `TaskPlanner` system message 已移除具体 capability 名称硬编码，改为根据 catalog 中的 description、schema、preconditions、postconditions、composableWith、fallbacks、sideEffects 和 approval requirement 做通用规划。
4. `copywriting_provider` 已支持 `notification_copywriting`。
5. 测试已验证细粒度 capability 暴露在 catalog 中。

仍未完成：

1. `activity_enroll` 仍在 `skills/index.txt` 中，仍可被 planner 选择。
2. `ActivityEnrollAgent` 仍存在，并保留端到端流程逻辑。
3. `SubAgentCapabilityProvider` 仍是通用 adapter：只要 manifest 的 `entryAgent` 是 registered sub-agent，它就会支持该 capability。这仍然给系统留下“把复杂任务丢回 sub-agent”的退路。
4. `rule_inquiry` 当前仍依赖 `InquiryAgent` + `SubAgentCapabilityProvider`，还没有迁移为明确的 `RuleInquiryCapabilityProvider`。
5. 对于 agent-backed capability，目前没有 schema validation、observation contract validation 或 provider mode 限制。

下一步行动：

1. 将 `rule_inquiry` 迁移到明确的 `RuleInquiryCapabilityProvider`。该 provider 内部可以调用 `InquiryAgent` 或 RAG/LLM，但 capability 边界必须由 provider 明确控制。
2. 将 `activity_enroll` 标记 deprecated，或从默认 catalog 移除，仅保留细粒度 capability。
3. 删除 `SubAgentCapabilityProvider`，或至少改名并收紧为显式 `agent-backed` provider mode，不允许任意 registered sub-agent 自动成为 capability provider。
4. 对所有 provider 输出加 observation contract validation。
5. 增加 eval：典型复合报名任务必须产生多节点 DAG，不能退回单个 `activity_enroll` 节点。

完成标准：

- 典型复合任务能在 metadata 中看到明确多节点 DAG。
- Excel 查询失败时可以只恢复查询节点，而不是整个报名 agent 失败。
- 文案生成依赖上游 observation，而不是某个 sub-agent 私有状态。
- 默认执行路径中不存在“任意 sub-agent 接管 capability”的万能入口。

## P1 状态：已有骨架，尚未完成

### P1-1 强化 Capability Manifest 和 Schema

当前状态：部分完成。

已完成：

1. `CapabilityDescriptor` 已包含：
   - `capabilityType`
   - `inputSchema`
   - `outputSchema`
   - `preconditions`
   - `postconditions`
2. `SkillDescriptor` 和 `SkillRegistry` 已支持读取 `capabilityType`、`preconditions`、`postconditions`。
3. `TaskPlanner` prompt 和 `ContextAssembler` compressed context 已暴露更完整的 capability contract。
4. metadata 中的 capability view 已包含 schema、type、preconditions、postconditions。

仍未完成：

1. manifest 目前仍没有真正字段级 `inputSchema` / `outputSchema` 解析。当前 schema 主要由 `requiredInputs` 和 `outputContract` 自动生成。
2. `SkillRegistry.parseDescriptor` 仍是简单 key/value + CSV 解析，不支持 YAML 嵌套结构。
3. `SkillRegistryValidator` 只验证 provider 是否存在、skill resource 是否存在、sub-agent requiredInputs 是否兼容；它不验证 schema、risk declaration、sideEffects、output contract。
4. provider 输出没有被 schema validator 校验。
5. artifact contract 仍未结构化。

下一步行动：

1. 升级 manifest 格式，支持字段级 input/output schema。
2. 用 YAML parser 替代当前手写 key/value parser。
3. 增加 `CapabilityManifestValidator`，验证 schema、risk、sideEffects、approval、provider support。
4. 增加 `ObservationContractValidator`，校验 provider 输出是否满足 capability output schema。
5. 将 artifact、evidence、visibleObjects、missingInputs、confidence 纳入 schema 契约。

完成标准：

- planner 能基于 schema 判断上游输出是否满足下游输入。
- 新增 capability 时不需要改 harness 路由代码。
- provider 输出不符合 manifest 时会被 runtime 或测试发现。

### P1-2 增加 Plan Validation 和 Plan Repair

当前状态：基础结构已完成，语义能力不足。

已完成：

1. 已新增 `TaskGraphValidator`。
2. 已支持：
   - null graph 检查。
   - duplicate node id repair。
   - unknown capability 移除并记录 error。
   - invalid dependency 移除并记录 warning/repair。
   - dependency cycle 检查并移除依赖。
   - side-effect node without upstream proposal warning。
   - required input not bound warning。
3. `TaskPlanner` 不再静默移除非法依赖，修复过程由 validator 留痕。
4. metadata 已包含 validation errors、warnings、repairs。
5. 测试已覆盖非法依赖修复。

仍未完成：

1. validator 还不能判断 DAG 是否满足用户业务目标。
2. validator 还不能判断是否遗漏必要节点，例如“执行报名前是否存在 preview/proposal node”。
3. validator 只 warning 高风险节点没有 upstream proposal，没有执行 deterministic repair。
4. required input 是否来自 request、context 或上游 observation 目前只是粗略判断，有依赖就放过。
5. 下游节点是否依赖必要上游证据还没有 schema 级验证。
6. validation errors 尚未进入 LLM based plan repair；当前 `TaskPlanner.replan` 只用于 observation recovery，不用于 plan validation repair。

下一步行动：

1. 增加 schema-aware dependency validation，判断下游 required input 是否可由上游 output schema 满足。
2. 对未确认 side-effect node 做 deterministic repair：插入 proposal/preview 节点，或将 execution node 改为 waiting_for_approval。
3. 增加 validation-driven LLM repair：把 validation errors、catalog、原始目标交给 planner 生成 repaired graph。
4. metadata 区分 original planner output、deterministic repair result、LLM repair result。
5. 增加 eval：错误计划不能静默执行，常见错误必须自动修复，不可修复错误必须明确追问或失败。

完成标准：

- 错误计划不会静默执行。
- 常见可修复错误能自动修复。
- 不可修复错误能明确追问用户或返回计划失败原因。
- DAG 质量不只停留在结构合法，还能约束关键业务依赖。

### P1-3 支持 Waiting-for-user 后续跑原 Task Graph

当前状态：第一版已完成，但还不稳。

已完成：

1. harness 会在执行前检查 capability required inputs。
2. 缺失输入时生成 `waiting_for_user` observation。
3. observation/statePatch 中包含 `task_graph_id`、`task_node_id`、`capability_name`、`missing_inputs`、`expected_schema`。
4. `ConversationSession.state.last_task_graph` 保存暂停图。
5. 下一轮请求如果同 conversation 中存在 `waiting_for_user` graph，会尝试恢复等待节点。
6. 如果用户通过 request variables 补齐缺失输入，原 node 会恢复为 pending 并继续执行。
7. 测试已覆盖通过变量补齐 `excel_file_path` 后恢复原图。

仍未完成：

1. 补充信息映射主要依赖 request variables；自然语言补充只支持单缺失字段时把 query 填进去。
2. 没有 LLM extractor 将用户自然语言映射到 missing inputs。
3. 如果补充信息仍不足，当前 `resumeWaitingGraphIfPossible` 返回 null，随后会进入重新规划路径，可能创建新图；这不符合“更新同一个 waiting node，不创建新项目”的目标。
4. 多 waiting node、多轮补充、部分补齐的处理还不完整。
5. 下游节点依赖补充后的 observation 目前依靠普通 observation summary enrichment，缺少结构化 artifact/input binding。

下一步行动：

1. 增加 `MissingInputExtractor`，支持 deterministic + LLM 两层提取。
2. 如果仍缺信息，不重新规划；更新同一个 waiting node 的 missing inputs 和 expected schema。
3. 支持多字段、多轮补充和部分补齐。
4. 补齐后生成结构化 observation/artifact，而不是只靠 summary。
5. 增加 eval：多轮补充不丢失原 task graph，下游节点能消费补齐后的结构化 observation。

完成标准：

- 多轮补充不会丢失原计划。
- 信息不足时不会创建新项目。
- 下游节点能依赖补充后的上游 observation。

### P1-4 建立 Observation Evaluation 和 Re-plan 闭环

当前状态：雏形已完成，但还不是可靠闭环。

已完成：

1. 已新增 `ObservationEvaluator`。
2. evaluation 会输出：
   - `sufficient`
   - `grounded`
   - `usable`
   - `needsFallback`
   - `needsReplan`
   - `reasons`
3. `RecoveryPolicyEngine` 已基于 evaluation 判断 fallback、ask_user、replan 或 none。
4. `TaskPlanner` 已新增 `replan(...)`。
5. `MarketingHarness.applyRecoveryIfNeeded` 已能在 recovery action 为 `replan` 时调用 planner 生成新 graph。
6. metadata 中已输出 observation evaluations。

仍未完成：

1. evaluator 的 groundedness 只是检查 evidence/artifacts/visibleObjects 是否为空，不能判断证据是否真实支持结论。
2. completionCriteria 只用 confidence 阈值粗略判断，没有语义或 schema 级检查。
3. “工具成功但内容不足”的场景没有充分测试。
4. re-plan 后旧 graph 和新 graph 的 trace 关系只在 harness trace 中粗略记录，没有持久化 graph lineage。
5. re-plan 没有合并已完成节点、未完成节点和新 continuation graph 的稳定策略。
6. re-plan 本身没有 eval 约束，可能生成同样不可用的图。

下一步行动：

1. 将 evaluator 升级为 schema-aware evaluator。
2. 增加 groundedness 检查，至少检查 evidence source、citation、artifact id、upstream observation id。
3. 明确 usable / sufficient / needs_replan 的策略边界。
4. 为 re-plan 增加 graph lineage：previous_graph_id、new_graph_id、reason、carried_observations。
5. 增加 eval：工具成功但 evidence 不足时必须 fallback、ask user 或 re-plan。

完成标准：

- 失败恢复不只依赖异常 retryable。
- observation 质量能影响后续路径。
- re-plan 能可靠保留已完成证据并生成 continuation graph。

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

当前 compressed context 已比早期多暴露了 capability schema/type，但工作记忆仍然偏薄。planner 仍难以稳定看到历史判断依据、未完成节点、artifact 摘要、关键 observation 和 graph lineage。

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
   - graph lineage 和 re-plan 历史。
2. 区分短期会话记忆、项目级记忆、artifact memory、decision memory。
3. 增加 memory compaction 策略，避免上下文无限增长。
4. 给 planner 和 provider/sub-agent 提供不同视角的 context。

完成标准：

- 多轮项目推进时，模型能知道“我们做到哪一步了”。
- 用户追问或补充信息时，不需要重复输入全部背景。

### P2-3 升级 Eval Harness

当前问题：

当前应用级测试已覆盖部分 P0/P1 行为，但 eval harness 本身仍主要检查答案文本和 metadata 中是否包含能力名。它还不能有效验证 DAG 质量、HITL 边界、并行识别、恢复路径和 groundedness。

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
   - expected validation warnings / repairs。
   - expected observation evaluation results。
3. 当前 `minTaskNodes` 对 metadata map 的读取问题已修正，但还需要覆盖更多结构化断言。
4. 增加中文自由表达、顺序打乱、多目标混合的案例。
5. 增加 regression suite，避免新增业务能力时退回关键词路由或 sub-agent 中心化。

完成标准：

- 架构级退化能被测试发现。
- planner prompt 或 capability manifest 调整有可量化反馈。

### P2-4 完善 Trace、Audit 和 Runtime Metadata

当前问题：

当前已有 trace、audit、metadata，并且 metadata 已包含 validation 和 observation evaluation。但 run、task graph、node、observation、pending action、operation 之间的稳定关联仍不完整。

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
   - graph lineage
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

最终回答应基于 answerStrategy、observations、observation evaluations 和 visibleObjects 生成，而不是简单拼接。

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

部分能力仍是演示实现，例如 copywriting 是模板生成，报名执行是模拟 operation，`activity_rule_check` 也只是占位式规则检查。

目标状态：

保持架构稳定的前提下，逐步替换为真实 provider。

行动路径：

1. copywriting 接入 LLM provider，并基于 upstream observations 生成内容。
2. enrollment execution 接入真实业务 API 前，先完善 dry-run、diff 和 rollback/compensation 设计。
3. FileTools 增加结构化表格查询、列类型推断、商品 ID 精确匹配。
4. `activity_rule_check` 接入真实规则/RAG provider。
5. 所有 provider 输出统一 observation schema。

完成标准：

- 替换 provider 不需要改变 planner 和 harness 主流程。

## 建议迭代顺序

第一阶段：收口 P0 遗留，彻底移除 sub-agent 中心化退路。

1. 修复 pending action payload 绑定完整性，特别是 `task_node_id` 为空的问题。
2. 建立统一 pending action builder 和完整 audit。
3. 将 `rule_inquiry` 迁出 `SubAgentCapabilityProvider`，实现明确 provider。
4. 将 `activity_enroll` 从默认 catalog 移除或标记 deprecated。
5. 删除或严格收紧 `SubAgentCapabilityProvider`。

第二阶段：补齐 P1 的真实 schema 和 plan repair。

1. manifest 支持字段级 schema。
2. provider 输出 schema validation。
3. schema-aware task graph validation。
4. validation-driven deterministic repair 和 LLM repair。
5. waiting_for_user 的 MissingInputExtractor。
6. observation evaluator 升级为 schema/evidence aware。

第三阶段：让知识、记忆和 trace 支撑真实决策。

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

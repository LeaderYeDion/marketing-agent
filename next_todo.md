# Marketing Agent Next TODO

## 文档定位

`project_goal.md` 定义项目终极蓝图和架构原则；本文档记录当前代码距离蓝图的真实差距，并给出后续可直接执行的迭代路径。

本文档基于当前最新代码重新评估，不沿用历史判断。当前项目已经从“单 agent / sub-agent 委派”明显推进到“LLM planner 生成 task graph，harness 负责约束、调度、观测、恢复和 HITL”的形态。P0 的主路径已基本收口，但系统距离营销通用智能体的终极形态仍有显著差距，主要集中在 schema、plan repair、长期记忆、真实证据层、eval harness 和 provider 质量。

当前判断依据包括：

- `project_goal.md`
- `MarketingHarness`
- `TaskPlanner`
- `TaskGraphValidator`
- `CapabilityDescriptor` / `SkillDescriptor` / `SkillRegistry`
- `ActivityEnrollmentCapabilityProvider`
- `RuleInquiryCapabilityProvider`
- `ObservationEvaluator`
- `RecoveryPolicyEngine`
- skill manifests、eval golden cases 和应用级测试

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

当前方向正确，并且 P0 主路径相比此前已经明显前进：

- `MarketingHarness` 已在 provider 执行前根据 `RiskPolicyEngine` 强制拦截需要审批的高风险 capability。
- `RiskPolicyEngine` 不再把普通输入里的 `confirmed=true` 当成授权依据，而是依赖 `pending_action_approved`。
- `PendingAction` 和 feedback 续跑逻辑已保护 `task_graph_id`、`task_node_id`、`capability_name`、`idempotency_key`、`approval_source` 等绑定字段。
- `MarketingHarness` 已抽出内部 pending action builder，创建 pending action 时会统一补齐和校验 `task_graph_id`、`task_node_id`、`capability_name`、`idempotency_key`，并记录 created / approved / rejected / edited / expired / executed 等审计事件。
- `enrollment_preview_create` 已不再创建空 `task_node_id`，而是生成 `node_x_approved_execution` 形式的可恢复执行节点绑定。
- 默认 `skills/index.txt` 已移除旧粗粒度 `activity_enroll`，当前 catalog 暴露的是细粒度报名能力。
- 已新增 `RuleInquiryCapabilityProvider`，`rule_inquiry` 不再通过通用 sub-agent adapter 暴露；`InquiryAgent` 退到 provider 内部实现细节。
- 通用 `SubAgentCapabilityProvider` 已从运行时删除；需要 sub-agent 能力时必须由明确的 capability provider 包装，例如 `RuleInquiryCapabilityProvider`。
- 已新增 `ActivityEnrollmentCapabilityProvider`，并把报名流程拆成 `spreadsheet_summarize`、`spreadsheet_query_product`、`activity_rule_check`、`enrollment_preview_create`、`enrollment_execute` 等细粒度 capability。
- `CapabilityDescriptor` 已暴露 `capabilityType`、`inputSchema`、`outputSchema`、`preconditions`、`postconditions`。
- `TaskPlanner` system message 保持通用规划原则，能力选择依据来自 capability catalog，而不是硬编码某个报名业务流程。
- `TaskGraphValidator` 已提供基础结构校验和 deterministic repair。
- `MarketingHarness` 已支持 `waiting_for_user` observation，并能在用户补充变量后恢复原 task graph 的等待节点。
- `ObservationEvaluator` 和 `RecoveryPolicyEngine` 已形成 observation evaluation / fallback / re-plan 的第一版闭环。
- metadata 中已经包含 task graph、validation、observations、observation evaluations、risk assessments、harness trace 和 capability catalog。
- 应用级测试已覆盖高风险直执拦截、默认 catalog 不暴露 `activity_enroll`、`rule_inquiry_provider` 暴露、preview pending action 绑定完整、waiting_for_user 原图续跑、非法依赖修复。
- 当前 `ActivityEnrollmentCapabilityProvider` 已把旧粗粒度报名 agent 拆成细粒度 capability 执行入口；这让 planner 可以组合能力，但 provider 内部仍主要是确定性工具封装，不具备 capability-scoped Re-Act 的观测、推理、参数修复和再次调用能力。

但当前还不能判定项目进入稳定通用智能体形态。主要原因：

- `ActivityEnrollAgent` 和 `activity_enroll` legacy skill 已移除，默认路径不再保留粗粒度报名 agent 退路。
- pending action、operation record、observation、audit 的关联已增强，但 operation record 仍缺少对 `pending_action_id`、`task_graph_id`、`task_node_id`、`observation_id` 的标准字段化双向引用。
- `CapabilityDescriptor.inputSchema/outputSchema` 仍主要由 `requiredInputs` / `outputContract` 自动生成，manifest 尚未支持真正字段级 schema。
- `TaskGraphValidator` 主要是结构校验，还不能判断 DAG 是否满足用户业务目标、是否遗漏必要节点、上下游 schema 是否真正匹配，也没有把 validation errors 交给 LLM 做 plan repair。
- `waiting_for_user` 续跑主要依赖 request variables 和单字段 query 填充；多字段、自然语言补充、仍缺信息时更新同一 waiting node 的能力还不完整。
- `ObservationEvaluator` 仍是简单规则，groundedness、充分性和 hallucination 检查都很粗糙。
- re-plan 只有雏形，尚未形成可靠的 old graph / new graph lineage、已完成证据携带策略和 eval 约束。
- 部分 provider 内部缺少 Re-Act 式工具执行循环。工具报错、参数带空格、字段别名、半结构化补充信息等问题目前主要靠工具错误文案或 harness 外层 recovery 暴露，provider 本身不会基于 observation 重新规范化参数、重试工具或生成更可靠的局部执行计划。
- RAG 和部分 provider 仍是演示实现，规则判断、文案生成、报名执行都还没有真实业务级质量。
- eval harness 仍以文本和 metadata 包含检查为主，不能系统性约束 DAG、HITL、recovery、groundedness 和多目标组合。

## 优先级说明

- P0：架构方向或安全边界问题。若不解决，未来能力越多，系统越容易退回固定 workflow 或 sub-agent 孤岛。
- P1：架构不变但核心实现需要大改。若不解决，系统可以演示，但难以稳定支撑复杂任务。
- P2：运行质量、可评估性和可演进性问题。若不解决，迭代会越来越难判断是否变好。
- P3：体验、工程完善和扩展性问题。重要，但可以在核心闭环稳定后推进。

## 架构反例：不要在 Planner System Message 中硬编码业务子 Capability

Planner 的基础 system message 只能描述通用规划原则，例如：

- 使用 catalog 中的 capability。
- 倾向细粒度、可组合、可恢复的能力。
- 根据 schema、preconditions、postconditions、risk、sideEffects、approval requirement 和 composableWith 决定节点组合。
- 对高风险副作用先规划 preview/proposal/approval 边界。
- 对缺失输入规划 waiting_for_user 或 clarification。

业务能力名称、输入输出、依赖建议和组合关系必须由 manifest/capability catalog 表达。如果 planner 不能从 catalog 推出正确 DAG，应优先增强 manifest schema、capability metadata、plan validation 和 eval，而不是把业务流程写进 system message。

当前代码中 `TaskPlanner` 的基础 prompt 已符合这个方向：它没有硬编码某个业务流程，而是把完整 capability catalog 展示给 planner，并要求基于通用 contract 做 DAG 规划。后续新增能力时应继续保持这个边界。

完成标准：

- planner 基础 system message 不包含具体业务流程路由表。
- 新增、拆分或重命名 capability 时，只需要更新 manifest/catalog/provider/eval，不需要更新 planner 基础 prompt。
- planner 的 DAG 质量提升来自更好的 capability contract、validation、repair 和 eval，而不是 prompt 中的业务硬编码。

## P0 状态：主路径已收口，剩余为扩大安全覆盖和运行质量约束

### P0-1 将高风险动作强制拦截上移到 Harness

当前状态：主路径基本完成，剩余是更严格的关联建模、测试覆盖和真实 operation 审计。

已完成：

1. `MarketingHarness.executePlannedGraph` 已在 provider 执行前评估风险。
2. `RiskAssessment.requiresApproval=true` 时，harness 直接生成 `waiting_for_approval` observation，不调用 provider。
3. `confirmed=true` 不再被视为合法授权，`RiskPolicyEngine` 只认可 `pending_action_approved=true`。
4. `PendingActionStateMachine.approve` 后，feedback 续跑由 harness 注入 `pending_action_approved=true`。
5. `PendingAction.withEditedPayload` 和 `MarketingHarness.feedbackInputs` 已禁止用户编辑覆盖 `task_graph_id`、`task_node_id`、`capability_name`、`idempotency_key`、`approval_source`。
6. `MarketingHarness.buildPendingAction` 已统一补齐并校验 pending action 的关键绑定字段，避免 provider 随意拼接 payload 后直接落库。
7. `enrollment_preview_create` 已生成明确的 approved execution node id，并写入 `execution_node_descriptor`。
8. pending action created / approved / rejected / edited / expired / executed 已写入 audit event。
9. `enrollment_execute` provider 内部会拒绝没有 `pending_action_approved=true` 的执行请求。
10. 应用级测试已覆盖 planner 直接选择 `enrollment_execute` 时不能绕过 harness，以及 preview 创建的 pending action 绑定完整。

仍未完成：

1. `OperationRecord` 只保存通用 request/result payload，还没有标准字段化引用 `pending_action_id`、`task_graph_id`、`task_node_id`、`observation_id`。
2. pending action、operation record、observation、audit 之间的双向关联还没有形成独立可查询模型。
3. `buildPendingAction` 仍在 `MarketingHarness` 内部，尚未抽成可单测、可复用、可被 eval 直接验证的 `PendingActionFactory`。
4. 当前测试覆盖了 direct side-effect block 和 preview binding，但还缺少 payload 篡改、重复确认、过期确认、错误 provider 产出执行卡等系统性场景。
5. `enrollment_execute` 仍是模拟 operation，真实业务 API 接入前还缺 dry-run、diff、rollback/compensation 和更强幂等模型。

下一步行动：

1. 抽出 `PendingActionFactory`，把 pending action 绑定、字段不可变规则、idempotency key 生成、audit payload 生成从 `MarketingHarness` 中独立出来。
2. 扩展 `OperationRecord`，标准化保存 `pending_action_id`、`task_graph_id`、`task_node_id`、`capability_name`、`observation_id`、`approved_by`。
3. 在 operation receipt、pending action payload、observation artifacts 和 audit data 中保持一致引用。
4. 增加 eval：任何 `sideEffects=true` 或 `requiresHumanApproval=true` 的 capability 在未审批前不得产生 operation receipt。
5. 增加回归：payload 篡改不能覆盖绑定字段；重复确认不能二次执行；过期确认不能执行；错误 provider 产出的 HITL 卡也必须由 harness 绑定和审计。

完成标准：

- 任意 provider 即使错误尝试执行副作用，也无法绕过 harness 边界。
- 所有副作用都有 pending action、audit、idempotency key、operation record 和状态机记录。
- approval 续跑只能执行 pending action 绑定的 capability 和 payload，且绑定信息完整可追踪。
- 可以从 operation record 反查 pending action、task graph node、observation 和 audit event 的主要关联。

### P0-2 将 Sub-agent 从“流程中心”降级为 Capability Provider

当前状态：默认执行路径已完成降级，通用 sub-agent adapter 和粗粒度报名 agent 退路已移除；后续重点转为 explicit provider 的 contract validation 和 eval。

已完成：

1. 默认 catalog 已移除粗粒度 `activity_enroll`。
2. 当前默认 catalog 暴露细粒度 capability：
   - `spreadsheet_summarize`
   - `spreadsheet_query_product`
   - `activity_rule_check`
   - `enrollment_preview_create`
   - `enrollment_execute`
   - `rule_inquiry`
   - `copywriting`
   - `notification_copywriting`
3. `ActivityEnrollmentCapabilityProvider` 承接报名相关细粒度能力。
4. `RuleInquiryCapabilityProvider` 承接 `rule_inquiry`，`InquiryAgent` 只作为内部实现细节。
5. 通用 `SubAgentCapabilityProvider` 已删除，不再因为 `entryAgent` 等于某个 registered sub-agent 就自动支持 capability。
6. `copywriting_provider` 已支持 `notification_copywriting`。
7. `ActivityEnrollAgent` 和 `activity_enroll` legacy skill 文件已删除。
8. 应用级测试已验证默认 catalog 不包含 `activity_enroll`，provider 列表不包含 `inquiry_agent` 或 `activity_enroll_agent`，并验证 legacy `activity_enroll` skill resource 不在 classpath。

仍未完成：

1. `RuleInquiryCapabilityProvider` 仍直接复用 `InquiryAgent` 的结果，provider 自身没有做 schema/evidence/groundedness 约束。
2. `InquiryAgent` 仍是 provider 内部 sub-agent 实现，缺少清晰的“内部实现可替换，不影响 capability contract”的测试边界。
3. 典型复合报名任务是否稳定产生多节点 DAG，目前仍主要依赖 planner 能力和少量测试，没有系统 eval。
4. 如果未来确实需要 agent-backed capability，目前没有标准 manifest 字段和 runtime contract；应新建显式 provider mode，而不是恢复通用 adapter。

下一步行动：

1. 为 `RuleInquiryCapabilityProvider` 增加 evidence-aware 输出约束：回答必须声明证据来源或明确说明证据不足。
2. 增加 provider contract tests：`rule_inquiry` 的 capability contract 不随 `InquiryAgent` 内部实现变化而变化。
3. 增加 eval：典型复合报名任务必须产生多节点 DAG，不能退回单个粗粒度 capability 或单个 agent 节点。
4. 如需重新引入 agent-backed provider mode，必须先设计 manifest 字段、allowlist、schema enforcement 和 observation contract validation，并配套 eval。

完成标准：

- 默认执行路径中不存在“任意 sub-agent 接管 capability”的万能入口。
- 典型复合任务能在 metadata 中看到明确多节点 DAG。
- Excel 查询失败时可以只恢复查询节点，而不是整个报名 agent 失败。
- 文案生成依赖上游 observation，而不是某个 sub-agent 私有状态。
- sub-agent 只能作为显式 capability provider 的内部实现细节存在，不能直接成为 catalog 的架构中心。

## P1 状态：已有骨架，尚未完成

### P1-1 强化 Capability Manifest 和 Schema

当前状态：部分完成。

已完成：

1. `CapabilityDescriptor` 已包含 `capabilityType`、`inputSchema`、`outputSchema`、`preconditions`、`postconditions`。
2. `SkillDescriptor` 和 `SkillRegistry` 已支持读取 `capabilityType`、`preconditions`、`postconditions`。
3. `TaskPlanner` prompt 和 `ContextAssembler` compressed context 已暴露更完整的 capability contract。
4. metadata 中的 capability view 已包含 schema、type、preconditions、postconditions。

仍未完成：

1. manifest 目前仍没有真正字段级 `inputSchema` / `outputSchema` 解析。当前 schema 主要由 `requiredInputs` 和 `outputContract` 自动生成。
2. `SkillRegistry.parseDescriptor` 仍是简单 key/value + CSV 解析，不支持 YAML 嵌套结构。
3. `SkillRegistryValidator` 只验证 provider 是否存在、skill resource 是否存在、sub-agent requiredInputs 是否兼容；它不验证 schema、risk declaration、sideEffects、output contract。
4. provider 输出没有被 schema validator 校验。
5. artifact、evidence、visibleObjects、missingInputs、confidence 仍未纳入结构化契约。
6. 当前 `skill` 和 capability manifest 在概念与目录结构上仍混在一起。`SkillDescriptor` 实际承担的是 capability manifest descriptor 的职责，而不是 Anthropic-style skill 所强调的过程性知识包职责。

下一步行动：

1. 升级 manifest 格式，支持字段级 input/output schema。
2. 用 YAML parser 替代当前手写 key/value parser。
3. 增加 `CapabilityManifestValidator`，验证 schema、risk、sideEffects、approval、provider support。
4. 增加 `ObservationContractValidator`，校验 provider 输出是否满足 capability output schema。
5. 将 artifact、evidence、visibleObjects、missingInputs、confidence 纳入 schema 契约。
6. 将 capability manifest 从 `SkillDescriptor` 语义中拆出，逐步重命名或引入 `CapabilityManifestDescriptor`，避免把运行时契约误称为 skill。
7. manifest 继续作为 planner、validator、policy、harness、eval 可机器读取的确定性契约；不要把长篇业务流程、few-shot、工具修复策略写入 manifest。

完成标准：

- planner 能基于 schema 判断上游输出是否满足下游输入。
- 新增 capability 时不需要改 harness 路由代码。
- provider 输出不符合 manifest 时会被 runtime 或测试发现。
- manifest 和 skill 的职责边界清晰：manifest 管“系统允许什么、如何调度和治理”，skill 管“模型如何理解和执行这类任务”。

### P1-2 增加 Plan Validation 和 Plan Repair

当前状态：基础结构已完成，语义能力不足。

已完成：

1. 已新增 `TaskGraphValidator`。
2. 已支持 null graph 检查、duplicate node id repair、unknown capability 移除、invalid dependency 修复、cycle 检查、side-effect node warning、required input warning。
3. `TaskPlanner` 不再静默移除非法依赖，修复过程由 validator 留痕。
4. metadata 已包含 validation errors、warnings、repairs。
5. 应用级测试已覆盖非法依赖修复。

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
2. evaluation 会输出 `sufficient`、`grounded`、`usable`、`needsFallback`、`needsReplan`、`reasons`。
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

### P1-5 引入 Capability-scoped Re-Act 执行模型

当前状态：尚未完成。当前 `ActivityEnrollmentCapabilityProvider` 的拆分方向是正确的，它把旧的粗粒度 `activity_enroll` 能力拆成了 `spreadsheet_summarize`、`spreadsheet_query_product`、`activity_rule_check`、`enrollment_preview_create`、`enrollment_execute` 等细粒度 capability，使 planner 能在 task graph 中组合、并行、暂停和恢复这些能力。

但 provider 内部目前主要是确定性 switch + 工具调用。进入某个 capability 后，执行路径基本由代码分支决定：

```text
capability name
  -> switch
  -> 参数读取
  -> 调用工具
  -> 成功 observation / 失败 observation / missing inputs
```

这种实现适合副作用执行、明确 schema 的简单读写、幂等工具封装，但不适合需要再次自然语言理解、参数修复、证据综合和多步工具探索的 capability。例如：

- 用户输入的 `product_id` 带有无关空格、全角符号或别名。
- 表格查询工具返回“未匹配”，但 evidence 显示列名或商品字段可能需要改写。
- `activity_rule_check` 需要基于上游 observation、规则知识、用户上下文和工具返回结果多轮判断。
- RAG 检索命中不足时，需要改写 query、扩大/缩小检索范围、再判断证据是否足够。
- 工具报错是可解释、可修复的参数错误，而不是应立即交给外层 harness re-plan 的全局失败。

目标状态：

1. capability provider 仍是 catalog 中的显式能力入口，不能退回一个粗粒度业务 agent。
2. provider 可以声明执行模式：
   - deterministic：确定性工具封装，适合副作用执行、简单查询、schema 明确的能力。
   - react：capability 内部拥有有限步数的 observe-reason-act 循环，适合半结构化输入、工具错误修复、RAG/规则判断、证据综合。
3. Re-Act 循环必须被 harness 约束，而不是成为新的架构中心：
   - 只能调用该 capability allowlist 内的工具。
   - 有最大步数、超时、token/cost budget。
   - 每一步 tool call 和 observation 都要写入 trace。
   - 最终仍必须输出统一 `Observation`。
   - 不能绕过 `RiskPolicyEngine`、`PendingActionStateMachine` 和 HITL 边界。
4. 高风险副作用能力，例如 `enrollment_execute`，默认必须保持 deterministic，不能让 provider 内部 Re-Act 自主执行真实副作用。

行动路径：

1. 设计 `CapabilityExecutionMode`，在 manifest / `CapabilityDescriptor` 中声明 `deterministic` 或 `react`。
2. 新增 `ReactCapabilityRuntime`，提供受控循环：

```text
initial capability inputs
  -> reason about current state
  -> choose allowed tool
  -> call tool
  -> observe result
  -> repair inputs / call another allowed tool / finish
  -> emit Observation
```

3. 为 `CapabilityExecutionRequest` 增加更适合 agentic provider 的上下文字段，例如 upstream observations、allowed tools、execution budget、previous tool observations。
4. 将 `spreadsheet_query_product` 优先改造成 react-capable capability：
   - trim / normalize 商品 ID。
   - 处理全角半角、空格、常见 SKU/商品名字段别名。
   - 查询失败后基于表格 summary 尝试字段或关键词改写。
   - 多次失败后输出结构化 missing input 或 failed observation。
5. 将 `activity_rule_check` 升级为 react-capable capability：
   - 读取上游 product/spreadsheet observation。
   - 检索规则证据。
   - 判断证据是否足够。
   - 证据不足时输出 limitations、missing inputs 或 fallback 建议。
6. provider 内部 Re-Act 的所有中间步骤写入 artifacts / trace，并在 final observation 中保留关键工具证据。
7. 增加 eval：参数有空格、字段别名、第一次工具查询失败、RAG 首次命中不足等场景，应能在 capability 内部自修复；耗尽预算后才交给 harness fallback、ask user 或 re-plan。

完成标准：

- `ActivityEnrollmentCapabilityProvider` 不再只是 switch 后的一次性工具调用集合；其中适合 agentic 执行的 capability 可以在受控预算内多轮调用工具和修复参数。
- capability 内部 Re-Act 不会重新变成粗粒度业务 agent；task graph、capability catalog、HITL 和 observation contract 仍由 harness 统一约束。
- 工具返回可修复错误时，系统能先在 capability 内部完成局部恢复，而不是立即暴露为全局失败。
- 所有 capability 内部工具调用都有 trace、budget 和最终 observation，后续可以被 eval 和 audit 检查。

### P1-6 将 Skill 定位从 Manifest 载体调整为过程性知识包

当前状态：尚未完成。当前项目目录使用 `skills/{name}/manifest.yaml` 注册能力，`SkillRegistry` 读取 manifest 后生成 `SkillDescriptor`，再由 `CapabilityDescriptor.fromSkill(...)` 转成 planner 可见的 capability catalog。这个实现能支撑早期 capability 注册，但它把两个不同概念混在了一起：

```text
Capability manifest
  -> 机器可读运行时契约
  -> 给 harness / planner / validator / policy / eval 使用

Skill
  -> LLM 可读过程性知识包
  -> 给 planner / react provider 学习如何理解、执行、修复和判断证据
```

长期看，`skill` 不应只是 manifest 的别名，也不应承担权限、审批、副作用、provider binding 等运行时治理职责。这些职责应留在 capability manifest 中。`skill` 应逐步向 Anthropic-style skill 的定位靠拢：一个包含 `SKILL.md`、示例、脚本、模板、领域参考和失败修复策略的按需加载知识包。

目标状态：

1. capability manifest 和 skill 分离：
   - manifest 负责能力名、输入 schema、输出 contract、provider、风险、副作用、审批、fallback、组合关系、预算和审计字段。
   - `SKILL.md` 负责使用场景、自然语言理解策略、参数抽取策略、工具调用步骤、失败修复策略、证据判断标准、few-shot 和反例。
2. planner 默认只加载 compact capability catalog；当需要规划某个复杂能力时，可以按需加载该能力的 skill 摘要或完整 `SKILL.md`。
3. react-capable provider 可以加载对应 `SKILL.md`，用于局部 Re-Act 循环中的参数修复、工具选择和证据充分性判断。
4. skill 不能绕过 capability catalog、schema、policy、HITL 或 provider allowlist。它只能增强模型理解和执行质量，不能成为新的隐形 agent 或权限入口。
5. 确定性副作用能力可以没有复杂 skill，或只提供非常薄的操作说明；高风险执行边界仍由 manifest + harness + policy 控制。

建议目录形态：

```text
capabilities/
  spreadsheet_query_product/
    manifest.yaml
    SKILL.md
    eval_cases.yaml

  activity_rule_check/
    manifest.yaml
    SKILL.md
    eval_cases.yaml
```

如果短期继续沿用 `skills/` 目录名，也应保持文件职责分离：

```text
skills/{capability_name}/manifest.yaml
skills/{capability_name}/SKILL.md
skills/{capability_name}/eval_cases.yaml
```

行动路径：

1. 引入或重命名 `CapabilityManifestDescriptor`，让当前 `SkillDescriptor` 的运行时契约职责逐步迁移到 manifest descriptor。
2. 在 manifest parser 中只解析机器契约字段；停止把过程性说明混入 manifest 字段。
3. 为 `spreadsheet_query_product`、`activity_rule_check`、`enrollment_preview_create` 优先补充 `SKILL.md`：
   - `spreadsheet_query_product`：商品 ID 归一化、字段别名、查询失败修复、表格证据输出。
   - `activity_rule_check`：规则证据来源、上游 observation 使用方式、证据不足时的追问/fallback。
   - `enrollment_preview_create`：只生成 preview/diff，不执行副作用，如何组织确认卡信息。
4. 增加 `SkillKnowledgeLoader`，支持按 capability name 加载 skill 摘要或完整内容，并控制上下文预算。
5. 修改 planner 上下文装配：catalog 用于选择能力，skill knowledge 用于复杂能力的节点输入生成、依赖规划和 completion criteria 设计。
6. 修改 react provider：允许其在预算内读取对应 `SKILL.md`，但工具调用仍必须来自 provider allowlist。
7. 增加 eval：验证新增 skill 后，复杂自由表达、参数带噪声、工具第一次失败等案例的 DAG 和 observation 质量变好，同时不能新增未注册 capability 或绕过 HITL。

完成标准：

- `skill` 不再只是 capability manifest 的别名。
- manifest 是可校验、可治理的机器契约；`SKILL.md` 是可按需加载的模型过程知识。
- 新增业务能力时，manifest 可以独立被 validator/eval 检查，skill 可以独立被 planner/react provider 使用。
- skill 的引入提升复杂任务规划和局部执行恢复能力，但不会改变 harness 的权限、安全和审计边界。

## P2 待办：RAG、记忆、评估与可观测性

### P2-1 将 RAG / Embedding 从占位实现升级为可用知识体系

当前问题：

默认 RAG 是内置少量文档加关键词包含排序；pgvector profile 下的 embedding 是 hashing 向量。这只能用于早期演示，不能支撑真实规则、活动、商品、案例和历史决策检索。

目标状态：

RAG 应提供可追踪、可引用、可评估的证据层，服务于规则判断、活动解释、历史决策复用和 planner 上下文构建。

行动路径：

1. 接入真实 embedding model。
2. 设计知识 chunk schema：tenant_id、document_id、chunk_id、title、content、source_uri、version、effective_from/effective_to、metadata、embedding。
3. 检索结果必须包含 score、citation、version 和 source。
4. 增加 domain filter，例如 rule、promotion、enrollment、case、risk、metric。
5. 将检索结果转成 observation evidence，而不是只作为 agent 内部工具字符串。
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

1. 在 compressed context 中加入最近关键 observation 摘要、active task graph 状态、pending/waiting nodes、可用 artifacts 摘要、visible object 摘要、pending action 风险和动作摘要、graph lineage 和 re-plan 历史。
2. 区分短期会话记忆、项目级记忆、artifact memory、decision memory。
3. 增加 memory compaction 策略，避免上下文无限增长。
4. 给 planner 和 provider/agent-backed provider 提供不同视角的 context。

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
2. 支持断言：expected capabilities set、expected dependency edges、expected parallel groups、forbidden direct side effects、expected waiting_for_user fields、expected waiting_for_approval before execution、expected fallback or re-plan、expected citations / grounded evidence、expected validation warnings / repairs、expected observation evaluation results。
3. 增加中文自由表达、顺序打乱、多目标混合的案例。
4. 增加 regression suite，避免新增业务能力时退回关键词路由或 sub-agent 中心化。

完成标准：

- 架构级退化能被测试发现。
- planner prompt 或 capability manifest 调整有可量化反馈。

### P2-4 完善 Trace、Audit 和 Runtime Metadata

当前问题：

当前已有 trace、audit、metadata，并且 metadata 已包含 validation 和 observation evaluation。pending action 审计已增强，但 run、task graph、node、observation、pending action、operation 之间的稳定关联仍不完整。

目标状态：

任何一次用户请求都能完整追踪：模型为什么规划、执行了什么、证据是什么、哪里暂停、谁确认、哪个操作产生副作用。

行动路径：

1. 为每个 node execution 记录统一 trace id。
2. pending action、operation record、observation 之间保存双向引用。
3. metadata 中明确区分 planner output、validation result、execution trace、recovery trace、HITL trace、graph lineage。
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
2. 支持 final answer、clarification question、approval request、partial progress summary、failure explanation。
3. 对复杂任务输出已完成事项、关键证据、风险或限制、等待用户确认/补充的内容、下游计划。

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

第一阶段：彻底完成 P0 收口，并删除历史退路。

1. 抽出 `PendingActionFactory` 并补齐 operation record 标准关联字段。
2. 为 `RuleInquiryCapabilityProvider` 增加 evidence-aware 输出约束和 provider contract tests。
3. 增加 payload 篡改、重复确认、过期确认、错误 HITL 卡、未审批 operation receipt 的回归测试。
4. 增加复合报名任务必须产生多节点 DAG 的结构化 eval。

第二阶段：补齐 P1 的真实 schema 和 plan repair。

1. manifest 支持字段级 schema。
2. provider 输出 schema validation。
3. schema-aware task graph validation。
4. validation-driven deterministic repair 和 LLM repair。
5. waiting_for_user 的 `MissingInputExtractor`。
6. observation evaluator 升级为 schema/evidence aware。
7. 引入 capability-scoped Re-Act 执行模型，优先改造 `spreadsheet_query_product` 和 `activity_rule_check`，让可修复工具错误、参数规范化和证据不足处理先在 capability 内部闭环。
8. 将 skill 从 capability manifest 载体中拆出，建立 `CapabilityManifestDescriptor` + `SKILL.md` 的双层结构，先为复杂能力补充 Anthropic-style 过程性知识包。

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

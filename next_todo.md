# Marketing Agent Next TODO

## 当前文档定位

`project_goal.md` 定义长期架构目标：本项目要从“业务 harness 雏形”演进为一个具备 deepagents 风格运行时能力的营销通用智能体。

本文件记录下一阶段的可执行重构计划。最高优先级不再是补某个单点 provider，而是先重构 planner、sub-agent、capability、skill 的定位和边界：

```text
Capability Manifest
  -> planner / validator / policy / harness / eval 可验证的运行时契约

Skill Package
  -> provider / sub-agent / react runtime 按需加载的过程知识包

SubAgent Profile
  -> planner 通过 delegate_task 可见的上下文隔离委派目标

Planner
  -> 基于 capability manifest、sub-agent profile、workspace refs 和 plan memory 生成可执行计划
```

## 总体判断

当前项目已经具备以下基础：

- `MarketingHarness` 是统一运行入口。
- `HarnessMiddlewareChain` 已有 lifecycle hooks。
- `TaskPlanner` 通过 LLM 生成 `TaskGraph`。
- `TaskGraph` 能表达依赖、并行 ready node、等待用户、等待审批、失败和成功状态。
- `CapabilityDescriptor` 已有 required inputs、output contract、permissions、risk、side effects、fallback、schema 等字段雏形。
- `SkillDescriptor` / `manifest.yaml` 已承载一部分 capability 描述。
- `SubAgent` / `InquiryAgent` 已能作为 provider 内部 agentic 实现。
- `AgentWorkspace` / `WorkspaceOffloadMiddleware` 已能把 observation/evidence/artifact 写入 workspace。
- `PendingActionStateMachine` 已经提供较强的业务 HITL 状态机。

主要问题：

1. Skill 与 capability manifest 混在一起。
2. Sub-agent 主要是 provider 内部实现，planner 不能直接感知和委派。
3. Planner 只能看 capability catalog，缺少 deepagents 风格的 sub-agent profile；skill progressive disclosure 也还没有下沉到执行上下文。
4. Capability manifest 还不够 schema-aware，无法支撑稳定的 plan validation、observation validation 和 eval。
5. Todo/PlanMemory 仍未成为模型可维护的持续工作计划。

因此，下一阶段 P0 是架构重构，而不是业务能力堆叠。

## P0：重构 Planner / Sub-agent / Capability / Skill 定位

### P0-1 拆分 Capability Manifest 与 Skill Package

#### 架构原因

当前 `SkillDescriptor` 实际承担了两类职责：

- 运行时契约：provider、requiredInputs、permissions、sideEffects、riskLevel、composableWith、fallback、pre/postconditions。
- 过程知识：skill.md、few-shot、工具使用流程、失败修复、证据判断。

这会让 skill 变成“半 manifest、半 prompt”，不利于 planner、validator、policy、eval 进行机器校验，也不利于 skill progressive disclosure。

#### 目标状态

新增独立概念：

```text
CapabilityManifestDescriptor
  -> 机器可验证的运行时契约

SkillPackageDescriptor
  -> LLM 可按需加载的过程知识包 metadata
```

推荐目录：

```text
capabilities/
  rule_inquiry.yaml
  activity_rule_check.yaml
  enrollment_preview_create.yaml

skills/
  rule_inquiry/
    SKILL.md
    references/
    scripts/
    assets/
    eval/
```

短期可以仍放在 `src/main/resources/skills/{name}/` 下，但语义上必须拆开：

```text
skills/{name}/manifest.yaml   -> capability manifest
skills/{name}/SKILL.md        -> skill package entry
```

后续再迁移到独立 `capabilities/` 目录。

#### 新字段建议

Capability manifest：

```yaml
name: rule_inquiry
description: Answer marketing activity and promotion rule questions.
provider: rule_inquiry_provider
executionMode: react
inputSchema:
  type: object
  required: [question]
outputSchema:
  type: observation
  required: [grounded_answer, citations, sufficiency_judgement]
permissions: [knowledge.retrieve, workspace.read]
sideEffects: false
riskLevel: low
requiresHumanApproval: false
composableWith: [activity_rule_check, copywriting]
fallbacks: []
preconditions:
  - question is available
postconditions:
  - answer cites evidence or explains evidence limits
skillRefs:
  - rule_inquiry
evalSuites:
  - rule_inquiry_basic
```

Skill package frontmatter：

```markdown
---
name: rule_inquiry
description: How to answer marketing rule questions with evidence and citations.
allowedCapabilities:
  - rule_inquiry
---

# Rule Inquiry

...
```

#### 实施步骤

1. 新增 `CapabilityManifestDescriptor`。
2. 新增 `CapabilityManifestRegistry`，先兼容读取现有 `manifest.yaml`。
3. 保留 `SkillRegistry`，但改为读取 `SKILL.md` frontmatter 和 skill package metadata。
4. 将 `CapabilityRegistry` 的来源从 `SkillDescriptor.fromSkill()` 迁移到 `CapabilityManifestDescriptor`。
5. 修改现有 `skill.md` 文件名为 `SKILL.md`，或短期兼容两者。
6. `TaskPlanner` prompt 只注入 compact capability manifest，不注入 skill 全文。
7. provider/sub-agent/react runtime 命中 capability 后，再按需加载对应 skill package。

#### 完成标准

- 新增 capability 不需要把过程知识塞进 manifest。
- planner 能仅基于 manifest 选择和组合 capability。
- skill 删除后不会改变权限边界，只会影响执行质量。
- capability manifest 能被 validator 和 eval 单独校验。

### P0-2 建立 SubAgent Profile，并引入 `delegate_task` capability

#### 架构原因

当前 `InquiryAgent` 是 `RuleInquiryCapabilityProvider` 的内部实现。Planner 看到的是 `rule_inquiry` capability，而不是 `inquiry_agent`。

这对稳定业务能力是合理的，但没有释放 deepagents 中 sub-agent 的核心价值：context isolation。对于表格解析、规则核验、资料检索、证据整理等重上下文任务，主 planner 应该能把任务委派给受控 sub-agent，主上下文只接收 summary 和 refs。

#### 目标状态

新增 `delegate_task` capability，作为 planner 可见的委派入口：

```yaml
name: delegate_task
description: Delegate an isolated analysis task to a bounded sub-agent and return summary plus workspace refs.
provider: subagent_delegation_provider
executionMode: delegate
inputSchema:
  type: object
  required: [agentName, task, expectedOutput]
  properties:
    agentName:
      type: string
    task:
      type: string
    expectedOutput:
      type: string
    contextRefs:
      type: array
    allowedTools:
      type: array
    maxSteps:
      type: integer
    maxTokens:
      type: integer
outputSchema:
  type: observation
  required: [summary, confidence, workspace_refs]
permissions: [workspace.read]
sideEffects: false
riskLevel: low
requiresHumanApproval: false
```

新增 `SubAgentProfile`：

```java
record SubAgentProfile(
    String name,
    String description,
    String systemPromptResource,
    List<String> allowedTools,
    List<String> permissionProfile,
    List<String> allowedSkills,
    int maxSteps,
    int maxTokens,
    Map<String, Object> outputSchema
) {}
```

Planner 需要看到 compact sub-agent list：

```text
Available delegation agents:
- general_purpose_agent: Read workspace refs, inspect evidence, summarize findings. No side effects.
- spreadsheet_analysis_agent: Analyze tabular enrollment/product data. Read-only. Emits normalized table summary refs.
- rule_check_agent: Check promotion/enrollment rules against evidence. Read-only. Emits decision and citations.
```

这对应 deepagents 的设计：主 agent 不是通过 skill 描述 sub-agent，而是通过 `task` 工具描述和 sub-agent `name/description/tools/permissions` 感知可委派目标。

#### 实施步骤

1. 新增 `SubAgentProfile` 和 `SubAgentProfileRegistry`。
2. 扩展 `SubAgent` 接口，增加 `profile()` 默认方法，或由 registry 单独读取 profile YAML。
3. 新增 `SubAgentDelegationCapabilityProvider`。
4. 新增 `delegate_task` capability manifest。
5. 新增 `general_purpose_agent`，只允许：
   - read workspace
   - search workspace
   - search knowledge base
   - read allowed skills
6. `TaskPlanner` prompt 增加 delegation section：列出可委派 sub-agent 的 name、description、permission profile。
7. `delegate_task` 执行时创建隔离 invocation id，将子 agent 详细过程写入 `/subagents/{invocationId}/`。
8. 主 harness 只接收：
   - summary
   - confidence
   - workspace refs
   - normalized observation
9. 子 agent 内部 tool call 也必须走统一 permission profile，不能绕过 policy。

#### 完成标准

- Planner 可以选择 `delegate_task`，并指定 `agentName`。
- Sub-agent 职责边界由 profile 描述，而不是由 skill 隐式描述。
- 主上下文不会吞下子 agent 的全部中间过程。
- 子 agent 不能执行未授权工具。
- 所有委派结果都进入 `Observation` 和 workspace。

### P0-3 引入 Skill Progressive Disclosure

#### 架构原因

能力数量增长后，不能把所有 skill 全文塞进 planner prompt。deepagents 的做法是：

- 启动时只暴露 skill metadata。
- 命中时再读取完整 `SKILL.md`。
- 需要时再读取 references/scripts/assets。

本项目也需要相同机制。

#### 目标状态

新增 `SkillKnowledgeLoader`：

```java
interface SkillKnowledgeLoader {
    List<SkillSummary> listSummaries();
    Optional<SkillPackage> loadSkill(String skillName);
    Optional<SkillReference> loadReference(String skillName, String path);
}
```

P0 阶段，主 planner 默认不看 skill summary，也不输出 `skillHints`。Skill metadata 只在执行上下文中暴露给 provider、ReactCapabilityRuntime 或被委派的 sub-agent：

```text
Available skill packages:
- rule_inquiry: How to answer marketing rule questions with evidence and citations.
- spreadsheet_query_product: How to inspect enrollment spreadsheets and normalize product IDs.
```

Provider/sub-agent/react runtime 在自己的隔离上下文中判断 skill 是否有用，并在需要时读取完整 `SKILL.md`。Planner 不负责证明 skill 是否适用于子任务。

#### 实施步骤

1. 将现有 `skill.md` 迁移为 `SKILL.md`。
2. 支持 `SKILL.md` frontmatter。
3. `SkillRegistry` 拆成：
   - `SkillPackageRegistry`
   - `SkillKnowledgeLoader`
4. `SpringAiAlibabaSkillRegistryAdapter` 改为基于 skill package metadata 生成 read_skill 指令。
5. `ContextAssembler` 不向主 planner 注入 skill summary；`SkillDisclosureMiddleware` 在 capability/sub-agent 执行上下文中注入允许的 skill metadata。
6. `InquiryAgent`、未来 `ReactCapabilityRuntime`、`delegate_task` sub-agent 可调用 `read_skill`。
7. 对 references/scripts/assets 增加路径白名单和权限扫描。

#### 完成标准

- planner prompt 不随 skill 全文线性膨胀。
- 执行 agent 可以按需读取完整 skill。
- skill 不能提升权限，只能提升执行质量。
- skill references 的读取可追踪、可预算、可审计。

### P0-4 Planner 改造成 Capability + Delegation 感知

#### 架构原因

当前 `TaskPlanner` prompt 已经能看到 capability catalog，但还缺少：

- sub-agent delegation targets。
- workspace refs 的更明确使用方式。
- todo/plan memory。
- schema-aware dependency 约束。

Planner 不应该读取或判断 skill 全文，也不应该承担“某个 skill 到底能不能帮 sub-agent 完成任务”的证明职责。否则主链路会重新吞下执行细节，违背 multi-agent 和 context isolation 的初衷。Skill 适用性判断应发生在被委派的 sub-agent 或 capability runtime 的隔离上下文中。

#### 目标状态

Planner system prompt 结构调整为：

```text
TASK_GRAPH_PLANNER

You plan executable DAGs for a marketing agent harness.

Runtime contracts:
1. Choose only capabilities from Capability Manifest.
2. Use delegate_task for isolated research/analysis when useful.
3. Do not treat sub-agents as workflow owners.
4. Skills are optional process knowledge loaded only inside execution contexts. They do not grant permissions.
5. Do not output skillHints in P0.
6. Side-effect capabilities require approval boundaries.

Capability Manifest:
...

Delegation Agents:
...

Workspace Refs:
...

Current Todo / PlanMemory:
...

Return JSON:
...
```

#### 实施步骤

1. 扩展 `HarnessContext`，加入：
   - capability manifests
   - sub-agent summaries
   - workspace refs
   - current todo state
2. 修改 `ContextAssembler` 输出 compact sections。
3. 修改 `TaskPlanner.plannerSystemMessage()`：
   - capability section 保持机器契约。
   - sub-agent section 只用于 `delegate_task`。
   - 不加入 skill summary section。
4. 修改 `TaskPlanner.validateAndNormalizeNodes()`：
   - 不允许未知 capability。
   - `delegate_task.agentName` 必须在 sub-agent profile 中存在。
   - side-effect capability 必须有 preview/proposal/approval 上游边界。
5. P0 明确禁止 planner 输出 `skillHints`。后续如需支持，也只能作为 P1/P2 的弱信号，并必须由 runtime 取 `skillHints ∩ capability.skillRefs ∩ subAgent.allowedSkills` 后再暴露给执行 agent。

#### 完成标准

- Planner 能区分 capability、sub-agent 和 skill 的职责。
- Planner 不能直接调用 sub-agent，只能通过 `delegate_task` capability。
- Planner 不把 skill 当成可执行能力。
- Planner 不读取 skill 全文，也不负责为 sub-agent 选择可靠 skill。
- Planner 生成的 DAG 能被 schema-aware validator 检查。

### P0-5 建立 Schema-aware Manifest / Plan / Observation Validator

#### 架构原因

当 planner 看到更多能力和 sub-agent profile 后，必须有更强的验证层，否则系统会回到 prompt 约定。Skill 引用也要在执行上下文中被 runtime 验证，不能由 planner 私下决定。

#### 实施步骤

1. 引入标准 YAML parser，替换当前手写 `key: value` parser。
2. 新增 `CapabilityManifestValidator`：
   - provider 是否存在。
   - executionMode 是否合法。
   - inputSchema/outputSchema 是否合法。
   - permissions 与 sideEffects / approval 是否一致。
   - fallback/composableWith/skillRefs 是否引用合法对象。
3. 新增 `SubAgentProfileValidator`：
   - allowedTools 是否存在。
   - permissionProfile 是否不超过工具权限。
   - allowedSkills 是否存在。
   - side-effect tools 是否禁止或必须走 HITL。
4. 升级 `TaskGraphValidator`：
   - 检查 capability 输入 schema。
   - 检查 `delegate_task.agentName`。
   - 检查 side-effect 前置 approval/proposal。
   - 检查依赖是否满足下游输入。
5. 新增 `ObservationContractValidator`：
   - provider/sub-agent 输出是否满足 outputSchema。
   - evidence/artifacts/workspaceRefs 是否满足 grounding 要求。

#### 完成标准

- 错误 manifest 在启动或测试阶段失败。
- 错误 plan 不会静默执行。
- provider 输出不满足 contract 时可被 runtime 或测试发现。

### P0-6 建立 PlanMemory / Todo 与 TaskGraph 双层计划

#### 架构原因

本项目的 `TaskGraph` 比 deepagents 的 todo 更适合严肃业务流程，但缺少模型可持续维护的工作计划。最佳方案不是二选一，而是双层计划：

```text
TaskGraph
  -> 工程执行层：依赖、并行、状态、审批、幂等、恢复

Todo / PlanMemory
  -> 模型工作层：阶段目标、用户可读进度、动态调整、前端展示
```

#### 实施步骤

1. 新增 `TodoItem`：

```java
record TodoItem(
    String id,
    String title,
    String status,
    String taskNodeId,
    String reason,
    List<String> workspaceRefs
) {}
```

2. 新增 `PlanMemory`：
   - current todos
   - taskGraphId
   - graphNode to todo mapping
   - previous graph lineage
3. `TaskPlanner.plan()` 生成 DAG 的同时生成初始 todo list。
4. `MarketingHarness` 执行节点时同步更新 todo。
5. recovery / replan 先更新 todo，再生成 continuation graph。
6. workspace 写入 `/plans/current.json` 和 `/todos/current.json`。
7. SSE / metadata 输出 todo 状态。

#### 完成标准

- 复杂任务不仅有内部 DAG，也有用户和模型都能读懂的持续计划。
- replan 后能看出哪些 todo 完成、替换、新增或阻塞。
- todo 不替代 task graph，而是增强长任务可解释性。

## P1：Runtime Middleware 化

P0 完成概念边界后，继续把运行时横切能力 middleware 化。

### P1-1 HumanApprovalMiddleware

把 pending action 创建、approval requirement、audit payload、idempotency key 从 `MarketingHarness` 进一步抽出。

目标：

- 所有 sideEffects=true 或 permission 包含 write/execute/external_send 的 capability 进入统一审批边界。
- pending action、operation、observation、audit 建立双向引用。
- sub-agent 内部敏感工具也继承 permission middleware。

### P1-2 SubAgentDelegationMiddleware

管理 sub-agent invocation lifecycle：

- before delegation
- after delegation
- tool permission inheritance
- workspace offload
- trace
- timeout/budget

### P1-3 SkillDisclosureMiddleware

管理执行上下文内的 skill metadata 注入、read_skill 工具、reference 读取、token budget、权限扫描和 trace。它不向主 planner 注入 skill 全文，P0 阶段也不向主 planner 注入 skill summary。

### P1-4 ContextBudgetMiddleware 升级

从 char budget 升级为 token-aware：

- 超长 observation 自动 offload。
- conversation history 摘要并归档。
- context overflow 后压缩重试 model call。

## P2：Capability Execution Runtime

### P2-1 Capability-scoped ReAct Runtime

部分能力不适合一次性 deterministic provider，例如：

- `rule_inquiry`
- `activity_rule_check`
- `spreadsheet_query_product`

新增 `ReactCapabilityRuntime`：

```text
inputs
  -> read relevant skill
  -> reason
  -> choose allowed tool
  -> observe
  -> repair / continue / finish
  -> emit Observation
```

约束：

- tool allowlist 来自 capability manifest。
- permissions 来自 policy。
- budget 来自 manifest。
- 输出必须满足 observation contract。

### P2-2 Provider 质量升级

在 P0/P1 的契约之上增强业务 provider：

- `RuleInquiryCapabilityProvider` evidence-aware。
- `activity_rule_check` 接入真实规则/RAG。
- `spreadsheet_query_product` 增强列类型推断、商品 ID 匹配、别名处理。
- `copywriting` 基于 upstream observations 生成文案。
- `enrollment_execute` 接入真实 API 前先完善 dry-run、diff、rollback/compensation、idempotency。

## P3：Eval / Trace / Replay

### P3-1 结构化 Eval Harness

当前 eval 已能检查 answer、capability、task node 数量、harness status。下一步升级为 YAML/JSON case schema：

- expected capabilities set
- expected dependency edges
- expected delegate_task agentName
- expected runtime skill usage / read_skill trace
- forbidden direct side effects
- expected waiting_for_user fields
- expected waiting_for_approval before execution
- expected fallback / replan
- expected citations / grounded evidence
- expected workspace refs
- expected todo state
- expected validation warnings / repairs

### P3-2 Trace / Audit / Replay

目标：

- 每个 model call、capability call、tool call、sub-agent invocation、observation commit 有统一 trace id。
- pending action、operation record、observation、workspace artifact 双向引用。
- metadata 区分 planner output、validation result、execution trace、recovery trace、HITL trace、graph lineage、workspace refs。
- 最小 replay 数据结构可复盘主要决策路径。

## 建议迭代顺序

第一阶段：概念拆分和 planner 可见性

1. `CapabilityManifestDescriptor` / `CapabilityManifestRegistry`
2. `SkillPackageDescriptor` / `SkillKnowledgeLoader`
3. `SubAgentProfile` / `SubAgentProfileRegistry`
4. `delegate_task` capability
5. `TaskPlanner` 注入 capability + sub-agent 两段信息，skill metadata 下沉到执行上下文

第二阶段：验证和边界

1. 标准 YAML parser
2. `CapabilityManifestValidator`
3. `SubAgentProfileValidator`
4. `TaskGraphValidator` schema-aware upgrade
5. `ObservationContractValidator`

第三阶段：计划记忆和上下文隔离

1. `PlanMemory` / `TodoItem`
2. DAG node 与 todo 双向引用
3. `/plans/current.json` 和 `/todos/current.json`
4. sub-agent invocation 写入 `/subagents/{invocationId}/`
5. 主 harness 只接收 summary + refs

第四阶段：runtime middleware

1. `HumanApprovalMiddleware`
2. `SubAgentDelegationMiddleware`
3. `SkillDisclosureMiddleware`
4. token-aware `ContextBudgetMiddleware`

第五阶段：能力质量和 eval

1. `ReactCapabilityRuntime`
2. 重点 provider 改造
3. 结构化 eval harness
4. trace/audit/replay

## 每次改动的判断标准

未来任何架构或功能改动，都应回答以下问题：

1. 是否把 capability、skill、sub-agent 的职责边界变得更清晰？
2. 是否让 planner 基于 manifest/profile/schema 工作，而不是基于关键词或隐藏约定？
3. 是否让 sub-agent 成为受控 context isolation 工具，而不是新的业务流程中心？
4. 是否让 skill 成为按需加载的过程知识包，而不是权限入口？
5. 是否让 TaskGraph 和 Todo/PlanMemory 形成双层计划？
6. 是否让所有副作用更牢地进入 capability/tool-level HITL 和 policy 边界？
7. 是否让 observation 更可验证、更 grounded、更可复用？
8. 是否增强 workspace refs、trace、audit、eval 或 replay？
9. 是否能在用户换说法、换顺序、混合多个目标时仍然成立？

如果答案是否定的，这个改动很可能是在把系统拉回固定工作流，而不是推向通用营销智能体运行时。

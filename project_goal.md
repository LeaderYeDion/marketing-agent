# Marketing Agent Project Goal

## 一句话目标

本项目的目标不是做一个由固定工作流、关键词路由或一组业务 agent 拼起来的营销问答工具，而是演进为一个面向营销场景的通用智能体运行时：

- LLM 负责自然语言理解、目标识别、任务拆解和能力选择。
- Java harness 负责工程约束、权限边界、状态机、执行调度、审计、恢复和评估。
- Capability 是系统对外暴露的运行时能力契约。
- Sub-agent 是受控的上下文隔离和任务委派机制。
- Skill 是可按需加载的过程知识包，而不是权限入口或业务路由规则。

本项目要向 `langchain-ai/deepagents` 的设计理念靠拢：把复杂 agent 需要的 todo、workspace、sub-agent、skill、summarization、HITL、permission、trace 等能力做成默认运行时能力，而不是让每个业务 provider 零散实现。

## 核心架构判断

营销智能体的关键不是拥有很多 sub-agent，而是拥有一个可组合、可观测、可恢复、可审计、可评估的 agent harness。

当前项目已经具备业务 harness 雏形：

- `MarketingHarness` 作为统一运行入口，负责 context assemble、task graph planning、capability execution、observation commit、recovery、HITL、audit 和 telemetry。
- `TaskPlanner` 通过 LLM 生成结构化 `TaskGraph`，避免 Java 代码直接根据自然语言做关键词路由。
- `CapabilityDescriptor` / `SkillDescriptor` 已经尝试把能力描述为可组合、可授权、可评估的目录。
- `PendingActionStateMachine` 已经把高风险副作用动作关进确定性审批边界。
- `AgentWorkspace` / `WorkspaceOffloadMiddleware` 已经开始把 observation、evidence、artifact 从 prompt 卸载到可引用的工作区。

但当前 planner、sub-agent、capability、skill 的边界仍然需要重构：

- 当前 skill 更像 capability manifest 和 prompt playbook 的混合体。
- 当前 sub-agent 多数是 capability provider 的内部实现细节，而不是主运行时可主动委派的 context isolation 工具。
- 当前 planner 主要感知 capability catalog，还不能像 deepagents 一样通过统一 `task`/`delegate_task` 机制感知可委派 sub-agent 的职责边界。
- 当前 skill 还没有升级为 `SKILL.md + references/scripts/assets/eval` 的渐进披露包。

因此，后续架构必须明确分层。

## 目标分层

### 1. Planner

Planner 是自然语言任务规划内核。它负责：

- 理解用户目标。
- 判断目标需要哪些 capability。
- 生成可验证的 `TaskGraph`。
- 判断哪些任务需要并行、依赖、追问、审批或委派。
- 在复杂任务中使用 `delegate_task` 将重上下文子任务交给 sub-agent。

Planner 不应该：

- 直接根据关键词路由到某个业务 agent。
- 直接绕过 capability catalog 调用 provider。
- 把 sub-agent 当作新的流程中心。
- 读取所有 skill 全文并把 prompt 线性膨胀。
- 负责证明某个 skill 是否真的适用于子任务；skill 适用性判断应下沉到执行 runtime 或被委派的 sub-agent。

Planner 应该看到的是：

- compact capability manifest。
- 可委派 sub-agent 的 name、description、permission profile 和工具范围摘要。
- workspace refs 和当前 todo/plan memory。

P0 阶段 planner 默认不需要看到 skill summary，也不输出 `skillHints`。它只负责决定“做什么、由哪个 capability 做、是否通过 `delegate_task` 委派给哪个 sub-agent”。Skill 的选择和读取发生在 provider / ReactCapabilityRuntime / sub-agent 的隔离执行上下文中。

### 2. Capability

Capability 是系统的一等运行时能力契约。它描述“系统能做什么”，并给 planner、validator、policy、harness、eval 使用。

Capability manifest 应只包含机器可验证的运行时字段：

- `name`
- `description`
- `provider`
- `executionMode`: `deterministic` / `react` / `delegate`
- `inputSchema`
- `outputSchema`
- `permissions`
- `sideEffects`
- `riskLevel`
- `requiresHumanApproval`
- `approvalPolicy`
- `budget`
- `composableWith`
- `fallbacks`
- `preconditions`
- `postconditions`
- `observationContract`
- `evalSuites`

Capability 不应该承载大量过程性提示词。过程知识应移动到 skill package。

### 3. Sub-agent

Sub-agent 的核心价值不是业务路由，而是 context isolation。

本项目未来要区分两类 sub-agent：

1. Capability-backed sub-agent

   作为某个 capability provider 的内部实现，例如规则咨询、表格分析、文案生成。这类 sub-agent 可以继续存在，但其结果必须回到统一 `Observation`。

2. Delegation sub-agent

   作为主运行时可调用的隔离执行单元，例如：

   - `general_purpose_agent`
   - `research_agent`
   - `spreadsheet_analysis_agent`
   - `rule_check_agent`
   - `copywriting_agent`

   主 harness 通过 `delegate_task` capability 调用它们。每个 sub-agent 需要声明：

   - `name`
   - `description`: 给 planner 判断何时委派。
   - `systemPrompt`
   - `allowedTools`
   - `permissionProfile`
   - `allowedSkills`
   - `maxSteps`
   - `maxTokens`
   - `outputContract`

Sub-agent 不能绕过 capability、policy、HITL、schema、audit 和 observation。它只能在受控权限内完成隔离任务，并返回 summary、confidence、workspace refs 和 observation。

### 4. Skill

Skill 是过程知识包，不是 capability manifest，也不是 sub-agent 描述。

Skill 应学习 deepagents 的 progressive disclosure 设计：

```text
skills/{skill_name}/
  SKILL.md
  references/
  scripts/
  assets/
  eval/
```

`SKILL.md` 使用 frontmatter 暴露 compact metadata：

```yaml
---
name: rule_inquiry
description: How to answer marketing rule questions with evidence and citations.
---
```

启动或主规划阶段不注入 skill 全文。P0 阶段，主 planner 默认不注入 skill metadata；runtime 根据 capability 的 `skillRefs`、sub-agent profile 的 `allowedSkills` 和当前任务，为执行 agent 暴露允许的 skill metadata。只有 provider、ReactCapabilityRuntime 或 sub-agent 在自己的隔离上下文中判断某个 skill 相关时，才按需读取完整 `SKILL.md` 和必要 references/scripts/assets。

Skill 可以描述：

- 参数抽取方法。
- 工具调用步骤。
- 证据判断标准。
- 失败修复策略。
- few-shot。
- 反例。
- 输出格式建议。

Skill 不可以：

- 声明或扩大工具权限。
- 绕过 capability manifest。
- 绕过 HITL。
- 成为隐藏业务路由入口。

## 目标运行时形态

```text
User Request
  -> Conversation Lock
  -> ContextBudgetMiddleware
  -> Workspace / Memory load
  -> Capability Manifest compact disclosure
  -> SubAgent Profile compact disclosure
  -> Planner
       -> TaskGraph
       -> Todo / PlanMemory
  -> Schema-aware Graph Validator
  -> Scheduler
       -> CapabilityProvider
       -> delegate_task SubAgent
       -> ReactCapabilityRuntime
       -> Skill metadata disclosure inside execution context
  -> ToolPermissionMiddleware
  -> HumanApprovalMiddleware
  -> WorkspaceOffloadMiddleware
  -> Observation
  -> ObservationEvaluator
  -> Recovery / Replan / Ask User / Approval
  -> Workspace + Conversation commit
  -> Stream todo/subagent/observation/artifact state
  -> AnswerSynthesizer
```

## 与 deepagents 的关系

deepagents 的核心不是“有 sub-agent”或“有 skill”这些名词，而是把复杂 agent 所需的运行时能力做成默认 harness：

- todo list 让模型持续维护工作计划。
- filesystem/workspace 让上下文可以卸载、检索和恢复。
- `task` 工具让主 agent 可以把重上下文任务委派给 sub-agent。
- skills 通过 progressive disclosure 让过程知识按需加载。
- middleware 管理 summarization、permissions、HITL、tool patch、memory 和 streaming。

本项目不应简单重写成 deepagents，而应保留自己的业务优势：

- `TaskGraph` 比普通 todo 更适合表达严肃业务流程的依赖、并行、审批和恢复。
- `PendingActionStateMachine` 比通用 HITL 更贴近真实业务副作用边界。
- `CapabilityDescriptor` 比普通 tool doc 更适合营销领域能力治理。
- `Observation` 比工具字符串结果更适合 eval、recovery、audit 和 replay。

正确方向是：

```text
保留 TaskGraph / Capability / Observation / PendingAction
  + 引入 deepagents 风格的 PlanMemory / Workspace / SubAgent Delegation / Skill Progressive Disclosure / Middleware-first Runtime
```

## 架构原则

1. 模型负责语义，代码负责约束。
2. Capability 是 planner 可见的能力地图。
3. Sub-agent 是受控的 context isolation 工具，不是新的业务流程中心。
4. Skill 是按需加载的过程知识包，不是权限入口。
5. Planner 不负责读取或验证 skill 全文；skill 是否有用由执行 runtime/sub-agent 在隔离上下文中按需判断。
6. TaskGraph 负责工程执行，Todo/PlanMemory 负责模型可读的持续计划。
7. 所有副作用必须经过 policy、HITL、idempotency、audit 和状态机。
8. 所有 provider、sub-agent、tool 结果必须转成 Observation。
9. Context 不能永远堆在 prompt 中，必须进入可命名、可检索、可恢复的 workspace。
10. Planner 不能依赖关键词路由，应依赖 capability manifest、sub-agent profile 和 schema contract。
11. Eval 不只评估最终答案，还要评估 plan、capability choice、dependency、HITL、observation、workspace refs 和 recovery。

## 预期终态示例

用户输入：

```text
帮我看一下这个报名 Excel 里商品 123 的价格，判断它能不能参加这个优惠活动。
如果可以，生成报名预览让我确认；确认后执行报名，再帮我写一段社群通知。
如果发现规则不满足，告诉我原因并给出替代方案。
```

系统应完成：

1. Planner 理解这是跨表格、规则、报名、HITL、文案的复合任务。
2. Planner 生成 `TaskGraph` 和 `Todo/PlanMemory`。
3. 对表格解析、规则核验等重上下文任务通过 `delegate_task` 委派给受控 sub-agent。
4. Sub-agent 在隔离上下文中读取 workspace、调用允许工具、按需加载 skill。
5. Sub-agent 输出 summary、evidence、workspace refs 和 observation。
6. Harness 基于 observation 继续调度下游 capability。
7. 高风险报名执行进入 `PendingActionStateMachine`。
8. 用户确认后执行副作用，并写入 audit、operation lineage、workspace 和 trace。
9. 最终回答由 observations、todos、evidence、approval 状态综合生成。

## 衡量标准

一个改动是否符合目标，可以用以下问题判断：

- 是否减少了自然语言关键词路由？
- 是否让 planner 更清楚地看到 capability 空间？
- 是否让 planner 能通过 `delegate_task` 看到可委派 sub-agent 的职责边界？
- 是否把 skill 从 manifest/prompt 混合体升级为按需加载的过程知识包？
- 是否让 context 从 prompt 堆叠升级为 workspace refs？
- 是否让 TaskGraph 更能表达依赖、并行、暂停、恢复？
- 是否让 Todo/PlanMemory 更好支撑长任务推进？
- 是否把副作用更牢地关进 capability/tool-level HITL 和 policy 边界？
- 是否让 observation 更可评估、更 grounded、更可复用？
- 是否增强 trace、audit、eval、workspace refs 或 replay？

如果一个改动不能改善以上任一项，或者让其中某项倒退，应谨慎合入。

# Marketing Agent Project Goal

## 一句话目标

本项目的目标不是做一个由固定流程和关键词路由驱动的营销问答工具，而是演进为一个以 LLM 自然语言理解为决策内核、以 harness engineering 作为工程约束和运行时底座的营销通用智能体。

它最终应该能够理解用户自由表达的营销目标，自主拆解任务，选择并组合能力，执行可并行或有依赖的复杂任务，持续观测结果，必要时重新规划，并在高风险动作前进入人类确认边界。

## 项目背景

当前系统已经具备一部分 agent harness 的基础设施：

- 会话入口和 Spring Graph 入口。
- LLM 调用网关、fallback、限流、熔断和观测。
- Skill、Capability、Tool、Sub-agent 的雏形。
- ConversationSession、ContextSummary、VisibleObject、PendingAction。
- PendingActionStateMachine 作为高风险动作的确定性审批边界。
- 审计、遥测、会话锁、幂等操作和 SSE 事件。

这些能力证明项目已经不是单纯的提示词工程或单点 agent demo。但系统的长期目标要求架构中心继续从“业务 agent 路由”升级为“围绕用户目标持续规划、执行、观测、恢复的统一 harness”。

## 核心判断

营销通用智能体的关键不是拥有很多 sub-agent，而是拥有一个可组合、可观测、可恢复、可评估的能力运行时。

如果系统依赖代码里的 `if contains`、关键词匹配、固定 intent 分支来理解用户输入，那么用户换一种说法、打乱输入顺序、混合多个业务目标时，系统就会退化。这样的实现不是通用智能体，只是被自然语言包装过的固定工作流。

因此，本项目的架构原则是：

- LLM 负责理解自然语言、识别目标、拆解任务、选择能力、规划依赖关系。
- Java 代码负责工程约束，包括上下文装配、能力目录暴露、结构校验、权限控制、任务调度、观测记录、恢复策略、审计和 HITL。
- 业务能力以 capability provider 或 sub-agent provider 形式接入，不能成为系统的第一等架构中心。
- task graph 是复杂任务的核心表达，不是某个 agent 的内部实现细节。

## 目标能力

系统需要逐步具备以下能力：

1. 自然语言理解

用户可以用任意中文表达业务目标，系统不要求用户使用固定关键词或固定句式。理解职责由 LLM planner 承担，代码不对用户自然语言做语义路由。

2. 自主任务规划

系统能够将复杂目标拆成结构化 task graph，包括：

- 子任务目标。
- 所需 capability。
- 输入参数。
- 依赖关系。
- 可并行节点。
- 完成标准。
- planner rationale。

3. 能力组合

系统可以把多个能力组合为完整业务方案，例如：

```text
读取报名 Excel
  -> 查询商品和价格
  -> 判断活动规则
  -> 生成报名预览
  -> 等待用户确认
  -> 执行报名
  -> 生成社群通知
```

4. 可观测执行

每个节点执行后都应生成 observation，记录：

- 状态。
- 业务摘要。
- 证据和产物。
- 置信度。
- 缺失输入。
- 风险等级。
- 可见对象。
- 需要提交到会话的消息。
- 状态补丁。

5. 失败恢复和重新规划

系统不应该遇到工具失败或信息不足就直接终止，而应根据 observation 决定：

- 重试。
- fallback 到其他 capability。
- 追问用户。
- 暂停等待审批。
- 标记失败并给出清晰原因。
- 在未来进一步支持 LLM based re-plan。

6. HITL 和权限边界

所有可能产生业务副作用的动作必须进入确定性审批边界。模型可以提出建议和计划，但副作用执行必须由 harness、policy 和 PendingActionStateMachine 控制。

7. 长上下文项目推进

系统需要把会话、文件、可见对象、待确认动作、历史判断依据、当前任务图和未完成子任务组织成 agent 可观察、可检索、可推理的工作记忆。

## 当前架构设计

### 运行主线

```text
User input
  -> MarketingGraphFactory
  -> MarketingHarness
  -> ContextAssembler
  -> TaskPlanner
  -> TaskGraph
  -> CapabilityRegistry
  -> CapabilityProvider / SubAgentCapabilityProvider
  -> Observation
  -> RecoveryPolicyEngine
  -> Memory / Audit / Telemetry / HITL
  -> User response or continuation
```

### 决策职责划分

| 问题 | 负责方 | 说明 |
| --- | --- | --- |
| 用户到底想做什么 | LLM planner | 从自然语言和上下文中理解目标 |
| 任务应该怎么拆 | LLM planner | 生成 task graph 和依赖关系 |
| 用哪些能力 | LLM planner | 只能从 capability catalog 中选择 |
| 能力名是否合法 | Harness / TaskPlanner | 确定性校验，不理解自然语言 |
| 哪些节点可并行 | TaskGraph runtime | 根据 DAG 依赖计算 ready nodes |
| 是否允许执行 | RiskPolicyEngine | 根据权限、风险和确认状态判断 |
| 如何执行能力 | CapabilityProvider | 执行具体业务能力或 sub-agent |
| 结果是否可用 | Observation / RecoveryPolicyEngine | 根据状态、缺失输入、错误类型判断 |
| 是否需要用户确认 | PendingActionStateMachine | 管理审批、拒绝、编辑、过期、执行状态 |
| 如何沉淀上下文 | ConversationSession / Memory | 记录对后续决策有用的信息 |

### 关键模块

#### MarketingHarness

系统的统一运行时。它不负责理解用户自然语言，而负责：

- 获取会话锁。
- 装配上下文。
- 调用 planner。
- 校验 task graph。
- 调度 ready nodes。
- 并行执行无依赖任务。
- 将 observation 写入记忆。
- 调用 recovery policy。
- 管理 HITL 反馈续跑。
- 写入审计、遥测和响应 metadata。

#### TaskPlanner

系统的自然语言任务规划内核。它通过 LLM 完成：

- 目标理解。
- 任务拆解。
- capability 选择。
- 输入整理。
- 依赖关系规划。
- 输出 answer strategy。

TaskPlanner 中允许存在确定性安全兜底，但兜底只能基于显式协议字段或能力 manifest，不允许用关键词匹配承担自然语言理解。

#### TaskGraph / TaskNode

复杂任务的结构化表达。TaskGraph 是 harness 执行和恢复的中心对象，承载：

- 节点列表。
- 用户目标。
- planner rationale。
- answer strategy。
- 图状态。
- 节点依赖。
- ready node 计算。
- fallback 依赖重写。

#### CapabilityRegistry

能力目录。它应该向 planner 暴露完整能力地图，包括：

- 能力名称。
- 描述。
- 输入要求。
- 输出契约。
- 权限。
- 风险等级。
- 是否有副作用。
- 是否需要人工确认。
- 可组合能力。
- fallback 能力。

CapabilityRegistry 的职责是让模型看到一个可推理、可组合的能力空间，而不是让代码做业务路由。

#### CapabilityProvider

能力执行入口。能力可以由普通 provider 实现，也可以由 sub-agent provider 实现。sub-agent 是能力提供者的一种，不是系统的架构中心。

#### Observation

harness 的观测对象。所有能力执行结果都应转为 observation，使后续节点、恢复策略、评估系统和用户响应可以基于统一结构工作。

#### RecoveryPolicyEngine

失败恢复策略入口。当前可以处理 retry、fallback、ask user 等动作，未来应进一步接入 LLM based evaluation 和 re-plan。

#### PendingActionStateMachine

高风险副作用边界。它把模型建议和真实业务副作用隔离开，确保确认、拒绝、编辑、过期、执行都是确定性状态迁移。

## 架构设计原则

### 1. 模型负责语义，代码负责约束

谁理解用户输入？LLM。

谁决策行动路径？LLM planner 产出计划，harness 在能力目录和策略边界内执行。

谁做任务规划编排？LLM 生成 task graph，TaskGraph runtime 按依赖调度。

代码不应该通过自然语言关键词来判断用户意图。代码可以做的是：

- 判断 capability 是否存在。
- 判断依赖是否有效。
- 判断权限是否允许。
- 判断字段是否为空。
- 判断状态机迁移是否合法。
- 判断错误类型是否可重试。

这些是工程约束，不是自然语言理解。

### 2. task graph 优先于单 agent 路由

系统不应该先问“交给哪个 agent”，而应该先问：

- 用户目标是什么？
- 目标需要拆成哪些子任务？
- 哪些子任务可以并行？
- 哪些子任务必须依赖前置 observation？
- 哪些任务需要人类确认？
- 失败后怎么恢复？

agent 和 tool 都只是 task graph 节点可调用的能力提供者。

### 3. capability 是系统的能力地图

每个能力都要有清晰的 manifest。没有 manifest 的能力，对 planner 来说就是不可见能力。

能力描述应该让模型能判断：

- 这个能力能解决什么问题。
- 需要哪些输入。
- 能输出什么。
- 会不会产生副作用。
- 是否需要确认。
- 能和哪些能力组合。
- 失败后可以 fallback 到哪里。

### 4. 观测和恢复是 harness 的核心

执行不是调用工具后拼接回答。执行必须产生 observation，并进入统一闭环：

```text
execute
  -> observe
  -> evaluate
  -> recover or continue
  -> answer / ask / approve / re-plan
```

### 5. 高风险动作必须确定性

LLM 可以建议报名、投放、发券、改预算、发送通知，但不能直接越过审批边界。真实副作用必须经过：

- 风险策略。
- pending action。
- 用户确认。
- 幂等键。
- 审计记录。
- 状态机迁移。

### 6. 评估要成为架构的一部分

系统越通用，越需要 eval harness。评估不只是检查答案文本，还要检查：

- planner 是否选择了正确 capability。
- task graph 是否有合理依赖。
- 是否识别出并行节点。
- 是否在信息不足时追问。
- 是否没有绕过 HITL。
- fallback 是否正确。
- observation 是否 grounded。
- metadata 是否可追踪。

## 反模式

以下做法会阻碍项目成为营销通用智能体：

- 根据用户 query 写 `contains`、关键词表、正则意图识别来路由业务。
- 让 MainAgent 只返回单个 delegate_to，然后把整个任务扔给某个 sub-agent。
- 每新增一个业务领域就新增一个互相隔离的 sub-agent，但没有统一 capability catalog。
- sub-agent 私有化自己的工具、状态和输出格式，导致上层无法组合。
- 工具调用结果不转 observation，后续无法评估和恢复。
- 高风险动作由模型或 agent 直接执行。
- 没有 golden cases、trace、audit、planner eval。

## 预期终极形态

本项目的终极形态是一个营销领域的通用智能工作台。用户不需要理解系统内部有哪些 agent，也不需要用固定格式输入。他只需要表达业务目标。

例如用户说：

```text
帮我看一下这个报名 Excel 里商品 123 的价格，判断它能不能参加这个优惠活动。
如果可以，生成报名预览让我确认；确认后执行报名，再帮我写一段社群通知。
如果发现规则不满足，告诉我原因并给出替代方案。
```

系统应该能够自主完成：

1. 理解这是一个跨表格、规则、报名、HITL、文案的复合任务。
2. 规划 task graph。
3. 查询 Excel。
4. 查询或判断活动规则。
5. 合并证据。
6. 判断是否满足条件。
7. 如满足，生成待确认报名动作。
8. 用户确认后执行副作用。
9. 继续执行下游文案生成。
10. 如失败或信息不足，追问、fallback 或重新规划。
11. 在 metadata、trace、audit 和 memory 中留下完整过程。

终极形态下，系统应该具备以下特征：

- 自然语言自由输入。
- 多轮项目推进。
- 长上下文工作记忆。
- 跨领域能力组合。
- 可并行任务调度。
- 可恢复执行。
- 可审计副作用。
- 可解释计划和结果。
- 可持续评估和优化。
- 能力目录可扩展。
- sub-agent 可替换、可降级、可组合。

## 长期演进方向

1. Planner 升级

- 支持 plan validation。
- 支持 plan repair。
- 支持 LLM based re-plan。
- 支持任务预算、优先级和截止条件。
- 支持多候选计划比较。

2. Memory 升级

- 引入长期用户偏好。
- 引入营销项目级上下文。
- 引入文件索引和可查询对象。
- 引入历史决策依据。
- 引入未完成任务树。

3. Capability 升级

- 每个能力都有 manifest、skill.md、eval cases、输入 schema、输出 schema。
- provider 和 sub-agent 可以独立扩展。
- 能力可被 planner 组合，而不是通过代码固定编排。

4. Observation / Evaluation 升级

- 引入 groundedness 检查。
- 引入工具结果充分性检查。
- 引入 hallucination 检查。
- 引入路由和规划质量评估。
- 引入线上 trace 反哺 skill 描述和 eval case。

5. HITL / Policy 升级

- 更细粒度的风险分级。
- 权限和角色控制。
- 批准前 diff。
- 幂等执行保障。
- 操作回滚或补偿机制。

## 衡量标准

一个改动是否符合本项目目标，可以用以下问题判断：

- 它是否减少了自然语言关键词路由？
- 它是否让 LLM 更清楚地看到能力空间？
- 它是否让任务更容易被拆解、组合、并行和恢复？
- 它是否增加了 observation、trace、audit 或 eval 能力？
- 它是否把副作用关进确定性边界？
- 它是否让 sub-agent 从架构中心回到 capability provider 的位置？
- 它是否能在用户换说法、换顺序、混合多个目标时仍然成立？

如果答案是否定的，这个改动很可能是在把系统拉回固定工作流，而不是推向营销通用智能体。

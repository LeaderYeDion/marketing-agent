# Project Goal

本项目的长期目标是把营销智能体从“业务 demo”演进为一个可治理、可评估、可恢复的 planner-worker harness。

核心原则：

1. LLM 负责自然语言理解、目标识别、任务拆解和 worker 选择。
2. Java harness 负责工程约束、权限边界、状态机、执行调度、审计、恢复和评估。
3. Worker 是 planner 可见的可执行工作契约，对应主流架构里的 tool/action surface。
4. WorkerProvider 是 worker 的运行时适配器，可以绑定 deterministic code、业务 API、sub-agent、delegation router 或 HITL flow。
5. Sub-agent 是上下文隔离和任务委派机制，不是所有业务能力的唯一封装方式。
6. Skill 是按需加载的过程知识包，不是 worker，不是权限入口，也不是 sub-agent 描述。

## 目标架构

```text
User Request
  -> ContextAssembler
      -> compact worker catalog
      -> compact sub-agent profiles
      -> workspace refs
  -> TaskPlanner
      -> TaskGraph
          -> TaskNode.workerName
  -> MarketingHarness
      -> WorkerRegistry
      -> WorkerProvider
          -> deterministic provider
          -> sub-agent-backed provider
          -> delegate_task provider
          -> HITL / approval flow
      -> Observation
      -> Recovery / Audit / Telemetry / Eval
```

Worker 是 planner-worker 架构里的 worker contract。它不是抽象能力集合，也不是一组 skill 的组合描述。每个 `TaskNode.workerName` 必须指向一个可执行、可验证、可审计的 worker manifest。

## Worker

Worker 描述“系统可以通过哪个受控入口完成某件事”。它给 planner、validator、policy、harness 和 eval 使用。

Worker manifest 应包含：

- `name`
- `description`
- `provider`
- `executionMode`: `deterministic` / `react` / `delegate`
- `requiredInputs`
- `inputSchema`
- `outputSchema`
- `permissions`
- `sideEffects`
- `requiresHumanApproval`
- `riskLevel`
- `composableWith`
- `fallbacks`
- `preconditions`
- `postconditions`
- `skillRefs`
- `evalSuites`

Worker 不承载长提示词或详细流程知识。详细流程放到 skill package，由执行上下文按需读取。

## WorkerProvider

`WorkerProvider` 是 worker 的运行时适配器。一个 provider 可以支持一个 worker，也可以支持一组强相关 worker。

当前推荐分类：

- 业务 worker provider：例如规则问答、文案生成、报名预览、报名执行。
- Sub-agent-backed provider：worker contract 稳定，但内部由 sub-agent 完成开放式推理。
- Delegation provider：`delegate_task`，把任务委派给指定 sub-agent。
- Deterministic provider：副作用、审批、幂等、审计要求强的动作优先走确定性实现。

这比“所有东西都做成 sub-agent”更稳：sub-agent 解决 context isolation，worker 解决 planner 可见契约、权限治理和 eval。

## Sub-agent

Sub-agent 是受控的上下文隔离执行体。主 harness 不直接把所有 sub-agent 暴露成自由动作，而是通过 `delegate_task` worker 委派。

每个 sub-agent 需要声明 `SubAgentProfile`：

- `name`
- `description`
- `allowedTools`
- `permissionProfile`
- `allowedSkills`
- `maxSteps`
- `maxTokens`
- `outputSchema`

Planner 通过 `delegate_task` 的 worker manifest 和 sub-agent profile 判断何时委派，以及委派给谁。Planner 不通过 skill 描述 sub-agent。

## Skill

Skill 是执行期按需加载的过程知识包。

主 planner 默认不看 skill 全文，也不输出 `skillHints`。Skill metadata 只在 worker runtime、provider 或 sub-agent 的隔离执行上下文中暴露。完整 `SKILL.md` 只有当执行 agent 判断相关时才读取。

Skill 可以描述：

- 操作流程
- 领域规则
- 工具使用方式
- 输出格式
- references/scripts/assets

Skill 不可以：

- 授权副作用
- 绕过 worker manifest
- 作为 planner 的执行节点
- 替代 sub-agent profile

## 与 Deep Agents 的关系

deepagents 的主流形态是：

```text
main agent
  -> tools
  -> task tool
      -> subagents
  -> skills
  -> filesystem
  -> todos
```

本项目对应关系：

```text
deepagents tool       ~= Worker / WorkerProvider
deepagents task tool  ~= delegate_task worker
deepagents subagent   ~= SubAgent + SubAgentProfile
deepagents skill      ~= Skill Package
deepagents filesystem ~= AgentWorkspace
deepagents todos      ~= future PlanMemory / Todo
```

因此本项目使用 Worker 对齐 planner-worker 和 tool/action surface 的主流心智模型。

## 架构边界

1. Planner 只能选择 worker catalog 中存在的 worker。
2. Planner 可以选择 `delegate_task`，并通过 `agentName` 指定 sub-agent。
3. Planner 不读取 skill 全文，不证明 skill 是否适用。
4. WorkerProvider 负责把 worker contract 转换成具体执行。
5. 副作用 worker 必须经过 policy、HITL、idempotency、audit 和状态机。
6. 所有 provider、sub-agent、tool 结果都归一化为 Observation。
7. Eval 不只评估最终答案，也评估 worker choice、dependency、HITL、observation、workspace refs 和 recovery。

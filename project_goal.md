# Project Goal

本项目的长期目标是把营销智能体从“业务 demo”演进为一个可治理、可评估、可恢复的 planner-worker harness。

现阶段最重要的是项目的架构，而不是各模块内部实现细节；内部实现细节会在后续由不同开发人员并行开发和演进，但所有实现都必须服从统一的架构边界、契约、观测和评测标准。

## Core Principles

1. LLM 负责自然语言理解、目标识别、任务拆解和 worker 选择。
2. Java harness 负责工程约束、权限边界、状态机、执行调度、审计、恢复和评估。
3. Worker 是 planner 可见的可执行工作契约，对应主流架构里的 tool/action surface。
4. WorkerProvider 是 worker 的运行时适配器，可以绑定 deterministic code、业务 API、sub-agent、delegation router 或 HITL flow。
5. Sub-agent 是上下文隔离和任务委派机制，不是所有业务能力的唯一封装方式。
6. Skill 是按需加载的过程知识包，不是 worker，不是权限入口，也不是 sub-agent 描述。
7. Eval 是一等架构模块，不是测试补丁；所有 planner、worker、RAG、HITL、recovery、workspace 行为都应该有可评测契约。

## Target Architecture

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
          -> DelegateTaskWorkerProvider
          -> HITL / approval flow
      -> Observation
      -> Recovery / Audit / Telemetry
  -> Eval Harness
      -> planner eval
      -> worker eval
      -> RAG eval
      -> end-to-end scenario eval
      -> regression dashboard
```

## Worker

Worker 描述“系统可以通过哪个受控入口完成某件事”。每个 `TaskNode.workerName` 必须指向一个可执行、可验证、可审计的 worker manifest。

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

## Sub-agent

Sub-agent 是受控的上下文隔离执行体。主 harness 不直接把所有 sub-agent 暴露成自由动作，而是通过 `delegate_task` worker 委派。

Planner 通过 `delegate_task` 的 worker manifest 和 sub-agent profile 判断何时委派，以及委派给谁。Planner 不通过 skill 描述 sub-agent。

## Skill

Skill 是执行期按需加载的过程知识包。

主 planner 默认不看 skill 全文，也不输出 `skillHints`。Skill metadata 只在 worker runtime、provider 或 sub-agent 的隔离执行上下文中暴露。完整 `SKILL.md` 只有当执行 agent 判断相关时才读取。

## Eval Architecture

当前系统已经将 `marketing-agent-eval` 升级为独立架构层：`EvalSuite` / `EvalDataset` 负责组织用例，`EvalRunner` 负责统一执行，`SystemTraceCapture` 负责从系统响应中捕获统一 trace，`MetricEvaluator` 负责扩展指标，`EvalReport` 负责输出报告。`GoldenCaseEvaluator` 仅保留为兼容适配器，不再作为评测架构中心。

评测层使用统一数据集、统一 trace、统一指标和统一报告评价系统行为。

评测至少分为四层：

1. Planner Eval
   - plan 是否选择了正确 worker。
   - DAG 是否有合理依赖、并行关系和执行顺序。
   - 是否正确使用 `delegate_task`。
   - 是否避免副作用 worker 越权执行。
   - 是否在缺少输入时进入 clarification / waiting flow。

2. Worker Eval
   - worker 输出是否符合 `outputSchema`。
   - Observation 是否包含必要 evidence、artifacts、workspace refs。
   - 是否遵守权限、HITL、幂等和 side-effect 边界。
   - 错误、重试和 recovery 是否符合预期。

3. RAG Eval
   - retrieval relevance：召回内容是否与问题相关。
   - recall / hit rate：标准答案所需证据是否被召回。
   - precision：召回内容中无关内容比例是否可控。
   - groundedness：最终答案是否被检索证据支持。
   - citation accuracy：引用是否指向真实证据片段。

4. End-to-End Eval
   - 用户目标是否被完成。
   - 最终答案是否正确、完整、可解释。
   - 中间 worker choice、HITL、workspace、recovery 是否符合架构契约。
   - 回归版本之间是否出现质量退化。

Eval 不是只看最终 answer 的字符串匹配，而是要评估 plan、worker choice、dependency、RAG evidence、Observation、HITL、workspace refs、recovery 和最终答案的组合质量。

## Deep Agents Mapping

```text
deepagents tool       ~= Worker / WorkerProvider
deepagents task tool  ~= delegate_task worker
deepagents subagent   ~= SubAgent + SubAgentProfile
deepagents skill      ~= Skill Package
deepagents filesystem ~= AgentWorkspace
deepagents todos      ~= future PlanMemory / Todo
```

本项目使用 Worker 对齐 planner-worker 和 tool/action surface 的主流心智模型。

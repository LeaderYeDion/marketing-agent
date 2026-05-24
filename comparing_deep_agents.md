# 本项目与 langchain-ai/deepagents 的架构对比

## 结论

deepagents 的核心不是某个名词，而是一套 agent harness：main agent 通过 tools、task tool、subagents、skills、filesystem、todos 和 middleware 完成复杂任务。

本项目更适合用 planner-worker 表达：

```text
TaskPlanner
  -> TaskGraph
      -> TaskNode.workerName
          -> WorkerProvider
              -> deterministic code / sub-agent / delegation router / HITL flow
```

因此本项目统一使用 Worker 术语。Worker 更接近 deepagents 里的 tool/action surface，也更容易和主流 planner-worker 架构对应。

## deepagents 怎么做

deepagents 的主 agent 在工具循环中工作：

- 用 `write_todos` 维护动态计划。
- 用 filesystem 管理上下文和中间产物。
- 用 `task` tool 委派 sub-agent。
- 用普通 tools 完成具体动作。
- 用 skills 做 progressive disclosure。

Sub-agent 的核心价值是 context quarantine。主 agent 通过 sub-agent 的 `name` 和 `description` 判断是否委派，而不是通过 skill 描述 sub-agent。

Skill 的核心价值是按需知识加载。agent 先看到极简 metadata，需要时再读取完整 `SKILL.md` 和 references/scripts/assets。

## 本项目对应关系

| deepagents | 本项目 |
| --- | --- |
| tool/action surface | Worker / WorkerProvider |
| task tool | `delegate_task` worker |
| subagent config | `SubAgentProfile` |
| skills | Skill Package |
| filesystem | `AgentWorkspace` |
| todo list | future PlanMemory / Todo |
| middleware stack | future WorkerMiddleware / SkillDisclosureMiddleware / SubAgentDelegationMiddleware |
| eval harness | `EvalSuite` / `EvalDataset` / `EvalRunner` / `SystemTraceCapture` / `MetricEvaluator` / `EvalReport` |

## 为什么用 Worker

旧的能力类命名容易被理解成“抽象能力集合”或“一组 skill 的组合描述”。这会让 DAG 节点字段变得难理解：一个节点到底是在选择抽象分类，还是选择可执行动作？

Worker 更直接：

```text
TaskNode.workerName = planner 选择哪个 worker contract 来完成这个子任务
```

Worker 不是 worker 实例，而是 planner 可见的 worker contract。WorkerProvider 是运行时适配器。具体实现可以是 deterministic code、sub-agent-backed provider、delegation provider 或审批流程。

## 当前架构

```text
WorkerDescriptor
  -> name / description / provider / executionMode
  -> requiredInputs / inputSchema / outputSchema
  -> permissions / risk / approval / sideEffects
  -> skillRefs / evalSuites

WorkerProvider
  -> supports(WorkerDescriptor)
  -> execute(WorkerExecutionRequest, MarketingRequest)
  -> Observation
```

当前 provider 类型：

- `RuleInquiryWorkerProvider`: `rule_inquiry`，内部由 `InquiryAgent` 执行。
- `CopywritingWorkerProvider`: `copywriting` / `notification_copywriting`。
- `ActivityEnrollmentWorkerProvider`: 表格摘要、商品查询、规则检查、报名预览、报名执行。
- `SubAgentDelegationWorkerProvider`: `delegate_task`，根据 `agentName` 委派 sub-agent。

## 与 deepagents 的主要差距

1. deepagents 的计划是动态 todo，本项目当前主要是一次性 TaskGraph。
2. deepagents 的 `task` tool 是主 agent 工具，本项目把它映射为 `delegate_task` worker。
3. deepagents 的 middleware 体系更完整，本项目仍有不少逻辑集中在 `MarketingHarness`。
4. deepagents 的 skill progressive disclosure 更原生，本项目刚完成执行期隔离的基础。
5. 本项目的 Worker / Observation / PendingAction 更适合严肃业务治理，但通用 agent runtime 还需要继续产品化。

## 推荐演进

短期：

- 保留 TaskGraph。
- 让 planner 稳定输出 `workerName`。
- 增强 `delegate_task` 测试。
- 继续拆分 Worker Manifest 与 Skill Package。

中期：

- 引入 PlanMemory / Todo。
- 引入 WorkerMiddleware。
- 引入 SkillDisclosureMiddleware。
- 引入 SubAgentDelegationMiddleware。

长期：

- 形成 deepagents 风格的默认 runtime：worker catalog、workspace、delegation、skills、todo、summarization、HITL、permission、trace、eval 全部成为 harness 默认能力。

# Next TODO

现阶段最重要的是项目的架构，而不是各模块内部实现细节；内部实现细节会在后续由不同开发人员并行开发和演进，但所有实现都必须服从统一的架构边界、契约、观测和评测标准。

本文件记录下一阶段的架构优先级。当前重点不是把某个 provider、RAG 算法或 planner prompt 一次性做完，而是先把系统边界、运行契约、观测数据和评测闭环定义清楚，让后续多人并行开发不会把系统重新写成散点功能。

## P0：评测架构成为一等模块

当前 `marketing-agent-eval` 已有 `GoldenCaseEvaluator`、`EvalCase` 和 golden cases，但它更像最小回归测试骨架，还不足以回答这些关键问题：

- planner 的 plan 好不好？
- worker 选择是否正确？
- worker 输出是否符合预期？
- RAG 召回内容是否相关？
- RAG 命中率、召回率、准确率如何？
- 最终答案是否基于证据？
- HITL、recovery、workspace refs 是否按架构契约工作？

P0 已完成：`marketing-agent-eval` 现在是一等评测架构模块，而不是只做最终答案字符串匹配的 golden case 骨架。现有落点如下：

- `EvalSuite` / `EvalDataset` 统一组织评测套件和数据集。
- `EvalRunner` 统一执行入口，负责调用被测系统、捕获 trace、调度指标评测器并生成 `EvalReport`。
- `EvalSystemRunner` 隔离被测系统适配，当前由 `MarketingAgentEvalSystemRunner` 对接 `MarketingAgentService`。
- `SystemTraceCapture` 将 `MarketingResponse.metadata()` 规范化为 `SystemTrace`，覆盖 planner output、TaskGraph、worker sequence、Observation、evidence / artifacts、workspace refs、RAG retrieved chunks、HITL pending actions、recovery trace、telemetry / audit trace。
- `MetricEvaluator` 是后续并行扩展指标的统一契约，已内置 `PlannerMetricEvaluator`、`WorkerMetricEvaluator`、`RagMetricEvaluator`、`EndToEndMetricEvaluator` 四层评测器。
- `GoldenCaseEvaluator` 已退化为兼容适配器，委托新的 `EvalRunner`，不再承载评测架构职责。

P0 的实现边界是先把 eval architecture 固化为稳定契约；具体业务指标的打分细节可继续在对应 evaluator 内演进，但不需要再新增“第一阶段/第二阶段”的架构迁移。

### P0-1 Eval Harness

定义统一评测入口：

```text
EvalSuite
  -> EvalDataset
  -> EvalRunner
  -> System Trace Capture
  -> Metric Evaluators
  -> EvalReport
```

EvalRunner 不只读取最终 answer，还必须读取：

- planner output
- TaskGraph
- worker sequence
- Observation
- evidence / artifacts
- workspace refs
- RAG retrieved chunks
- HITL pending actions
- recovery decisions
- telemetry / audit trace

### P0-2 Planner Eval

Planner Eval 负责评价计划质量。

核心维度：

- worker choice 是否正确。
- DAG 拆解是否合理。
- 依赖关系是否正确。
- 是否正确使用 `delegate_task`。
- 是否避免副作用 worker 越权执行。
- 缺少输入时是否进入 waiting / clarification。
- plan 是否可执行、可验证、可恢复。

推荐数据形态：

```text
query
expectedWorkers
forbiddenWorkers
expectedDependencies
expectedDelegateAgent
expectedMissingInputs
expectedHarnessStatus
```

### P0-3 Worker Eval

Worker Eval 负责评价每个 worker 的执行结果是否符合 contract。

核心维度：

- 输出是否满足 `outputSchema`。
- Observation status 是否正确。
- evidence 是否完整。
- artifact 是否可读。
- workspace refs 是否可恢复。
- side-effect worker 是否经过 HITL、idempotency、audit。
- failure / retry / recovery 是否符合预期。

Worker Eval 不应该只比较 answer 字符串，而应该基于 Worker Manifest、Observation Contract 和 trace 做结构化评估。

### P0-4 RAG Eval

RAG Eval 单独作为一层，不混在最终答案评测里。

核心指标：

- relevance：召回内容与 query 是否相关。
- recall / hit rate：标准证据是否被召回。
- precision：召回列表中无关内容比例。
- answer groundedness：最终答案是否被证据支持。
- citation accuracy：引用路径和片段是否真实。
- coverage：是否覆盖用户问题中的关键约束。

推荐数据形态：

```text
query
goldEvidenceIds
goldAnswerFacts
forbiddenFacts
expectedCitationPaths
```

### P0-5 End-to-End Eval

E2E Eval 负责评价用户目标是否被完整完成。

核心维度：

- final answer correctness
- plan quality
- worker execution quality
- RAG groundedness
- HITL correctness
- recovery behavior
- workspace recoverability
- regression stability

E2E Eval 可以调用 planner eval、worker eval、RAG eval 的结果，但不能替代它们。

## P1：Worker 架构继续收口

Worker 是 planner 可调度的执行契约。后续继续完成：

1. 将资源目录从 `skills/{name}/manifest.yaml` 迁移到 `workers/{name}/manifest.yaml`。
2. 保留短期兼容读取：先读 `workers`，再回退 `skills`。
3. 用标准 YAML parser 替换当前简单 parser。
4. Worker manifest 只保留 schema、权限、风险、provider、skillRefs 等机器字段。
5. Skill package 只承载执行期过程知识。

## P2：Sub-agent 与 delegate_task

`delegate_task` 是一个特殊 worker，对应 deepagents 的 `task` tool。

后续动作：

1. 为 `delegate_task` 增加专门 planner/e2e eval case。
2. 验证 planner 返回 `workerName=delegate_task` 时必须提供合法 `agentName`。
3. 将 sub-agent invocation 过程写入 `/subagents/{invocationId}/result.json`。
4. 增强 `general_purpose_agent`，从 deterministic stub 演进为只读 ReAct sub-agent。
5. 为 `inquiry_agent`、`general_purpose_agent` 增加 profile validator。

## P3：Skill Progressive Disclosure

主 planner 不判断 skill 细节。Skill 是否适用由 worker runtime 或 sub-agent 在隔离上下文中按需判断。

后续动作：

1. 增加 `SkillPackageRegistry`。
2. 增加 `SkillDisclosureMiddleware`。
3. `read_skill` 受 `worker.skillRefs`、`subAgent.allowedSkills` 和权限白名单约束。
4. 禁止 planner 输出 `skillHints`，除非后续作为弱信号并由 runtime 做交集过滤。

## P4：Middleware-first Runtime

把 harness 中散落的生命周期逻辑下沉到 middleware：

- `WorkerMiddleware`
- `SubAgentDelegationMiddleware`
- `SkillDisclosureMiddleware`
- `PlanMemoryMiddleware`
- `WorkspaceContextMiddleware`
- `HumanApprovalMiddleware`
- `EvalTraceMiddleware`

## P5：Worker-scoped ReAct Runtime

为开放式但仍需 worker contract 治理的能力增加 `ReactWorkerRuntime`。

适用场景：

- 规则问答
- 证据整理
- 表格分析
- 多工具检索
- 文案生成

不适用场景：

- 直接副作用执行
- 无审批写操作
- 强幂等业务 API

## Architecture Guardrails

1. Planner 只能选择 worker catalog 中存在的 worker。
2. Planner 可以选择 `delegate_task`，并通过 `agentName` 指定 sub-agent。
3. Planner 不读取 skill 全文，不证明 skill 是否适用。
4. WorkerProvider 负责把 worker contract 转换成具体执行。
5. 副作用 worker 必须经过 policy、HITL、idempotency、audit 和状态机。
6. 所有 provider、sub-agent、tool 结果都归一化为 Observation。
7. Eval 不只评估最终答案，也评估 planner、worker、RAG、HITL、workspace 和 recovery。

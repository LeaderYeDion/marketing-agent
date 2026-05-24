# Next TODO

本阶段最高优先级是完成 Worker 概念和代码收口，使项目更符合 planner-worker / tool-action surface 的主流架构表达。

## P0：Worker 定位重构

目标：

- 用 Worker 作为 planner 可调度的执行契约。
- 用 WorkerProvider 作为运行时适配器。
- 用 WorkerManifest 作为机器可验证 manifest。
- 将 `TaskNode` 的执行字段统一为 `workerName`。
- 将日志、metadata、测试、eval 和文档中的执行契约术语全部统一为 worker。

当前状态：

- `WorkerDescriptor` / `WorkerRegistry` / `WorkerProvider` / `WorkerExecutionRequest` 已完成代码命名迁移。
- `WorkerManifestDescriptor` / `WorkerManifestRegistry` / `WorkerManifestValidator` 已完成代码命名迁移。
- `TaskPlanner` 已改为输出 `workerName`。
- `MarketingHarness`、middleware、policy、recovery、observation、eval 已改为 worker 术语。
- `delegate_task` 已作为 worker 暴露给 planner。
- 测试已切换为 `workerName` / `worker_name`。

## P0-1 Worker Manifest 与 Skill Package 继续拆分

Worker manifest 是 planner/validator/policy/harness/eval 可见的运行时契约。Skill package 是执行期按需加载的过程知识。

后续动作：

1. 将资源目录从 `skills/{name}/manifest.yaml` 逐步迁移到 `workers/{name}/manifest.yaml`。
2. 保留短期兼容读取：先读 `workers`，再回退 `skills`。
3. 用标准 YAML parser 替换当前简单 `key: value` parser。
4. `SkillRegistry` 继续读取 `SKILL.md` / `skill.md`，只提供执行期 progressive disclosure。
5. Worker manifest 只保留 schema、权限、风险、provider、skillRefs 等机器字段。

验收：

- planner prompt 中只出现 Worker catalog，不出现 skill 全文。
- validator 能校验 worker provider、schema、权限和 fallback 引用。
- worker manifest 与 skill package 的职责不再混在一起。

## P0-2 SubAgent Profile 与 delegate_task

`delegate_task` 是一个特殊 worker，对应 deepagents 的 `task` tool。

后续动作：

1. 为 `delegate_task` 增加专门 e2e 测试。
2. 验证 planner 返回 `workerName=delegate_task` 时必须提供合法 `agentName`。
3. 将 sub-agent invocation 过程写入 `/subagents/{invocationId}/result.json`。
4. 增强 `general_purpose_agent`，从 deterministic stub 演进为只读 ReAct sub-agent。
5. 为 `inquiry_agent`、`general_purpose_agent` 增加 profile validator。

验收：

- Planner 可以稳定规划 `delegate_task`。
- Runtime 可以执行委派，并产出标准 Observation。
- 主上下文只接收 summary 和 workspace refs，不接收子 agent 全量过程。

## P0-3 Skill Progressive Disclosure

主 planner 不应该判断 skill 细节。Skill 是否适用由 worker runtime 或 sub-agent 在隔离上下文中按需判断。

后续动作：

1. 增加 `SkillPackageRegistry`，将它从旧 `SkillRegistry` 中拆出。
2. 增加 `SkillDisclosureMiddleware`，负责执行上下文内的 skill summary 注入。
3. `read_skill` 需要受 `worker.skillRefs`、`subAgent.allowedSkills` 和权限白名单约束。
4. 禁止 planner 输出 `skillHints`，P1 如需弱提示也必须由 runtime 做交集过滤。

验收：

- planner prompt 不随 skill 数量线性膨胀。
- sub-agent 只看到 profile 允许的 skill。
- provider/sub-agent 可按需读取完整 `SKILL.md`。

## P0-4 Planner 改造成 Worker + Delegation 感知

Planner 的职责是选择 worker、组织依赖、判断是否需要委派，而不是执行细节推理。

Planner 规则：

1. 只选择 Worker Manifest 中存在的 worker。
2. 对重上下文任务优先考虑 `delegate_task`。
3. 如果选择 `delegate_task`，必须指定合法 `agentName`、`task`、`expectedOutput`。
4. 不输出 `skillHints`。
5. 不使用副作用 worker 替代 preview/approval worker。

后续动作：

1. 增加 planner 单测覆盖 `delegate_task`。
2. 增加 unknown worker / unknown agent / skillHints 的 validator 测试。
3. 增加 worker schema-aware validation。
4. 将 `requested_workers` 作为显式 fallback 输入保留。

## P1：Middleware-first Runtime

目标是把 harness 中散落的生命周期逻辑下沉到 middleware：

- `WorkerMiddleware`
- `SubAgentDelegationMiddleware`
- `SkillDisclosureMiddleware`
- `PlanMemoryMiddleware`
- `WorkspaceContextMiddleware`
- `HumanApprovalMiddleware`

这会让 worker 调用、权限检查、skill disclosure、workspace refs、HITL 和 trace 都进入统一生命周期。

## P2：Worker-scoped ReAct Runtime

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

## P3：Eval 与可观测性

新增 eval 维度：

- worker choice
- dependency graph
- delegate_task agentName
- skill disclosure budget
- HITL boundary
- observation grounding
- workspace refs
- recovery path

## 命名约定

以后核心架构命名统一为：

```text
WorkerDescriptor
WorkerProvider
WorkerRegistry
WorkerManifest
WorkerExecutionRequest
workerName
worker_name
workerSequence
```

保留兼容层时只能作为迁移代码存在，不能再作为新的架构术语写入文档或日志。

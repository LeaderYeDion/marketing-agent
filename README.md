# Marketing Agent

基于 Spring Boot、Maven 多模块和 Spring AI Alibaba Graph 的营销智能助手工程。

当前架构的中心已经从“按关键词或 intent 路由到某个 agent”调整为“LLM 规划任务图，harness 负责校验、调度、观测、恢复和权限边界”。系统的目标是让自然语言理解、任务拆解和行动路径选择由模型完成，Java 代码只承担可验证的工程约束与执行运行时。

## 构建环境

- JDK：`%USERPROFILE%\.jdks\ms-21.0.11`
- Maven：通过 Maven Wrapper 固定为 `3.9.11`

Windows 下建议使用项目内脚本，它会临时设置 `JAVA_HOME` 并调用 `mvnw.cmd`：

```powershell
.\mvn-jdk21.cmd test package
```

也可以手动执行：

```powershell
$env:JAVA_HOME="$HOME\.jdks\ms-21.0.11"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -version
.\mvnw.cmd test package
```

## 模块

- `marketing-agent-api`：对外请求、响应、SSE 事件 DTO。
- `marketing-agent-core`：harness 核心运行时，包含 task graph、planner、capability、memory、observation、policy、recovery、audit、telemetry、RAG 和 sub-agent provider。
- `marketing-agent-app`：Spring Boot 启动模块，对外提供 JSON 和 SSE 接口。
- `marketing-agent-eval`：离线评测入口和 golden cases。

## 核心流程

```text
user input
  -> ContextAssembler 汇总会话、记忆、可见对象、待确认动作、能力目录
  -> TaskPlanner 调用 LLM 生成结构化 DAG
  -> MarketingHarness 校验 capability、依赖和风险
  -> 可并行 ready nodes 进入 capability/sub-agent 执行
  -> Observation 写入工作记忆
  -> RecoveryPolicyEngine 决定重试、fallback、追问或暂停
  -> 汇总回答 / HITL 确认 / 后续续跑
```

## 启动

```powershell
.\mvn-jdk21.cmd -pl marketing-agent-app -am spring-boot:run
```

## 普通调用

```bash
curl -X POST http://localhost:8080/api/v1/marketing-agent/chat \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"帮我为新品设计一场会员拉新活动\",\"channel\":\"社群\",\"product\":\"智能咖啡机\",\"audience\":\"一二线城市白领\",\"goals\":[\"拉新\",\"转化\"]}"
```

## SSE 调用

```bash
curl -N -X POST http://localhost:8080/api/v1/marketing-agent/chat/stream \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"帮我写一段优惠券活动话术\",\"channel\":\"短信\",\"product\":\"会员月卡\",\"audience\":\"沉睡用户\"}"
```

## 扩展方向

- 新增营销能力时优先补充 `SkillDescriptor` / capability manifest，声明输入、输出契约、权限、风险、可组合能力和 fallback。
- 业务执行逻辑优先封装为 `CapabilityProvider` 或 sub-agent provider，避免在 planner 或 harness 中写自然语言关键词分支。
- 高风险副作用必须经过 `PendingActionStateMachine` 和 HITL 边界。
- planner 的评测应围绕复杂跨域任务、并行节点、依赖顺序、恢复路径和追问质量持续扩展。

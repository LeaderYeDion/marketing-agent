# Marketing Agent

基于 Spring Boot、Maven 多模块和 Spring AI Alibaba Graph 的营销智能助手样例。

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
- `marketing-agent-core`：Graph 核心逻辑，包含 state、context、node、edge、RAG 服务和 agent service。
- `marketing-agent-app`：Spring Boot 启动模块，对外提供普通 JSON 和 SSE 接口。

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

## 下一步

当前样例使用内存 RAG 和规则化生成，便于离线运行。接入真实智能能力时，可以在 `marketing-agent-core` 中替换：

- `RagService`：接入 Milvus、Elasticsearch、PGVector 或 Spring AI VectorStore。
- `MarketingNodes.generateNode()`：接入 DashScope/OpenAI 兼容 ChatModel。
- `MarketingEdges.routeAfterIntent()`：扩展多分支营销流程，例如风控、预算校验、投放排期。

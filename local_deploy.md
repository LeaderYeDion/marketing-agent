# Local Deployment Guide

这份说明用于在 Windows 本地启动后端，并通过浏览器页面或终端 SSE 客户端验证完整链路。

## 1. 准备 LLM Key

当前 planner、inquiry agent、activity enroll agent 都通过 Spring AI `ChatModel` 调用 Gemini。启动前建议设置：

```powershell
$env:GEMINI_API_KEY="your-gemini-api-key"
$env:GEMINI_MODEL="gemini-2.5-flash-lite"
```

如果没有设置 `GEMINI_API_KEY`，应用会使用本地占位 key 启动，方便你先打开页面和检查接口；实际聊天时 LLM provider 会返回认证错误。要验证真实 planner 和 sub-agent 链路，必须设置真实 key。

如果本机访问 Google/Gemini 需要代理，不要只依赖 IDEA 或 JVM 的系统代理自动发现。应用支持显式代理配置：

```powershell
$env:LLM_PROXY_ENABLED="true"
$env:LLM_PROXY_HOST="127.0.0.1"
$env:LLM_PROXY_PORT="6268"
```

也可以在脚本参数里传入：

```powershell
.\scripts\run-backend.ps1 -ProxyHost 127.0.0.1 -ProxyPort 6268
```

## 2. 启动后端和网页控制台

推荐方式：

```powershell
.\scripts\run-backend.ps1
```

或指定端口：

```powershell
.\scripts\run-backend.ps1 -Port 8081
```

也可以直接用 Maven：

```powershell
$env:JAVA_HOME="$HOME\.jdks\ms-21.0.11"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:SERVER_PORT="8080"
.\mvn-jdk21.cmd -pl marketing-agent-app -am -DskipTests package
& "$env:JAVA_HOME\bin\java.exe" -jar .\marketing-agent-app\target\marketing-agent-app-0.0.1-SNAPSHOT.jar
```

启动成功后打开：

```text
http://localhost:8080/
```

这个页面由 Spring Boot 静态资源直接托管，不需要额外启动前端 dev server。

## 3. 浏览器聊天验证

页面提供：

- 对话输入。
- SSE 开关。
- conversationId / userId。
- channel / product / audience / goals。
- variables JSON。
- SSE 事件日志。
- HITL 待确认动作按钮。

可以先输入：

```text
帮我判断活动报名规则有哪些，并写一段社群通知。
```

如果要测试显式变量，可以在 `Variables JSON` 填入：

```json
{
  "activity_id": "A100"
}
```

## 4. 终端 SSE 聊天验证

另开一个 PowerShell 窗口：

```powershell
node .\scripts\chat-sse.mjs
```

也可以通过 npm：

```powershell
npm run chat
```

如果后端不是 8080：

```powershell
$env:MARKETING_AGENT_URL="http://localhost:8081/api/v1/marketing-agent/chat/stream"
node .\scripts\chat-sse.mjs
```

可选上下文环境变量：

```powershell
$env:MARKETING_AGENT_CHANNEL="社群"
$env:MARKETING_AGENT_PRODUCT="会员月卡"
$env:MARKETING_AGENT_AUDIENCE="沉睡用户"
$env:MARKETING_AGENT_GOALS="拉新,转化"
$env:MARKETING_AGENT_VARIABLES='{"activity_id":"A100"}'
```

终端命令：

- `/new`：创建新会话。
- `/exit`：退出。

如果返回了待确认动作，终端会提示输入 `approve`、`reject` 或 `skip`。

## 5. 普通 HTTP 调用

```powershell
curl.exe -X POST http://localhost:8080/api/v1/marketing-agent/chat `
  -H "Content-Type: application/json" `
  -d "{\"query\":\"帮我写一段优惠券活动社群通知\",\"channel\":\"社群\",\"product\":\"会员月卡\",\"audience\":\"沉睡用户\"}"
```

SSE：

```powershell
curl.exe -N -X POST http://localhost:8080/api/v1/marketing-agent/chat/stream `
  -H "Content-Type: application/json" `
  -H "Accept: text/event-stream" `
  -d "{\"query\":\"帮我判断活动报名规则有哪些\"}"
```

## 6. 打包 Jar

```powershell
.\mvn-jdk21.cmd -pl marketing-agent-app -am package
& "$HOME\.jdks\ms-21.0.11\bin\java.exe" -jar .\marketing-agent-app\target\marketing-agent-app-0.0.1-SNAPSHOT.jar
```

## 7. Docker 可选运行

先打包 Jar：

```powershell
.\mvn-jdk21.cmd -pl marketing-agent-app -am package
```

构建镜像：

```powershell
docker build -t marketing-agent:local .
```

运行容器：

```powershell
docker run --rm -p 8080:8080 `
  -e GEMINI_API_KEY="$env:GEMINI_API_KEY" `
  -e GEMINI_MODEL="gemini-2.5-flash-lite" `
  -e LLM_PROXY_ENABLED="$env:LLM_PROXY_ENABLED" `
  -e LLM_PROXY_HOST="$env:LLM_PROXY_HOST" `
  -e LLM_PROXY_PORT="$env:LLM_PROXY_PORT" `
  marketing-agent:local
```

然后访问：

```text
http://localhost:8080/
```

## 8. 当前本地部署形态

```text
Browser or Node CLI
  -> localhost Spring Boot app
  -> /api/v1/marketing-agent/chat or /chat/stream
  -> MarketingHarness
  -> LLM TaskPlanner
  -> Capability providers / sub-agents
  -> SSE events and final response
```

这个形态已经可以用于本地验证 harness planner、task graph、capability 编排、SSE 事件、HITL 反馈和最终回答。

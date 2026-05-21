const apiBase = "/api/v1/marketing-agent";
const state = {
  conversationId: localStorage.getItem("marketing-agent.conversationId") || crypto.randomUUID(),
  busy: false,
  finalResponseRendered: false
};

const $ = (id) => document.getElementById(id);

const fields = {
  conversationId: $("conversationIdInput"),
  userId: $("userIdInput"),
  channel: $("channelInput"),
  product: $("productInput"),
  audience: $("audienceInput"),
  goals: $("goalsInput"),
  variables: $("variablesInput"),
  query: $("queryInput"),
  stream: $("streamToggle"),
  events: $("events"),
  messages: $("messages"),
  pendingActions: $("pendingActions"),
  send: $("sendButton"),
  status: $("status")
};

fields.conversationId.value = state.conversationId;

$("chatForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  const query = fields.query.value.trim();
  if (!query || state.busy) {
    return;
  }
  fields.query.value = "";
  addMessage("user", query);
  await sendRequest(buildRequest(query), fields.stream.checked);
});

$("newConversationButton").addEventListener("click", () => {
  state.conversationId = crypto.randomUUID();
  localStorage.setItem("marketing-agent.conversationId", state.conversationId);
  fields.conversationId.value = state.conversationId;
  fields.pendingActions.hidden = true;
  fields.pendingActions.innerHTML = "";
  addMessage("system", "已创建新会话。");
});

$("clearButton").addEventListener("click", () => {
  fields.messages.innerHTML = "";
  fields.events.textContent = "";
  fields.pendingActions.hidden = true;
  fields.pendingActions.innerHTML = "";
});

fields.conversationId.addEventListener("change", () => {
  const value = fields.conversationId.value.trim();
  if (value) {
    state.conversationId = value;
    localStorage.setItem("marketing-agent.conversationId", value);
  }
});

function buildRequest(query, extraVariables = {}) {
  const variables = {
    ...parseVariables(),
    ...extraVariables
  };
  return {
    conversationId: fields.conversationId.value.trim() || state.conversationId,
    userId: fields.userId.value.trim() || "local-user",
    query,
    channel: emptyToNull(fields.channel.value),
    product: emptyToNull(fields.product.value),
    audience: emptyToNull(fields.audience.value),
    goals: splitGoals(fields.goals.value),
    variables
  };
}

async function sendRequest(payload, stream) {
  setBusy(true);
  state.finalResponseRendered = false;
  fields.status.textContent = stream ? "SSE streaming..." : "requesting...";
  try {
    if (stream) {
      await streamChat(payload);
    } else {
      const response = await fetch(`${apiBase}/chat`, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(payload)
      });
      if (!response.ok) {
        throw new Error(await response.text());
      }
      renderFinalResponse(await response.json());
    }
  } catch (error) {
    addMessage("system", `请求失败：${error.message}`);
    appendEvent("error", error.message);
  } finally {
    setBusy(false);
    fields.status.textContent = "ready";
  }
}

async function streamChat(payload) {
  const response = await fetch(`${apiBase}/chat/stream`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "text/event-stream"
    },
    body: JSON.stringify(payload)
  });
  if (!response.ok || !response.body) {
    throw new Error(await response.text());
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const {done, value} = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, {stream: true});
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() || "";
    for (const part of parts) {
      handleSseBlock(part);
    }
  }
  if (buffer.trim()) {
    handleSseBlock(buffer);
  }
}

function handleSseBlock(block) {
  const lines = block.split(/\r?\n/);
  let eventName = "message";
  const dataLines = [];
  for (const line of lines) {
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }
  if (dataLines.length === 0) {
    return;
  }
  const raw = dataLines.join("\n");
  try {
    const event = JSON.parse(raw);
    appendEvent(event.type || eventName, event.message || "", event);
    if (event.type === "token" && event.message) {
      addMessage("assistant", event.message, "token");
    }
    if (event.data && event.data.response) {
      renderFinalResponse(event.data.response);
    }
  } catch {
    appendEvent(eventName, raw);
  }
}

function renderFinalResponse(response) {
  if (!response || state.finalResponseRendered) {
    return;
  }
  state.finalResponseRendered = true;
  if (response.conversationId) {
    state.conversationId = response.conversationId;
    fields.conversationId.value = response.conversationId;
    localStorage.setItem("marketing-agent.conversationId", response.conversationId);
  }
  addMessage("assistant", response.answer || "(empty response)", "final");
  renderPendingActions(extractPendingActions(response));
}

function extractPendingActions(response) {
  const observations = response?.metadata?.observations;
  if (!Array.isArray(observations)) {
    return [];
  }
  const actions = [];
  for (const observation of observations) {
    const visibleObjects = observation.visibleObjects || [];
    for (const object of visibleObjects) {
      if (object.type === "hitl_confirmation" && object.status === "pending") {
        actions.push({
          id: object.id,
          title: object.title || "待确认动作",
          summary: object.summary || "",
          data: object.data || {}
        });
      }
    }
  }
  return actions;
}

function renderPendingActions(actions) {
  if (actions.length === 0) {
    fields.pendingActions.hidden = true;
    fields.pendingActions.innerHTML = "";
    return;
  }
  fields.pendingActions.hidden = false;
  fields.pendingActions.innerHTML = "<h2>待确认动作</h2>";
  for (const action of actions) {
    const item = document.createElement("div");
    item.className = "pending-item";
    const title = document.createElement("strong");
    title.textContent = action.title;
    const summary = document.createElement("p");
    summary.textContent = action.summary;
    const buttons = document.createElement("div");
    buttons.className = "pending-buttons";
    buttons.append(
      feedbackButton("确认执行", action, "approve"),
      feedbackButton("取消", action, "reject"),
      feedbackButton("编辑 JSON 后确认", action, "edit")
    );
    item.append(title, summary, buttons);
    fields.pendingActions.append(item);
  }
}

function feedbackButton(label, action, decision) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  button.addEventListener("click", async () => {
    let editedPayload = {};
    if (decision === "edit") {
      const value = prompt("输入 edited_payload JSON", JSON.stringify(action.data, null, 2));
      if (value === null) {
        return;
      }
      try {
        editedPayload = value.trim() ? JSON.parse(value) : {};
      } catch (error) {
        addMessage("system", `edited_payload JSON 无效：${error.message}`);
        return;
      }
    }
    const variables = {
      human_feedback: {
        pending_action_id: action.id,
        visible_object_id: action.id,
        decision,
        edited_payload: editedPayload
      }
    };
    addMessage("user", `${label}：${action.title}`);
    await sendRequest(buildRequest(`${label} ${action.title}`, variables), fields.stream.checked);
  });
  return button;
}

function addMessage(role, text, meta = "") {
  const element = document.createElement("article");
  element.className = `message ${role}`;
  if (meta) {
    const metaElement = document.createElement("span");
    metaElement.className = "meta";
    metaElement.textContent = meta;
    element.append(metaElement);
  }
  element.append(document.createTextNode(text));
  fields.messages.append(element);
  fields.messages.scrollTop = fields.messages.scrollHeight;
}

function appendEvent(type, message, event = null) {
  const timestamp = new Date().toLocaleTimeString();
  const payload = event ? ` ${JSON.stringify(event)}` : "";
  fields.events.textContent += `[${timestamp}] ${type} ${message}${payload}\n`;
  fields.events.scrollTop = fields.events.scrollHeight;
}

function parseVariables() {
  const text = fields.variables.value.trim();
  if (!text) {
    return {};
  }
  try {
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch (error) {
    addMessage("system", `Variables JSON 无效，已忽略：${error.message}`);
    return {};
  }
}

function splitGoals(value) {
  return value.split(/[,，、\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function emptyToNull(value) {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function setBusy(next) {
  state.busy = next;
  fields.send.disabled = next;
}

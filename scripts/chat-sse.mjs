#!/usr/bin/env node

import readline from "node:readline/promises";
import {stdin as input, stdout as output} from "node:process";
import {randomUUID} from "node:crypto";

const endpoint = process.env.MARKETING_AGENT_URL
  || "http://localhost:8080/api/v1/marketing-agent/chat/stream";

const rl = readline.createInterface({input, output});
let conversationId = process.env.MARKETING_AGENT_CONVERSATION_ID || randomUUID();
const userId = process.env.MARKETING_AGENT_USER_ID || "local-cli";

console.log(`Marketing Agent SSE CLI`);
console.log(`endpoint: ${endpoint}`);
console.log(`conversationId: ${conversationId}`);
console.log(`输入 /exit 退出，/new 新建会话。`);

while (true) {
  const query = (await rl.question("\n你> ")).trim();
  if (!query) {
    continue;
  }
  if (query === "/exit") {
    break;
  }
  if (query === "/new") {
    conversationId = randomUUID();
    console.log(`conversationId: ${conversationId}`);
    continue;
  }
  try {
    const response = await streamRequest({
      conversationId,
      userId,
      query,
      channel: process.env.MARKETING_AGENT_CHANNEL || null,
      product: process.env.MARKETING_AGENT_PRODUCT || null,
      audience: process.env.MARKETING_AGENT_AUDIENCE || null,
      goals: splitEnvList(process.env.MARKETING_AGENT_GOALS),
      variables: parseVariables(process.env.MARKETING_AGENT_VARIABLES)
    });
    if (response?.conversationId) {
      conversationId = response.conversationId;
    }
    if (response?.answer) {
      console.log(`\n助手> ${response.answer}`);
    }
    const pendingActions = extractPendingActions(response);
    for (const action of pendingActions) {
      await handlePendingAction(action);
    }
  } catch (error) {
    console.error(`请求失败: ${error.message}`);
  }
}

rl.close();

async function handlePendingAction(action) {
  console.log(`\n待确认> ${action.title}`);
  if (action.summary) {
    console.log(action.summary);
  }
  const decision = (await rl.question("输入 approve / reject / skip: ")).trim().toLowerCase();
  if (!["approve", "reject"].includes(decision)) {
    return;
  }
  const response = await streamRequest({
    conversationId,
    userId,
    query: `${decision} ${action.title}`,
    variables: {
      human_feedback: {
        pending_action_id: action.id,
        visible_object_id: action.id,
        decision,
        edited_payload: {}
      }
    }
  });
  if (response?.conversationId) {
    conversationId = response.conversationId;
  }
  if (response?.answer) {
    console.log(`\n助手> ${response.answer}`);
  }
}

async function streamRequest(payload) {
  const response = await fetch(endpoint, {
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
  let finalResponse = null;
  while (true) {
    const {done, value} = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, {stream: true});
    const blocks = buffer.split(/\r?\n\r?\n/);
    buffer = blocks.pop() || "";
    for (const block of blocks) {
      const event = parseSseBlock(block);
      if (!event) {
        continue;
      }
      printEvent(event);
      if (event.data?.response) {
        finalResponse = event.data.response;
      }
    }
  }
  if (buffer.trim()) {
    const event = parseSseBlock(buffer);
    if (event) {
      printEvent(event);
      if (event.data?.response) {
        finalResponse = event.data.response;
      }
    }
  }
  return finalResponse;
}

function parseSseBlock(block) {
  const dataLines = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trimStart());
    }
  }
  if (dataLines.length === 0) {
    return null;
  }
  try {
    return JSON.parse(dataLines.join("\n"));
  } catch {
    return {type: "raw", message: dataLines.join("\n"), data: {}};
  }
}

function printEvent(event) {
  const type = event.type || "message";
  const node = event.node ? `/${event.node}` : "";
  const message = event.message ? ` ${event.message}` : "";
  console.log(`[${type}${node}]${message}`);
}

function extractPendingActions(response) {
  const observations = response?.metadata?.observations;
  if (!Array.isArray(observations)) {
    return [];
  }
  return observations
    .flatMap((observation) => observation.visibleObjects || [])
    .filter((object) => object.type === "hitl_confirmation" && object.status === "pending")
    .map((object) => ({
      id: object.id,
      title: object.title || "待确认动作",
      summary: object.summary || ""
    }));
}

function parseVariables(raw) {
  if (!raw) {
    return {};
  }
  try {
    const value = JSON.parse(raw);
    return value && typeof value === "object" && !Array.isArray(value) ? value : {};
  } catch {
    return {};
  }
}

function splitEnvList(raw) {
  if (!raw) {
    return [];
  }
  return raw.split(/[,，、]/).map((item) => item.trim()).filter(Boolean);
}

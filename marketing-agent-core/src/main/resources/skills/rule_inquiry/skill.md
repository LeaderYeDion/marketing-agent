# rule_inquiry

## Purpose
Use this skill when the user asks about marketing activity rules, promotion eligibility, enrollment status, failure reasons, visible cards, or rule interpretation.

## Inputs
- `question`: User question or the question distilled by the main agent.
- `compressed_context`: Relevant context distilled by the main agent.
- `visible_objects`: User-visible cards or result summaries that may be referenced by the question.

## Allowed Tools
- `search_related_knowledge`
- `read_visible_object`
- `web_search` only when configured by the runtime.

## Core Flow
1. Understand whether the answer can be grounded in conversation context, visible objects, or knowledge retrieval.
2. Search the local marketing knowledge base when rule knowledge is needed.
3. Answer in user-facing language and identify the evidence source at a high level.
4. If evidence is insufficient, ask for the missing activity ID, promotion ID, card, or file context.

## Output Contract
- Return `succeeded` with a concise answer when enough evidence exists.
- Return a clarification request when key identifiers or context are missing.
- Include a handoff summary that helps the main agent continue later.

## Guardrails
- Do not invent activity rules or operational status.
- Do not perform write operations.
- Do not expose internal runtime fields such as pending action IDs unless they are user-visible.

## Few Shots
User asks: "What are the enrollment rules for this activity?"
Expected behavior: search related knowledge, combine it with visible context, and answer with the source of evidence.

User asks: "What does this confirmation card mean?"
Expected behavior: inspect visible objects and explain the card status, required action, and next step.

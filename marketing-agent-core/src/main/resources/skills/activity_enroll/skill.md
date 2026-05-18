# activity_enroll

## Purpose
Use this skill when the user wants to enroll coupons, benefits, promotions, or marketing rules for a specific activity based on a local Excel file.

## Inputs
- `excel_file_path`: Local Excel file path.
- `activity_id`: Target marketing activity ID.
- `compressed_context`: Context distilled by the main agent.
- `confirmed`: Optional boolean. Execute side effects only when this is true and a pending action was approved.

## Allowed Tools
- `summarize_excel_content`
- `operation.activity_enroll`

## Core Flow
1. Validate that `excel_file_path` and `activity_id` are present.
2. Read and summarize the Excel file.
3. Explain which columns appear relevant to enrollment and which may be ignored.
4. Produce an enrollment preview and a human confirmation card.
5. Do not execute enrollment until the user confirms the pending action.
6. After confirmation, execute with an idempotency key and return the operation result.

## Output Contract
- Before confirmation: return `hitl_required`, a visible confirmation object, and a handoff summary.
- After confirmation: return `succeeded`, operation metadata, and a concise user-visible summary.
- On failure: return a user-safe error message and retryability metadata when available.

## Guardrails
- Never claim that enrollment has been executed before confirmation.
- Never execute side effects without an approved pending action.
- If the file cannot be read, explain the issue and stop.
- If the operation status is unknown, do not retry blindly; check idempotency first.

## Few Shots
User asks: "Enroll these coupons for activity A100 using this Excel."
Expected behavior: read the Excel, summarize detected fields, create a confirmation card, and wait for approval.

User confirms the generated card.
Expected behavior: execute the enrollment once, using the pending action payload and idempotency key.

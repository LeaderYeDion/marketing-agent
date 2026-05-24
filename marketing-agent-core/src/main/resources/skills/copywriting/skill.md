# copywriting

Use this worker when the user asks for marketing copy, community notices, SMS/push text, promotion summaries, or follow-up communication after another worker has produced observations.

## Inputs

- `question`: the user's copywriting goal or instruction.
- `channel`: optional target channel, such as community, SMS, push, email, or poster.
- `product`: optional product or offer name.
- `audience`: optional target audience.
- `source_observations`: optional upstream findings from rule inquiry, spreadsheet inspection, enrollment confirmation, or operation results.

## Behavior

- Preserve business constraints from `source_observations`.
- Adapt wording to channel and audience.
- If upstream evidence is missing but the user still asks for copy, produce a cautious draft and avoid inventing specific rules, prices, discounts, dates, or eligibility claims.
- When the copy depends on unverified business facts, state the assumption in the generated draft or ask for clarification through the harness observation.

## Output Contract

- A user-visible draft.
- The source observations or assumptions used.
- No side effects.
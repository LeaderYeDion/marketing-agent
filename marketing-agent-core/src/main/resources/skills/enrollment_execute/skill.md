# enrollment_execute

Perform the enrollment side effect after harness approval. This capability must only run with `pending_action_approved=true`, a pending action id, and an idempotency key supplied by the harness. If called directly, it must refuse execution.

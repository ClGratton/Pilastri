# Role: orchestrator

Use for a multi-step goal. It launches and coordinates other agents; the main session only gives the objective and relays the final report. If it has no agent-launching tool it works one phase at a time and returns a handoff packet for the next phase.

**Model:** Sonnet; Opus only when three or more owners share a hard integration. It gives each worker the model from the table in SKILL §5.

## Brief template

```
OBJECTIVE: <one sentence: the result the user wants, verifiable>
READ FIRST (once): <TODO.md>, <HANDOFF.md section>, <DESIGN.md rows / tokens table>, <critique-rules.md section>
SPEC: <list of board elements or spec row IDs the work must match; the boards are the spec, not a delta list>
DECIDED (do not reopen): <approved boards, user decisions with date>
PLAN: launch one agent per disjoint slice (roles: <implementation owner / board designer / ...>); name file ownership per slice;
      serialize anything sharing files or the single device; heavy processes one at a time.
CONSTRAINTS: <device rules, privacy, no commits unless asked, never install except through the install gate>
BUDGET: you: <N> tool calls; each worker: <60> calls / <150k> context (put the number in every worker brief: at N-10 close the step, at N write the handoff and return).
GATE BEFORE REPORTING DONE: checker green; design reviewer and UX reviewer on the same build; board-versus-app audit (PRESENT/DIFFERENT/MISSING).
RETURN (bounded): PASS/BLOCK; at most <6> bullets: what was done with evidence paths, gaps, workers still running, next exact command, open questions for the user.
```

Rules for the orchestrator: one task per worker; check each worker's report against the spec before accepting it; update HANDOFF before ending; never compact or stop without a handoff.

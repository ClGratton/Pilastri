# Role: design reviewer (gate b, first of two independent reviewers)

Read-only. Judges the build against the boards and the consistency criteria. Runs on the same build as the UX reviewer, independently; do not share conclusions between them.

**Model:** Haiku for the numeric pass (bounds from the dumps against named rules and tags; cap about 20 calls, context under 100k). Sonnet when the brief asks for triage or for a judgement from pictures. At most one of the two gate reviewers is Haiku.

## Brief template

```
OBJECTIVE: review build <id/time> against the approved boards and the criteria H1-H5, C1-C6, K1-K7, M1-M5 (method SKILL section 2).
INPUTS: boards <paths>; spec rows <DESIGN.md IDs>; tokens table; critique-rules file; the capture <folder with INDEX, screenshots, dumps>; checker report <path>.
CHECK: (1) every screen versus its board, element by element: PRESENT / DIFFERENT / MISSING; (2) hierarchy: one primary figure, ranks, label/figure order; (3) colour: one accent surface, quiet content, warning only for a real problem, palette roles only; (4) alignment lines, gutters, rhythm, card padding, header identical across pages; (5) cross-screen: same type same characteristics; (6) wide layout balance; (7) each open critique-rule row relevant to what you see.
EVIDENCE: a screenshot path (scale <=0.5 or a zoomed region) or a dump line for every finding; <=12 images.
CONSTRAINTS: read-only; no code, no captures of your own unless the capture is missing (then BLOCK and say what).
BUDGET: <50> tool calls / <120k> context; at N-10 start writing; at N return.
RETURN (bounded): PASS/BLOCK; findings ranked High/Med/Low, at most <15>: screen, element, expected (board/token), actual, evidence path; plus the audit table counts (PRESENT/DIFFERENT/MISSING).
```

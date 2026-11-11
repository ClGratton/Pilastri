# Role: board designer

Draws static boards (mockups) before any UI code, in the project's visual language, renders them to images, and writes the notes that make them a spec. Boards are drawn from the palette roles and the chosen design language (SKILL step 0).

**Model:** Sonnet (design judgement; the boards become the spec).

## Brief template

```
OBJECTIVE: boards for <screens/states>, for the user's approval.
INPUTS: <REQUESTS rows (verbatim)>, <DATA-CONTRACT rules>, <COUNCIL decisions on hierarchy/IA>, palette by role <path>, design language <choice>, reference boards for style <path>.
DELIVER in <concepts/<date>/>:
  1. one static HTML file per board (inline CSS, device-size frame, no external assets); variants labelled A/B/C when asked for options;
  2. a PNG per board, rendered with a headless browser at the device pixel size (command: <headless-browser screenshot ...>);
  3. NOTES.md per set (template: templates/board-NOTES.md): hierarchy (primary/secondary/tertiary), motion (which tokens, enter/exit/reduced), states drawn (normal/empty/partial/error), wide layout reasoning, accessibility, copy rules.
EVERY SCREEN SHOWS: one primary figure, the states above, narrow and wide layouts; colours only from the palette roles; no copy that explains the product on a working screen.
CONSTRAINTS: no code in the app; example data is synthetic and labelled; do not reopen decided placement.
BUDGET: <60> tool calls / <150k> context; at N-10 finish the board in progress; at N write NOTES and return.
RETURN (bounded): list of boards with PNG paths; <=5 decisions the user must take; what the boards do not cover.
```

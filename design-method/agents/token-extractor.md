# Role: token extractor

Turns approved boards into a tokens table, and compares it to what the app actually uses (gap and causes). Read-only on code.

**Model:** Haiku when the boards have text NOTES or CSS to read; Sonnet when values must be read from pictures.

## Brief template

```
OBJECTIVE: the tokens table for <boards>, and the gap between it and the app.
INPUTS: board HTML/PNG <paths>; the app's token/theme/component files <paths>; the existing tokens table <path, if any>.
MEASURE (by role, from the boards): font size/weight/line-height/letter-spacing and colour for page title, subtitle, primary figure, secondary figure, label, caption, card title, chip, row; fill/outline/radius/height/padding of each container type; gutter and gap; number of accent-filled surfaces per screen; dark-mode roles if boards exist.
OUTPUT: (1) a plain table (Role | Size / weight, colour | Notes), no narrative, in the format of templates/DESIGN.md "Tokens"; (2) gap table: Role | board value | app value | where in source | likely cause (literal in screen, unshared component, copied scale factor, ...); (3) roles in the app with no row in the table.
CONSTRAINTS: edit only <the tokens section of DESIGN.md>; do not change code.
BUDGET: <40> tool calls / <120k> context; at N-10 finish the table; at N return.
RETURN (bounded): PASS/BLOCK; table path; count of gaps by cause; <=5 highest-impact gaps with file:line.
```

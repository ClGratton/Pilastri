# Role: capture agent

Produces the evidence the checker and reviewers need from a device, emulator or simulator: screenshots and tagged accessibility dumps of every screen and size, plus an INDEX. Does not judge.

**Model:** Haiku (one command, counts and an INDEX; the reviewers wait on it, so latency matters). Cap about 15 calls.

## Brief template

```
OBJECTIVE: a capture of <screens x states x size classes> from build <artifact id/time>.
TARGET: <emulator, simulator or device id, e.g. an adb serial>; it must be the emulator unless the task says otherwise. If the app is in the foreground on a user's device: do not touch it.
PRECONDITIONS: only one emulator/heavy process; commit/memory headroom checked <command>; platform animation settings, e.g. animator scales <1.0 and the user's value>; synthetic data loaded <how>; scale (px per layout unit) read <command>.
DO: for each screen/size: set size (and reset after), navigate, capture a screenshot at scale <=0.5 (a zoomed region for detail) and the accessibility dump to <output/capture-<date>/<layout>-<screen>.xml> (top-level pages: <layout>-top-<screen>.xml); for motion: a rest dump and a mid-gesture dump (<x>-rest.xml, <x>-moving.xml) or frames at fixed times.
INDEX: <output/capture-<date>/INDEX.md>: file, screen, size class, state, build, density, data set, what could not be captured and why.
BUDGET: <60> tool calls, <=12 screenshots read; at N-10 finish the screen in progress; at N write the INDEX and return.
RETURN (bounded): PASS/BLOCK; folder path; counts of dumps/screenshots; gaps; density value.
```

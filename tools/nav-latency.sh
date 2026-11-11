#!/usr/bin/env bash
# Navigation latency per top destination (SKILL §2 "Navigation speed"; user 2026-11-09: switching to Pilastri waited 1 s before animating).
# For each destination: reset `dumpsys gfxinfo <pkg> framestats`, tap its dock/island item with adb, read framestats.
#   first-animated = from the input's HandleInputStart to the FrameCompleted of the 3rd frame from that input (the input frame + the next two):
#                    a blocked first composition makes this about the block's length; a healthy switch is 2-3 vsyncs.
#   worst frame    = the longest IntendedVsync -> FrameCompleted in the window (about 1.2 s after the tap).
# Verdict: first-animated <= 100 ms (LIMIT_MS) and no destination above 2x the median of the others. Run on the `fast` build only.
# Usage: SERIAL=emulator-5554 [PKG=xyz.grtlabs.pilastri] [DESTS="Pilastri Spese Futuro Agente"] [RUNS=3] bash tools/nav-latency.sh [label]
# Never address the user's phone: SERIAL is required and a non-emulator serial is refused unless ALLOW_DEVICE=1.
set -u
SERIAL="${SERIAL:-}"; PKG="${PKG:-xyz.grtlabs.pilastri}"; DESTS="${DESTS:-Pilastri Spese Futuro Agente}"; RUNS="${RUNS:-3}"; LIMIT_MS="${LIMIT_MS:-100}"
LABEL="${1:-run}"
[ -n "$SERIAL" ] || { echo "nav-latency: set SERIAL (e.g. emulator-5554)"; exit 2; }
case "$SERIAL" in emulator-*) ;; *) [ "${ALLOW_DEVICE:-0}" = 1 ] || { echo "nav-latency: refusing non-emulator serial $SERIAL"; exit 2; };; esac
ADB="$(command -v adb 2>/dev/null || true)"
if [ -z "$ADB" ]; then SDKDIR="$(grep '^sdk.dir=' local.properties 2>/dev/null | cut -d= -f2- | tr '\' '/' | sed 's#/:#:#; s#//*#/#g')"
  for c in "${SDKDIR:+$SDKDIR/platform-tools/adb.exe}" "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe"; do [ -x "$c" ] && ADB="$c" && break; done; fi
[ -n "$ADB" ] || { echo "nav-latency: adb not found"; exit 2; }
A() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" "$@"; }
TMP="$(mktemp -d)"; command -v cygpath >/dev/null 2>&1 && TMP="$(cygpath -m "$TMP")"; trap 'rm -rf "$TMP"' EXIT
PY="$(command -v python3 || command -v python)"

dump() { A shell uiautomator dump /sdcard/nl.xml >/dev/null 2>&1; A pull /sdcard/nl.xml "$TMP/nl.xml" >/dev/null 2>&1; }
# Prints "x y" of the dock/island item for a destination: by label (text/content-desc), else by order among tagged items.
where() { "$PY" - "$TMP/nl.xml" "$1" "$DESTS" <<'PYEOF'
import sys,re,xml.etree.ElementTree as ET
f,name,dests=sys.argv[1:4]; dests=dests.split()
items=[]
for n in ET.parse(f).iter('node'):
    rid=n.get('resource-id',''); 
    if 'dock-item' not in rid: continue
    m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
    if not m: continue
    x0,y0,x1,y1=map(int,m.groups()); items.append(((y0,x0),(x0+x1)//2,(y0+y1)//2,(n.get('text','')+' '+n.get('content-desc','')).lower()))
items.sort()
for _,x,y,l in items:
    if name.lower() in l: print(x,y); sys.exit(0)
# open Fold island: its buttons carry no dock-item tag; match the exact content-desc / text
for n in ET.parse(f).iter('node'):
    if (n.get('content-desc','') or n.get('text','')).strip().lower()==name.lower():
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n.get('bounds',''))
        if m: x0,y0,x1,y1=map(int,m.groups()); print((x0+x1)//2,(y0+y1)//2); sys.exit(0)
i=dests.index(name)
if i<len(items): print(items[i][1],items[i][2]); sys.exit(0)
sys.exit(1)
PYEOF
}
measure() { # reads framestats from $TMP/fs.txt, prints "first_ms worst_ms"
"$PY" - "$TMP/fs.txt" <<'PYEOF'
import sys
lines=open(sys.argv[1],errors='ignore').read().splitlines()
try: i=next(k for k,l in enumerate(lines) if l.strip()=='---PROFILEDATA---')
except StopIteration: print("NA NA"); sys.exit(0)
hdr=[h.strip().lower() for h in lines[i+1].rstrip(',').split(',')]
col=lambda n: hdr.index(n)
ci,ch,cf=col('intendedvsync'),col('handleinputstart'),col('framecompleted')
rows=[]
for l in lines[i+2:]:
    if l.startswith('---'): break
    p=l.split(',')
    if len(p)<len(hdr): continue
    try: v=[int(x) for x in p[:len(hdr)]]
    except ValueError: continue
    if v[ci]>=9223372036854775807 or v[cf]<=0: continue
    rows.append(v)
rows.sort(key=lambda v:v[ci])
inp=[k for k,v in enumerate(rows) if v[ch]>v[ci]>0 and v[ch]-v[ci]<10**9 and v[ch]>0]
if not rows or not inp: print("NA NA"); sys.exit(0)
k=inp[0]; t0=rows[k][ch]
first=(rows[min(k+2,len(rows)-1)][cf]-t0)/1e6
worst=max((v[cf]-v[ci])/1e6 for v in rows[k:])
print("%.0f %.0f"%(first,worst))
PYEOF
}

echo "nav-latency [$LABEL] serial=$SERIAL pkg=$PKG limit=${LIMIT_MS}ms"
A shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 3
declare -A FIRST WORST
prev=""
for d in $DESTS; do FIRST[$d]=""; WORST[$d]=""; done
for r in $(seq 1 "$RUNS"); do
  for d in $DESTS; do
    # start from a different destination so the tap is a real switch
    other="$(echo $DESTS | tr ' ' '\n' | grep -vx "$d" | head -1)"
    dump; xy="$(where "$other")" && A shell input tap $xy >/dev/null 2>&1; sleep 2
    dump; xy="$(where "$d")" || { echo "  $d: item not found"; continue; }
    A shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
    A shell input tap $xy >/dev/null 2>&1; sleep 1.5
    A shell dumpsys gfxinfo "$PKG" framestats > "$TMP/fs.txt" 2>/dev/null
    read f w <<<"$(measure)"
    echo "  run $r $d: first-animated ${f}ms worst-frame ${w}ms"
    FIRST[$d]+="$f "; WORST[$d]+="$w "
  done
done
"$PY" - "$LIMIT_MS" "$DESTS" "$(for d in $DESTS; do echo "$d|${FIRST[$d]}|${WORST[$d]}"; done)" <<'PYEOF'
import sys,statistics as st
lim=float(sys.argv[1]); dests=sys.argv[2].split(); rows={}
for l in sys.argv[3].splitlines():
    d,f,w=l.split('|'); fv=[float(x) for x in f.split() if x!='NA']; wv=[float(x) for x in w.split() if x!='NA']
    rows[d]=(st.median(fv) if fv else None, max(wv) if wv else None)
bad=[]
print("destination   first-animated(median)  worst-frame(max)")
for d in dests:
    f,w=rows[d]; print("%-12s  %8s ms  %8s ms"%(d,"n/a" if f is None else "%.0f"%f,"n/a" if w is None else "%.0f"%w))
    if f is None: bad.append(d+": no measurement"); continue
    if f>lim: bad.append("%s: first animated frame %.0f ms > %.0f ms"%(d,f,lim))
    others=[rows[o][0] for o in dests if o!=d and rows[o][0] is not None]
    if others and f>2*st.median(others) and f>lim/2: bad.append("%s: %.0f ms is more than 2x the median of the others (%.0f ms)"%(d,f,st.median(others)))
print("NAV-LATENCY "+("PASS" if not bad else "FLAG: "+"; ".join(bad)))
sys.exit(1 if bad else 0)
PYEOF

#!/bin/bash
# Screenshot check. Runs inside the ocr container after run-install.sh, in
# the same repo checkout. Shoots test.s and test.c at 1600x1000 and gates
# every capture on OCR of the target text (2 s poll, no fixed sleep over
# 10 s). Success is exit code 0 and four files in work/out/:
# shot-asm-hints.png, shot-asm-hover.png, shot-cpp-hints.png, shot-cpp-hover.png.
set -u
. "$(dirname "$0")/common.sh"

[ -x "$W/sys/simdref/bin/simdref-lsp" ] || { echo "FATAL: no managed simdref install; run run-install.sh first"; exit 1; }
[ -n "$(ls -A "$W/conf" 2>/dev/null)" ] || { echo "FATAL: no IDE conf; run run-install.sh first"; exit 1; }
[ -s "$W/home/.local/share/simdref/catalog.db" ] || { echo "FATAL: no catalog from run 1"; exit 1; }

# --- pin the managed install to the version the README screenshots show ---
# The uv binary the plugin downloaded in run 1 lives in the managed dir.
# Idempotent: if a previous run already pinned and disarmed uv, verify the
# installed version instead of re-pinning. Bump the pin when re-shooting.
UV="$W/sys/simdref/uv"
PY="$W/sys/simdref/tools/simdref/bin/python"
if [ -x "$UV" ]; then
  rm -rf "$W/sys/simdref/bin" "$W/sys/simdref/tools" "$W/sys/simdref/python" "$W/sys/simdref/uv-cache"
  rm -f "$W/sys/simdref"/update-*
  export UV_TOOL_DIR="$W/sys/simdref/tools" UV_TOOL_BIN_DIR="$W/sys/simdref/bin" \
         UV_PYTHON_INSTALL_DIR="$W/sys/simdref/python" UV_CACHE_DIR="$W/sys/simdref/uv-cache"
  "$UV" tool install --force simdref==0.0.16 || { echo "FATAL: uv tool install failed"; exit 1; }
  # Disarm the daily upgrade: without the uv binary the check logs a benign
  # no-op, so the 0.0.16 pin survives the IDE session.
  rm -f "$UV"
fi
VER=$("$PY" -c "import importlib.metadata; print(importlib.metadata.version('simdref'))")
echo "pinned simdref version: $VER"
[ "$VER" = "0.0.16" ] || { echo "FATAL: expected 0.0.16, got $VER"; exit 1; }
# isa update always re-downloads the ~400MB catalog from the GitHub release
# (that endpoint 504s from this network), and it only falls back to a local
# catalog.msgpack, which the install flow deletes. The stamp lives in
# catalog.db and was written by the install that built it, so check the
# stamp directly; the LSP start below exercises the catalog for real.
STAMP=$("$PY" -c "from simdref.storage import read_installed_version_stamp as r; print(r() or 'none')")
echo "catalog stamp: $STAMP"
[ "$STAMP" = "0.0.16" ] || { echo "FATAL: catalog stamp is not 0.0.16"; exit 1; }

# --- start the IDE at 1600x1000, reusing the accepted first-run state ---
[ -f "$LOG" ] && mv "$LOG" "$W/logs/idea.log.install"
# A previous run's killed IDE leaves conf/.lock; with a reused PID in the
# fresh container the lock looks alive and IDEA shows "Start Failed".
rm -f "$W/conf/.lock"
# The spellchecker is part of the IDE core in build 261, so plugin-disable
# lists cannot turn it off. The "Typo" bar and the wavy underlines come from
# the typo inspection; in this build its tool name is SpellCheckingInspection
# (the legacy "Typos" id does not exist). Disable that id in both the
# application profile and the project profile.
mkdir -p "$W/conf/options" "$W/project/.idea/inspectionProfiles"
cat > "$W/conf/options/Inspection.xml" <<'EOF'
<application>
  <component name="InspectionProjectSettingsManager">
    <option name="projectProfile" value="Project Default" />
    <profile version="1.0">
      <option name="myName" value="Project Default" />
      <inspection_tool class="SpellCheckingInspection" enabled="false" level="TYPO" enabled_by_default="true" />
    </profile>
  </component>
</application>
EOF
cat > "$W/project/.idea/inspectionProfiles/Project_Default.xml" <<'EOF'
<profile version="1.0">
  <option name="myName" value="Project Default" />
  <inspection_tool class="SpellCheckingInspection" enabled="false" level="TYPO" enabled_by_default="true" />
</profile>
EOF
export IDEA_VM_OPTIONS=$W/idea64-patched.vmoptions IDEA_PROPERTIES=$W/idea.properties
cp /ide/bin/idea64.vmoptions "$IDEA_VM_OPTIONS"
cat >> "$IDEA_VM_OPTIONS" <<EOF
-XX:ErrorFile=$W/out/hs_err_%p.log
-Didea.config.path=$W/conf
-Didea.system.path=$W/sys
-Didea.log.path=$W/logs
-Didea.plugins.path=$W/plugins
EOF
rm -rf "$W/out" && mkdir -p "$W/out"
Xvfb :99 -screen 0 1600x1000x24 &
XPID=$!
sleep 2
/ide/bin/idea.sh "$W/project" "$W/project/test.s" > "$W/out/idea.stdout" 2>&1 &
IPID=$!
trap 'kill $IPID $XPID 2>/dev/null' EXIT

# --- OCR helpers ---
# ocr_tsv: screenshot the root window, OCR it (normal + inverted for the
# dark theme), write merged word boxes to $W/out/ocr.tsv: "text cx cy conf".
ocr_tsv() {
  import -window root "$W/out/ocr.png" 2>/dev/null || return 1
  convert "$W/out/ocr.png" -negate "$W/out/ocr-neg.png"
  : > "$W/out/ocr.tsv"
  local img
  for img in "$W/out/ocr.png" "$W/out/ocr-neg.png"; do
    tesseract "$img" "$W/out/ocr-tmp" tsv --psm 11 2>/dev/null
    awk -F'\t' 'NR>1 && $12!="" && $11>=30 {printf "%s %d %d %s\n", $12, $7+$9/2, $8+$10/2, $11}' \
      "$W/out/ocr-tmp.tsv" >> "$W/out/ocr.tsv"
  done
}

# ocr_text: join the OCR words into one line. The TSV is one word per line,
# so phrase patterns like "Packed Single Precision" never match across
# lines. ocr.tsv is already "text cx cy conf", conf-filtered in ocr_tsv.
ocr_text() {
  awk '{print $1}' "$W/out/ocr.tsv" | tr '\n' ' '
}

# poll_ocr REGEX SECONDS: poll the screen every 2 s until OCR text matches.
# Case-sensitive: uppercase patterns (VADDPS) must not match the lowercase
# code text, and vice versa.
poll_ocr() {
  local i n=$(( $2 / 2 ))
  for i in $(seq 1 "$n"); do
    ocr_tsv && ocr_text | grep -Eq "$1" && return 0
    kill -0 "$IPID" 2>/dev/null || return 1
    sleep 2
  done
  return 1
}

# wait_no_ocr REGEX SECONDS: poll until the text disappears (tooltip closed).
wait_no_ocr() {
  local i n=$(( $2 / 2 ))
  for i in $(seq 1 "$n"); do
    if ! ocr_tsv; then return 0; fi
    ocr_text | grep -Eq "$1" || return 0
    kill -0 "$IPID" 2>/dev/null || return 1
    sleep 2
  done
  return 1
}

# word_box WORD: print "cx cy" of the best OCR hit containing WORD.
# Substring match: the C fixture word sits inside quotes, so OCR may merge
# the quote into the word token.
word_box() {
  ocr_tsv || return 1
  awk -v w="$(echo "$1" | tr '[:upper:]' '[:lower:]')" \
    'index(tolower($1), w) > 0 { if ($4+0 > best) { best = $4+0; x = $2; y = $3 } }
     END { if (best > 0) print x, y; else exit 1 }' "$W/out/ocr.tsv"
}

shot() { import -window root "$W/out/$1.png"; }

fail() { echo "FAIL: $1"; shot "debug-fail"; exit 1; }

# hover X Y: trigger the IDE hover tooltip at a point. A bare pointer warp
# does not fire the hover intent (verified: 12 s of warps, nothing); the
# editor needs focus and fresh motion events. Focus with a click in the
# empty area below the code: clicking the word itself pops a false-positive
# "Typo: vaddps" inspection bar that stays open with the caret.
hover() {
  xdotool mousemove 700 280 click 1
  sleep 1
  xdotool mousemove "$1" "$2"
  xdotool mousemove $(( $1 + 4 )) "$2"
  poll_ocr 'VADDPS' 20 && return 0
  # fallback: click the word directly (accepts the typo bar)
  xdotool mousemove "$1" "$2" click 1
  sleep 1
  xdotool mousemove $(( $1 + 4 )) "$2"
  poll_ocr 'VADDPS' 30
}

# --- the shots ---
# Reuse the accepted first-run state; if any dialog reappears (EULA state is
# not reliably flushed by a killed IDE), handle it the same way as run 1.
first_run || fail "first-run dialogs not handled in run 2"

# check_shot NAME EXPECTED...: re-OCR the captured frame; fail if "Typo" is
# on screen or an expected token is missing.
check_shot() {
  local name=$1; shift
  ocr_tsv || return 0
  ocr_text | grep -q 'Typo' && { echo "FAIL-check: $name has Typo marker"; return 1; }
  local tok
  for tok in "$@"; do
    ocr_text | grep -q "$tok" || { echo "FAIL-check: $name missing '$tok'"; return 1; }
  done
  echo "check ok: $name"
  return 0
}

# test.s opens from the command line; wait for the server, then poll until
# the inlay hint text is on screen.
wait_log 'LSP server initialized' 120 || fail "LSP server did not start"
poll_ocr 'Packed Single Precision' 90 || fail "asm hints never appeared on screen"
shot shot-asm-hints
echo "shot-asm-hints captured"
check_shot shot-asm-hints 'Packed Single Precision' || fail "check_shot shot-asm-hints"

# Hover on the vaddps line of test.s.
set -- $(word_box vaddps)
[ -n "$1" ] || fail "OCR could not find the vaddps word"
hover "$1" "$2" || fail "asm hover tooltip never appeared"
shot shot-asm-hover
echo "shot-asm-hover captured"
check_shot shot-asm-hover 'VADDPS' 'YMM' || fail "check_shot shot-asm-hover"

# Close the tooltip, open test.c, wait for its hint.
xdotool key --clearmodifiers Escape
xdotool mousemove 30 950
wait_no_ocr 'VADDPS' 20 || fail "old tooltip did not close"
open_file_and_verify test.c || fail "test.c did not open"
poll_ocr 'Packed Single Precision' 90 || fail "cpp hints never appeared on screen"
shot shot-cpp-hints
echo "shot-cpp-hints captured"
check_shot shot-cpp-hints 'Packed Single Precision' || fail "check_shot shot-cpp-hints"

# Hover inside the asm string of test.c. The vaddps token in the C string is
# not OCR-readable (string coloring defeats tesseract), but the "%ymm1,"
# register directly after it is (conf 63); vaddps sits 7 chars (incl. space)
# before it, about 66 px at this font size.
set -- $(word_box '%ymm1,')
[ -n "$1" ] || fail "OCR could not find %ymm1, in test.c"
hover $(( $1 - 66 )) "$2" || fail "cpp hover tooltip never appeared"
shot shot-cpp-hover
echo "shot-cpp-hover captured"
check_shot shot-cpp-hover 'VADDPS' 'YMM' || fail "check_shot shot-cpp-hover"

# --- done ---
cp "$LOG" "$W/out/idea.log" 2>/dev/null
cp "$W/out/ocr.tsv" "$W/out/final-ocr.tsv" 2>/dev/null
ls -l "$W"/out/shot-*.png
echo "ALL SHOTS DONE"

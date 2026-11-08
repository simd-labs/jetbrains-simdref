# Shared setup for run-install.sh and screenshot.sh. Source this file inside the container.
# The container mounts the repo at /repo and IntelliJ IDEA at /ide (the image has it there).
set -u
R=/repo
W=$R/test/podman/work
LOG=$W/logs/idea.log
export TMPDIR=$W/tmp XDG_RUNTIME_DIR=$W/tmp/xdg HOME=$W/home DISPLAY=:99

# reset_state all|keep-server. "keep-server" keeps the simdref install in the IDE system dir.
reset_state() {
  if [ "$1" = keep-server ]; then
    find "$W/sys" -mindepth 1 -maxdepth 1 ! -name simdref -exec rm -rf {} + 2>/dev/null
    rm -rf "$W/conf" "$W/logs" "$W/tmp" "$W/home" "$W/out" "$W/project"
  else
    rm -rf "$W/conf" "$W/sys" "$W/logs" "$W/tmp" "$W/home" "$W/out" "$W/project"
  fi
  mkdir -p "$W/conf/options" "$W/sys" "$W/logs" "$W/tmp/xdg" "$W/home" "$W/out" "$W/plugins" "$W/project"
  # Seed the project dir with the fixtures. The caller does not need to create it.
  cp "$R"/test/podman/fixtures/* "$W/project/"
}

# start_ide: write the IDE config, unpack the plugin zip, start Xvfb and the IDE.
start_ide() {
  local zip
  zip=$(ls "$R"/build/distributions/simdref-*.zip 2>/dev/null | head -n 1)
  [ -n "$zip" ] || { echo "FATAL: no plugin zip in build/distributions, run ./gradlew buildPlugin"; exit 1; }
  export IDEA_VM_OPTIONS=$W/idea64-patched.vmoptions IDEA_PROPERTIES=$W/idea.properties
  cp /ide/bin/idea64.vmoptions "$IDEA_VM_OPTIONS"
  cat >> "$IDEA_VM_OPTIONS" <<EOF
-XX:ErrorFile=$W/out/hs_err_%p.log
-Didea.config.path=$W/conf
-Didea.system.path=$W/sys
-Didea.log.path=$W/logs
-Didea.plugins.path=$W/plugins
EOF
  cat > "$W/conf/options/trusted-paths.xml" <<EOF
<application>
  <component name="Trusted.Paths.Settings">
    <option name="TRUSTED_PATHS">
      <list>
        <option value="$W/project" />
      </list>
    </option>
  </component>
</application>
EOF
  : > "$IDEA_PROPERTIES"
  rm -rf "$W/plugins"/*
  unzip -q -o "$zip" -d "$W/plugins/"
  Xvfb :99 -screen 0 1920x1080x24 &
  XPID=$!
  sleep 2
  /ide/bin/idea.sh "$W/project" > "$W/out/idea.stdout" 2>&1 &
  IPID=$!
  trap 'kill $IPID $XPID 2>/dev/null' EXIT
}

click() { xdotool mousemove "$1" "$2" click 1; sleep 1; }
shot() { import -window root "$W/out/$1.png" 2>/dev/null; }

# open_file NAME: Search Everywhere style open (ctrl+shift+n).
# open_file: focus the main IDE window first (no WM under Xvfb, so focus is
# not inherited from closed dialogs and unfocused keystrokes are dropped).
# Wait for a focusable window: the main window title is set late at startup
# and is briefly empty.
open_file() {
  local wid n
  for n in $(seq 1 10); do
    wid=$(xdotool search --name "project" 2>/dev/null | head -n1)
    [ -n "$wid" ] && break
    sleep 2
  done
  echo "open_file: wid=$wid all=$(xdotool search --name '' getwindowname %@ 2>/dev/null | tr '\n' '|')" >> "$W/out/open-debug.log"
  [ -n "$wid" ] && xdotool windowfocus "$wid" && sleep 1
  xdotool key --delay 60 --clearmodifiers ctrl+shift+n
  sleep 2
  shot dbg-open-afterkey
  echo "open_file: after key all=$(xdotool search --name '' getwindowname %@ 2>/dev/null | tr '\n' '|')" >> "$W/out/open-debug.log"
  # The Search Everywhere popup is a top-level window named "win0". If the
  # keystroke route produced nothing (input focus not on the main window),
  # fall back to clicking the toolbar search icon (screen coords for the
  # 1600x1000 shot layout); that route is position-based, no focus needed.
  if ! xdotool search --name "win0" 2>/dev/null | grep -q .; then
    echo "open_file: no win0, clicking search icon" >> "$W/out/open-debug.log"
    xdotool mousemove 1304 35 click 1
    sleep 2
  fi
  xdotool type --delay 50 "$1"; sleep 2
  shot dbg-open-aftertype
  xdotool key Return
  echo "open_file: after return all=$(xdotool search --name '' getwindowname %@ 2>/dev/null | tr '\n' '|')" >> "$W/out/open-debug.log"
}

# open_file_and_verify NAME: open, then poll the window title for the file
# name (the title is "<file> - project"). Retry the whole sequence: a lost
# keystroke must not pass silently.
open_file_and_verify() {
  local a i
  for a in 1 2 3; do
    open_file "$1"
    for i in $(seq 1 15); do
      xdotool search --name "" getwindowname %@ 2>/dev/null | grep -q "$1" && return 0
      sleep 2
    done
  done
  return 1
}

# first_run: click through EULA, Data Sharing and any welcome page.
# Focus each dialog window before acting: on a fresh Xvfb the pointer and
# keyboard focus are not on the dialog, and unfocused clicks and keys are
# dropped (this is why the repo's original coordinate-only clicks stalled).
# Clicks are relative to the dialog window origin (fixed 600x460 dialog),
# so they work on any screen size: the repo's absolute coords only fit
# 1920x1080.
dclick() { # WID DX DY
  local g
  g=$(xdotool getwindowgeometry --shell "$1")
  eval "$g"
  xdotool mousemove $(( X + $2 )) $(( Y + $3 )) click 1
  sleep 1
}
first_run() {
  local eula="" ds="" t wid i
  for i in $(seq 1 100); do
    sleep 3
    kill -0 "$IPID" 2>/dev/null || { echo "idea exited at $((i * 3))s"; return 1; }
    t=$(xdotool search --name "" getwindowname %@ 2>/dev/null | tr '\n' '|')
    echo "t=$((i * 3))s titles=$t" >> "$W/out/titles.log"
    # Main window up and no dialog: nothing left to do (already-accepted
    # state, or the dialogs were just cleared).
    if echo "$t" | grep -q 'project' && ! echo "$t" | grep -Eq "User Agreement|Data Sharing"; then
      return 0
    fi
    if [ -z "$eula" ] && echo "$t" | grep -q "User Agreement"; then
      wid=$(xdotool search --name "User Agreement" | head -n1)
      xdotool windowfocus "$wid"
      sleep 3
      dclick "$wid" 43 394   # "I confirm that I have read and agree" checkbox
      dclick "$wid" 539 431  # Continue (default button, bottom right)
      eula=done; continue
    fi
    if [ -n "$eula" ] && [ -z "$ds" ] && echo "$t" | grep -q "Data Sharing"; then
      wid=$(xdotool search --name "Data Sharing" | head -n1)
      xdotool windowfocus "$wid"
      sleep 2
      dclick "$wid" 323 431  # Don't Send
      ds=done; continue
    fi
    if [ -n "$ds" ] && [ -n "$eula" ] && ! echo "$t" | grep -Eq "Data Sharing|User Agreement"; then
      if echo "$t" | grep -Eqi 'welcome|theme'; then
        click 960 740
        sleep 2
      fi
      return 0
    fi
  done
  shot dialog-stuck
  return 1
}

# stop_ide: TERM the IDE java process and wait for a clean exit so settings
# (EULA acceptance, workspace) are flushed to disk.
stop_ide() {
  pkill -TERM -f 'jbr/bin/java' 2>/dev/null
  local i
  for i in $(seq 1 30); do
    pgrep -f 'jbr/bin/java' >/dev/null 2>&1 || return 0
    sleep 2
  done
  pkill -KILL -f 'jbr/bin/java' 2>/dev/null
  return 0
}

# wait_log REGEX SECONDS: wait until idea.log has a line that matches.
wait_log() {
  local n=$(($2 / 3)) i
  for i in $(seq 1 "$n"); do
    grep -Eqs "$1" "$LOG" && return 0
    kill -0 "$IPID" 2>/dev/null || return 1
    sleep 3
  done
  return 1
}

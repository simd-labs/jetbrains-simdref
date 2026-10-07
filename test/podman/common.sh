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
open_file() {
  xdotool key --delay 60 ctrl+shift+n; sleep 2
  xdotool type --delay 50 "$1"; sleep 2
  xdotool key Return
}

# first_run: click through EULA, Data Sharing and the theme page. Coordinates are for 1920x1080.
first_run() {
  local eula="" ds="" skip="" t i
  for i in $(seq 1 100); do
    sleep 3
    kill -0 "$IPID" 2>/dev/null || { echo "idea exited at $((i * 3))s"; return 1; }
    t=$(xdotool search --name "" getwindowname %@ 2>/dev/null | tr '\n' '|')
    echo "t=$((i * 3))s titles=$t" >> "$W/out/titles.log"
    if [ -z "$eula" ] && echo "$t" | grep -qi "User Agreement"; then
      click 703 704; click 1200 743; eula=done; continue
    fi
    if [ -n "$eula" ] && [ -z "$ds" ] && echo "$t" | grep -q "Data Sharing"; then
      click 984 741; ds=done; continue
    fi
    if [ -n "$ds" ] && [ -z "$skip" ]; then
      click 960 740; sleep 2; skip=done; return 0
    fi
  done
  return 1
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

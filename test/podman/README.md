# Podman test rig

The rig starts a real IntelliJ IDEA 2026.1 in a rootless podman container. It loads the plugin zip and checks that the plugin installs and starts `simdref-lsp`.

CLion itself crashes in rootless podman (SIGSEGV right after the Data Sharing dialog). IntelliJ IDEA has the same LSP API and survives, so the rig uses IntelliJ IDEA.

## Files

- `Containerfile.dev` builds `localhost/jb-simdref-dev`. It has JDK 21, Gradle 9.0.0, Xvfb, xdotool and ImageMagick.
- `Containerfile.idea-nolsp` builds `localhost/jb-simdref-idea-nolsp` on top of the dev image. It adds IntelliJ IDEA 2026.1 in `/ide` and has no `simdref` on `PATH`.
- `run-install.sh` is the install check. `screenshot.sh` is the screenshot check. `common.sh` holds the shared functions.
- `fixtures/` holds `test.s` and `test.c`. The run scripts copy them into the project directory themselves.
- `work/` receives all run output (IDE config, logs, screenshots). Git ignores it.

## Build

Run these commands from the repository root. The IDEA download is about 4.4 GB.

```
podman build -t localhost/jb-simdref-dev -f test/podman/Containerfile.dev test/podman
podman build -t localhost/jb-simdref-idea-nolsp -f test/podman/Containerfile.idea-nolsp test/podman
```

Build the plugin zip once. The Gradle cache directory can live anywhere. Use an empty directory the first time.

```
mkdir -p "$HOME/.cache/jb-simdref-gradle"
podman run --rm --userns=keep-id -v "$PWD":/repo -v "$HOME/.cache/jb-simdref-gradle":/gradle -w /repo -e GRADLE_USER_HOME=/gradle localhost/jb-simdref-dev ./gradlew buildPlugin
```

## Install check

The check needs network access. The plugin downloads `uv` and `simdref`, and `isa update` downloads the catalog. Expect 5 to 10 minutes.

```
podman run --rm --userns=keep-id -v "$PWD":/repo localhost/jb-simdref-idea-nolsp bash /repo/test/podman/run-install.sh
```

Success means exit code 0 and both of these lines in `test/podman/work/logs/idea.log`:

```
SimdrefLspServerDescriptor@project: starting LSP server: .../simdref/bin/simdref-lsp []
SimdrefLspServerDescriptor@project(Running;0): LSP server initialized in 0.100s
```

The script prints both lines and `PASS`. On failure it saves `work/out/shot-fail.png`.

## Screenshot check

Run the install check first. The screenshot check reuses the `simdref` install from that run.

```
podman run --rm --userns=keep-id -v "$PWD":/repo localhost/jb-simdref-idea-nolsp bash /repo/test/podman/screenshot.sh
```

Success means exit code 0 and two files, `work/out/shot-s.png` (assembly) and `work/out/shot-cpp.png` (C with an `asm` string). Look at both images. Inlay hints need simdref 0.0.8 or newer.

## Notes

- Never pass the host `DISPLAY` or `WAYLAND_DISPLAY` to the container. The scripts start their own Xvfb on `:99`.
- The click coordinates in `common.sh` match a 1920x1080 screen and the dialog layout of IDEA 2026.1. A different IDEA version may need new coordinates.

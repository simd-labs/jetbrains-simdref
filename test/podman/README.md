# Podman test environment

This environment runs IntelliJ IDEA 2026.1 headless in rootless podman. It checks that the plugin installs and starts `simdref-lsp`. CLion SIGSEGVs in rootless podman (after the Data Sharing dialog). IDEA has the same LSP API and survives.

## Files

- `Containerfile.dev` makes `localhost/jb-simdref-dev` with JDK 21, Gradle 9.0.0, Xvfb, xdotool and ImageMagick.
`Containerfile.idea-nolsp` makes `localhost/jb-simdref-idea-nolsp`: it adds IntelliJ IDEA 2026.1 in `/ide`, no `simdref` on `PATH`.
- `run-install.sh` is the install check. `screenshot.sh` is the screenshot check. `common.sh` holds the shared functions.
- Run output goes to `work/` (git-ignored).

## Build

Run from the repository root. The IDEA download is about 4.4 GB.

```
podman build -t localhost/jb-simdref-dev -f test/podman/Containerfile.dev test/podman
podman build -t localhost/jb-simdref-idea-nolsp -f test/podman/Containerfile.idea-nolsp test/podman
```

Make the plugin zip one time (use an empty Gradle cache directory the first time).

```
mkdir -p "$HOME/.cache/jb-simdref-gradle"
podman run --rm --userns=keep-id -v "$PWD":/repo -v "$HOME/.cache/jb-simdref-gradle":/gradle -w /repo -e GRADLE_USER_HOME=/gradle localhost/jb-simdref-dev ./gradlew buildPlugin
```

## Install check

The check downloads `uv`, `simdref` and the ISA catalog. Plan for 5 to 10 minutes.

```
podman run --rm --userns=keep-id -v "$PWD":/repo localhost/jb-simdref-idea-nolsp bash /repo/test/podman/run-install.sh
```

Success is exit code 0 and two lines in `test/podman/work/logs/idea.log`:

```
SimdrefLspServerDescriptor@project: starting LSP server: .../simdref/bin/simdref-lsp []
SimdrefLspServerDescriptor@project(Running;0): LSP server initialized in 0.100s
```

The script prints the two lines and `PASS`. On an error, it keeps `work/out/shot-fail.png`.

## Screenshot check

Run the install check first.

```
podman run --rm --userns=keep-id -v "$PWD":/repo localhost/jb-simdref-idea-nolsp bash /repo/test/podman/screenshot.sh
```

Success is exit code 0 and two files: `work/out/shot-s.png` (assembly) and `work/out/shot-cpp.png` (C with an `asm` string). Inlay hints use the newest simdref.

## Update the screenshots

The `screenshots` branch is a single-commit orphan branch that holds the primary README images. Force-push to update it. Do not commit PNGs to `main`.

## Notes

- Do not give the host `DISPLAY` or `WAYLAND_DISPLAY` to the container. The scripts start their own Xvfb on `:99`.
- The click positions in `common.sh` are for a 1920x1080 screen and the IDEA 2026.1 dialog layout. A different IDEA version can want new positions.

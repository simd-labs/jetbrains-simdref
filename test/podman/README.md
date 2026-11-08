# Podman test environment

This environment runs IntelliJ IDEA 2026.1 headless in rootless podman. It checks that the plugin installs and starts `simdref-lsp`. CLion SIGSEGVs in rootless podman (after the Data Sharing dialog). IDEA has the same LSP API and survives.

## Files

- `Containerfile.dev` makes `localhost/jb-simdref-dev` with JDK 21, Gradle 9.0.0, Xvfb, xdotool and ImageMagick.
`Containerfile.idea-nolsp` makes `localhost/jb-simdref-idea-nolsp`: it adds IntelliJ IDEA 2026.1 in `/ide`, no `simdref` on `PATH`.
`Containerfile.ocr` makes `localhost/jb-simdref-ocr`: it adds `tesseract-ocr`, so the screenshot run polls for on-screen text instead of fixed sleeps.
- `run-install.sh` is the install check. `screenshot.sh` is the screenshot check. `common.sh` holds the shared functions.
- Run output goes to `work/` (git-ignored).

## Build

Run from the repository root. The IDEA download is about 4.4 GB.

```
podman build -t localhost/jb-simdref-dev -f test/podman/Containerfile.dev test/podman
podman build -t localhost/jb-simdref-idea-nolsp -f test/podman/Containerfile.idea-nolsp test/podman
podman build -t localhost/jb-simdref-ocr -f test/podman/Containerfile.ocr test/podman
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
podman run --rm --userns=keep-id -v "$PWD":/repo localhost/jb-simdref-ocr bash /repo/test/podman/screenshot.sh
```

The script pins the managed install to the simdref version the main README screenshots show and checks the catalog stamp against it.
It shoots `test.s` and `test.c` at 1600x1000.
Each capture waits for its OCR target text on screen and re-OCRs the frame after capture.
Success is exit code 0 and four files in `work/out/`: `shot-asm-hints.png`, `shot-asm-hover.png`, `shot-cpp-hints.png`, `shot-cpp-hover.png`.

## Update the screenshots

Retake the frames with the screenshot check.
Commit the PNGs on top of `refs/assets/screenshots` with the file names the main README uses.
Push that ref fast-forward.
Point the main README image links at the new commit sha.
Do not commit PNGs to `main`.

## Notes

- Do not give the host `DISPLAY` or `WAYLAND_DISPLAY` to the container. The scripts start their own Xvfb on `:99`.
- The dialog clicks in `common.sh` are relative to the dialog window, so a new screen size needs no new positions. The search-icon fallback in `open_file` uses the positions of the 1600x1000 screenshot layout. A different IDEA version can want new positions.

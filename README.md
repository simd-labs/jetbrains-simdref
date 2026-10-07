# simdref for JetBrains IDEs

![simdref inlay hints in a .s file in IntelliJ IDEA](docs/idea-asm.png)
![simdref inlay hints in a .cpp file in IntelliJ IDEA](docs/idea-cpp.png)

This plugin shows SIMD instruction documentation in CLion and other JetBrains IDEs. It starts the `simdref-lsp` language server. The server gives hover text and inlay hints for instructions.

It works in assembly files (`.s`, `.S`, `.asm`) and in C and C++ files (`.c`, `.cc`, `.cpp`, `.cxx`, `.c++`, `.h`, `.hpp`, `.hh`, `.hxx`, `.cu`, `.cuh`). In C and C++, the server gives hints only inside `asm` string literals.

## Supported IDEs

The plugin uses the native JetBrains LSP API (`com.intellij.modules.lsp`). It needs version 2026.1 or later, up to and including 2026.2.

- CLion, including the non-commercial license.
- IntelliJ IDEA (unified build, free tier included).
- Other commercial JetBrains IDEs that ship the LSP API.

IntelliJ IDEA Community builds and Android Studio do not have the LSP API.

Inlay hints need simdref 0.0.8 or newer (on PyPI). The plugin installs it for you.
The IDE cuts LSP inlay hints at 42 characters by default. The plugin raises this limit to 100 through `LspInlayHintSupport.getMaxInlayHintChars` (the platform clamps it to 100).

## Automatic install

The plugin looks for the server in this order:

1. `simdref-lsp` on `PATH`.
2. A previous install in `<IDE system dir>/simdref`.
3. A new install. The plugin downloads `uv` into `<IDE system dir>/simdref` and checks its
   SHA-256 against the release checksum before it unpacks the archive with the IDE's own
   extractor, so no external `tar` tool is needed on any OS. It runs
   `uv tool install simdref` and `isa update` there. The uv tool dir, bin dir, Python install
   dir and cache dir all stay under `<IDE system dir>/simdref`. This runs as a background
   task with a progress bar.

The checksum comes from the `.sha256` file of the same GitHub release. This catches a corrupt
download. It does not catch a tampered release, because checksum and archive come from the
same place.

If a step fails, the plugin shows a notification with the error and an action that opens the
instructions. The next matching file open runs the install again.

## Manual install

```
uv tool install simdref
isa update
```

`pip install simdref` also works. Then run `isa update`; then restart the IDE. The server opens the catalog only at start. Instructions: https://github.com/simd-labs/simdref

## Troubleshooting

If `simdref-lsp` is on `PATH`, the plugin uses it. Inlay hints need simdref 0.0.8 or newer (`uv tool upgrade simdref`).

## Development install

```
./gradlew buildPlugin
```

In the IDE, open Settings > Plugins > gear icon > Install Plugin from Disk. Select `build/distributions/simdref-0.1.0.zip`.

Other tasks: `./gradlew test`, `./gradlew verifyPlugin`, `./gradlew runIde`.

## Headless run in containers

CLion does not survive a plain rootless container here (SIGSEGV right after the Data Sharing
dialog, backend then dies). The same holds for the IU bundle and the free IDEA build. Use the
host IDE or a VM for end-to-end validation; the plugin is verified against CLion and IDEA
with `./gradlew verifyPlugin`.

## License

GPL-3.0-or-later.

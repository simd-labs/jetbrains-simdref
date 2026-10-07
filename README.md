# simdref for JetBrains IDEs

![simdref inlay hints in a .s file in IntelliJ IDEA](https://raw.githubusercontent.com/simd-labs/jetbrains-simdref/screenshots/idea-asm.png)
![simdref inlay hints in a .cpp file in IntelliJ IDEA](https://raw.githubusercontent.com/simd-labs/jetbrains-simdref/screenshots/idea-cpp.png)

This plugin shows SIMD instruction documentation in CLion and other JetBrains IDEs. It starts the `simdref-lsp` language server. The server gives hover text and inlay hints for instructions.

The plugin is active in assembly files (`.s`, `.S`, `.asm`).
It is also active in C and C++ files (`.c`, `.cc`, `.cpp`, `.cxx`, `.c++`, `.h`, `.hpp`, `.hh`, `.hxx`, `.cu`, `.cuh`).
In C and C++, the server gives hints only in `asm` string literals.

The Quick Documentation command shows the full simdref page from the local catalog, no network. The shortcut is Ctrl+Q on Linux and Windows, F1 on macOS.

## Supported IDEs

The plugin uses the native JetBrains LSP API (`com.intellij.modules.lsp`). The IDE version must be 2026.1 through 2026.2.

- CLion, including the non-commercial license.
- IntelliJ IDEA (unified release, free tier included).
- Other commercial JetBrains IDEs that ship the LSP API.

IntelliJ IDEA Community and Android Studio do not have the LSP API.

Inlay hints: simdref 0.0.8 or newer (on PyPI). The plugin installs it.
The IDE cuts LSP inlay hints at 42 characters by default. The plugin increases this limit to 100 through `LspInlayHintSupport.getMaxInlayHintChars` (the platform caps it at 100).

## Automatic install

The plugin looks for the server in this sequence:

1. `simdref-lsp` on `PATH`.
2. A previous install in `<IDE system dir>/simdref`.
3. A new install. The plugin downloads `uv` into `<IDE system dir>/simdref` and
   checks its SHA-256 against the release checksum. The IDE's own extractor
   writes the archive contents to disk, so the plugin uses no external `tar`
   tool on each OS. It runs `uv tool install simdref` and `isa update` there.
   The uv tool dir, bin dir, Python install dir and cache dir all stay below
   `<IDE system dir>/simdref`. This runs as a background task.

The checksum comes from the `.sha256` file of the same GitHub release. The check catches a corrupt download. It does not catch a tampered release: checksum and archive come from the same position.

When a step has an error, the plugin shows a notification with the error and a link that opens the instructions. The next supported file open runs the install again.

## Manual install

```
uv tool install simdref
isa update
```

`pip install simdref` is also OK. Then run `isa update`. Then start the IDE again. The server opens the catalog only at start. Instructions: https://github.com/simd-labs/simdref

## Troubleshooting

If `simdref-lsp` is on `PATH`, the plugin uses it. Inlay hints: simdref 0.0.8 or newer (`uv tool upgrade simdref`).

## Development install

```
./gradlew buildPlugin
```

In the IDE, open Settings > Plugins > gear icon > Install Plugin from Disk. Select `build/distributions/simdref-0.1.0.zip`.

Other tasks: `./gradlew test`, `./gradlew verifyPlugin`, `./gradlew runIde`.

## Headless containers

CLion dies in a plain rootless container here (SIGSEGV right after the Data Sharing dialog, backend then dies). The IU bundle and the free IDEA release die the same. Use the host IDE or a VM for end-to-end checks. The `./gradlew verifyPlugin` task checks the plugin against CLion and IDEA.

## License

GPL-3.0-or-later.

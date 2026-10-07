#!/bin/bash
# Install check. Runs inside the container. Fresh IDE state, no simdref-lsp on PATH, network on.
# The plugin has to download uv, install simdref, run isa update and start the LSP server.
# Exit 0 when idea.log shows both the start line and the initialized line.
. "$(dirname "$0")/common.sh"
command -v simdref-lsp && { echo "FATAL: simdref-lsp on PATH"; exit 1; }
reset_state all
start_ide
first_run || { echo "FATAL: first-run dialogs not handled"; exit 1; }
open_file test.s
# The install takes minutes: uv download, simdref install, isa update.
wait_log 'starting LSP server: .*/simdref/bin/simdref-lsp' 900 || { shot shot-fail; echo "FAIL: no start line"; exit 1; }
wait_log 'LSP server initialized' 60 || { shot shot-fail; echo "FAIL: no initialized line"; exit 1; }
sleep 8
shot shot-install
grep -E 'starting LSP server: .*/simdref/bin/simdref-lsp|LSP server initialized' "$LOG"
echo "PASS"

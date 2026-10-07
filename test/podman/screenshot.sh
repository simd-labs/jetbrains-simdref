#!/bin/bash
# Screenshot check. Runs inside the container, after run-install.sh in the same repo checkout.
# Keeps the simdref install from that run, resets the IDE state and shoots test.s and test.c.
. "$(dirname "$0")/common.sh"
[ -x "$W/sys/simdref/bin/simdref-lsp" ] || { echo "FATAL: run run-install.sh first"; exit 1; }
reset_state keep-server
start_ide
first_run || { echo "FATAL: first-run dialogs not handled"; exit 1; }
open_file test.s
wait_log 'LSP server initialized' 120 || { echo "FAIL: LSP server did not start"; exit 1; }
sleep 15
shot shot-s
open_file test.c
sleep 15
shot shot-cpp
ls -l "$W"/out/*.png

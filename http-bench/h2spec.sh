#!/usr/bin/env bash
# h2spec (SPEC §6): runs h2spec against echoServer serving HTTP/2 over cleartext (NETON_HTTP_H2=1), which reads each
# request before answering as h2's CI example server does (SPEC §11: a server that answers before reading makes four
# cases depend on timing). Usage: h2spec.sh <echoServer binary> <h2spec binary> [port] [junit report].
# NETON_IO_DRIVER picks the driver. Only the server started here is stopped, by its recorded PID.
set -u
BIN=$1
H2SPEC=$2
PORT=${3:-3102}
REPORT=${4:-h2spec-report.xml}
NETON_HTTP_H2=1 "$BIN" 127.0.0.1 "$PORT" > h2spec-server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; wait $SERVER 2>/dev/null' EXIT
for _ in $(seq 50); do curl -s --http2-prior-knowledge -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 0.1; done
"$H2SPEC" -h 127.0.0.1 -p "$PORT" -o 5 -j "$REPORT"

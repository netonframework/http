#!/bin/bash
# HTTP/3 interop between neton.http.h3 (h3interop.kexe) and aioquic 1.2.0 (an independent Python QUIC + HTTP/3 +
# QPACK implementation: its examples/http3_server.py with the ASGI app aioquic/neton_routes.py, and
# examples/http3_client.py), both directions, real TLS 1.3 on loopback UDP.
# Usage: run-interop-aioquic.sh <h3interop.kexe> <port base>
# Expects in the current directory: certs/ (gen-certs.sh), venv/ with aioquic and wsproto (pip), the aioquic sdist
# unpacked in sdist/aioquic-1.2.0 (for its examples), aioquic/neton_routes.py, www/large.bin (run-interop.sh).
# Only processes started here are stopped, by their recorded PIDs.
set -u
K=$1
P=${2:-24700}
ROOT=$(pwd)
C=$ROOT/certs
PY=$(pwd)/venv/bin/python
EX=$(pwd)/sdist/aioquic-1.2.0/examples
LARGE=16777216
mkdir -p logs out
result() { echo "== $*"; }
stop() { kill "$1" 2>/dev/null; wait "$1" 2>/dev/null; }

# A. neton client -> aioquic http3_server.py (routes of neton_routes.py; no trailers or GOAWAY there: "basic")
(cd $EX && PYTHONPATH=$ROOT/aioquic exec $PY http3_server.py --host 127.0.0.1 --port $P \
  -c $C/server.pem -k $C/server.key neton_routes:app) > logs/A-aioquic-server.log 2>&1 &
SPID=$!
sleep 3
$K client 127.0.0.1:$P localhost $C/ca.pem basic $LARGE > logs/A-neton-client.log 2>&1
R=$?
sleep 1
stop $SPID
result "A. neton client -> aioquic server: neton exit $R"
grep -h "h3-interop\]" logs/A-neton-client.log
grep -h "Traceback\|Error\|error" logs/A-aioquic-server.log | head -5

# B. aioquic http3_client.py -> neton server, with GREASE (the default, as h3) and without (NETON_H3_GREASE=0).
# The aioquic client has no timeout of its own: each run is bounded by `timeout 60`.
AQ="timeout 60 $PY http3_client.py --ca-certs $C/ca.pem"
run_b() { # <label> <port> <NETON_H3_GREASE value>
  L=$1; Q=$2
  NETON_H3_GREASE=$3 $K server 127.0.0.1:$Q $C/server.pem $C/server.key > logs/B$L-neton-server.log 2>&1 &
  NPID=$!
  sleep 1
  rm -rf out/b$L && mkdir -p out/b$L/1 out/b$L/2 out/b$L/3
  # B1: three requests on one connection (the example sends its URLs concurrently on one connection)
  (cd $EX && $AQ --output-dir $ROOT/out/b$L/1 https://127.0.0.1:$Q/ https://127.0.0.1:$Q/size/$LARGE https://127.0.0.1:$Q/missing) > logs/B$L-1-aioquic-client.log 2>&1
  R=$?
  cmp -s out/b$L/1/$LARGE www/large.bin && SAME=identical || SAME=DIFFERENT
  result "B$L-1. aioquic client GET /, /size/$LARGE, /missing on one connection -> neton server (NETON_H3_GREASE=$3): exit $R; / = \"$(cat out/b$L/1/index.html 2>/dev/null)\"; /size: $(stat -c %s out/b$L/1/$LARGE 2>/dev/null) bytes, $SAME to the pattern; /missing: $(stat -c %s out/b$L/1/missing 2>/dev/null) bytes"
  grep -h "Response received\|Error\|error" logs/B$L-1-aioquic-client.log | sed 's/^.*INFO //' | head -5
  # B2: POST with a body
  DATA="hello over HTTP/3 from aioquic"
  (cd $EX && $AQ --output-dir $ROOT/out/b$L/2 -d "$DATA" https://127.0.0.1:$Q/echo) > logs/B$L-2-aioquic-client.log 2>&1
  R=$?
  [ "$(cat out/b$L/2/echo 2>/dev/null)" = "$DATA" ] && SAME=identical || SAME=DIFFERENT
  result "B$L-2. aioquic client POST /echo -> neton server (NETON_H3_GREASE=$3): exit $R; echo $SAME"
  grep -h "Response received\|Error\|error" logs/B$L-2-aioquic-client.log | sed 's/^.*INFO //' | head -3
  # B3: a larger POST body (100 KiB, passed as the -d argument, which Linux limits to 128 KiB)
  BIG=$(head -c 102400 /dev/zero | tr '\0' 'x')
  (cd $EX && $AQ --output-dir $ROOT/out/b$L/3 -d "$BIG" https://127.0.0.1:$Q/echo) > logs/B$L-3-aioquic-client.log 2>&1
  R=$?
  [ "$(cat out/b$L/3/echo 2>/dev/null)" = "$BIG" ] && SAME=identical || SAME=DIFFERENT
  result "B$L-3. aioquic client POST /echo 100 KiB -> neton server (NETON_H3_GREASE=$3): exit $R; $(stat -c %s out/b$L/3/echo 2>/dev/null) bytes echoed, $SAME"
  grep -h "Response received\|Error\|error" logs/B$L-3-aioquic-client.log | sed 's/^.*INFO //' | head -3
  stop $NPID
  grep -h "h3-interop\]" logs/B$L-neton-server.log | grep -v -- "-> done"
}
run_b g $((P+1)) 1
run_b n $((P+2)) 0
exit 0

#!/bin/bash
# HTTP/3 interop between neton.http.h3 (h3interop.kexe) and h3 0.0.8 + h3-quinn (h3's own examples/server.rs and
# examples/client.rs, and h3-peer built on the same crates), both directions, over real TLS 1.3 on loopback UDP.
# Usage: run-interop.sh <h3interop.kexe> <port base>
# Expects in the current directory: certs/ (gen-certs.sh), target/release/{h3-peer,examples/server,examples/client}.
# Only processes started here are stopped, by their recorded PIDs.
set -u
K=$1
P=${2:-24600}
C=$(pwd)/certs
T=$(pwd)/target/release
LARGE=16777216
mkdir -p logs www
echo "hello from the h3 example server" > www/small.txt
python3 -c "import sys; n=$LARGE; sys.stdout.buffer.write(bytes((i*31+7)%251 for i in range(n)))" > www/large.bin
result() { echo "== $*"; }
nocolor() { sed 's/\x1b\[[0-9;]*m//g'; }
h3log() { nocolor < "$1" | grep -h "response:\|connection closed\|Error\|ERROR" | sed 's/^[^ ]* *//' | head -4; }
stop() { kill "$1" 2>/dev/null; wait "$1" 2>/dev/null; }

# 1. neton client -> h3 examples/server.rs (serving www/; DER certificate and key as the example expects)
$T/examples/server --listen=127.0.0.1:$P --cert=$C/server.der --key=$C/server.key.der --dir=www > logs/1-h3-example-server.log 2>&1 &
SPID=$!
sleep 1
$K client 127.0.0.1:$P localhost $C/ca.pem example $LARGE > logs/1-neton-client.log 2>&1
R=$?
stop $SPID
result "1. neton client -> h3 examples/server.rs: neton exit $R"
grep -h "h3-interop\]" logs/1-neton-client.log
echo "h3 example server: $(grep -c 'successfully respond' logs/1-h3-example-server.log) responses sent; its ERROR lines:"
nocolor < logs/1-h3-example-server.log | grep ERROR | sed 's/^[^ ]* *//' | sort | uniq -c

# 2. h3 examples/client.rs -> neton server (one request per connection, as the example does)
$K server 127.0.0.1:$((P+1)) $C/server.pem $C/server.key > logs/2-neton-server.log 2>&1 &
NPID=$!
sleep 1
$T/examples/client https://127.0.0.1:$((P+1))/ --ca=$C/ca.der > logs/2a-body.txt 2> logs/2a-h3-example-client.log
result "2a. h3 examples/client.rs GET / -> neton server: exit $?, body: $(cat logs/2a-body.txt)"
h3log logs/2a-h3-example-client.log
$T/examples/client https://127.0.0.1:$((P+1))/size/$LARGE --ca=$C/ca.der > logs/2b-body.bin 2> logs/2b-h3-example-client.log
R=$?
cmp -s logs/2b-body.bin www/large.bin && SAME=identical || SAME=DIFFERENT
result "2b. h3 examples/client.rs GET /size/$LARGE -> neton server: exit $R, $(stat -c %s logs/2b-body.bin) bytes, $SAME to the pattern"
h3log logs/2b-h3-example-client.log
$T/examples/client https://127.0.0.1:$((P+1))/missing --ca=$C/ca.der > /dev/null 2> logs/2c-h3-example-client.log
result "2c. h3 examples/client.rs GET /missing -> neton server: exit $?"
h3log logs/2c-h3-example-client.log
$T/examples/client https://127.0.0.1:$((P+1))/ --ca=$C/other-ca.der > /dev/null 2> logs/2d-h3-example-client.log
result "2d. h3 examples/client.rs trusting another CA -> neton server: exit $? (expected to fail)"
nocolor < logs/2d-h3-example-client.log | grep -h "Error" | tail -2
stop $NPID
grep -h "h3-interop\]" logs/2-neton-server.log | grep -v -- "-> done"

# 3. neton client -> h3-peer server (h3 0.0.8 + h3-quinn): every scenario
$T/h3-peer server 127.0.0.1:$((P+2)) $C/server.pem $C/server.key > logs/3-h3-peer-server.log 2>&1 &
SPID=$!
sleep 1
$K client 127.0.0.1:$((P+2)) localhost $C/ca.pem peer $LARGE > logs/3-neton-client.log 2>&1
R=$?
sleep 1
stop $SPID
result "3. neton client -> h3-peer server: neton exit $R"
grep -h "h3-interop\]" logs/3-neton-client.log
grep -h "h3-peer\]" logs/3-h3-peer-server.log | grep -v -- "-> done"

# 4. h3-peer client -> neton server: every scenario
$K server 127.0.0.1:$((P+3)) $C/server.pem $C/server.key > logs/4-neton-server.log 2>&1 &
NPID=$!
sleep 1
$T/h3-peer client 127.0.0.1:$((P+3)) localhost $C/ca.pem $LARGE > logs/4-h3-peer-client.log 2>&1
R=$?
sleep 1
stop $NPID
result "4. h3-peer client -> neton server: h3-peer exit $R"
grep -h "h3-peer\]" logs/4-h3-peer-client.log
grep -h "h3-interop\]" logs/4-neton-server.log | grep -v -- "-> done"

# 5. neton client trusting another CA -> h3-peer server (expected to fail the handshake)
$T/h3-peer server 127.0.0.1:$((P+4)) $C/server.pem $C/server.key > logs/5-h3-peer-server.log 2>&1 &
SPID=$!
sleep 1
$K client 127.0.0.1:$((P+4)) localhost $C/other-ca.pem peer > logs/5-neton-client.log 2>&1
result "5. neton client trusting another CA -> h3-peer server: neton exit $? (expected to fail)"
grep -h "Exception\|Error" logs/5-neton-client.log | head -2
sleep 1
stop $SPID
grep -h "h3-peer\]" logs/5-h3-peer-server.log
exit 0

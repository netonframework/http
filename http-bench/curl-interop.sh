#!/usr/bin/env bash
# curl interop (SPEC §6): runs curl against echoServer and checks what the server saw and what curl got.
# Usage: curl-interop.sh <echoServer binary> [port]. Environment NETON_IO_DRIVER picks the driver; H2=1 runs the
# HTTP/2 cleartext checks (prior knowledge) instead of the HTTP/1.x ones.
set -u
BIN=$1
PORT=${2:-3101}
URL=http://127.0.0.1:$PORT
TMP=$(mktemp -d)
H2=${H2:-0}
# Over HTTP/2 the request target is absolute, built from :scheme and :authority (hyper does the same).
if [ "$H2" = 1 ]; then export NETON_HTTP_H2=1; C="curl --http2-prior-knowledge"; V="HTTP/2.0"; T="$URL"
else C=curl; V="HTTP/1.1"; T=""; fi
"$BIN" 127.0.0.1 "$PORT" > "$TMP/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; wait $SERVER 2>/dev/null; rm -rf "$TMP"' EXIT
for _ in $(seq 50); do $C -s -o /dev/null "$URL/" && break; sleep 0.1; done

fnv() { python3 -c 'import sys
h=0xcbf29ce484222325
for b in open(sys.argv[1],"rb").read(): h=((h^b)*0x100000001b3)&0xffffffffffffffff
print(format(h,"x"))' "$1"; }

pass=0; fail=0
check() { # name, actual, expected
    if [ "$2" == "$3" ]; then pass=$((pass+1)); echo "PASS $1"
    else fail=$((fail+1)); echo "FAIL $1"; echo "  got:      $2"; echo "  expected: $3"; fi
}

head -c 5000000 /dev/urandom > "$TMP/5m"; F5=$(fnv "$TMP/5m")
printf 'hello' > "$TMP/small"; FS=$(fnv "$TMP/small")
: > "$TMP/empty"; FE=$(fnv "$TMP/empty")

check "GET" "$($C -s "$URL/a?b=1")" "GET $T/a?b=1 $V len=0 fnv=$FE trailers="
check "POST small" "$($C -s --data-binary @"$TMP/small" "$URL/p")" "POST $T/p $V len=5 fnv=$FS trailers="
check "PUT from stdin (streamed)" "$($C -s -T - "$URL/put" < "$TMP/5m")" "PUT $T/put $V len=5000000 fnv=$F5 trailers="
check "unknown-length response" "$($C -s "$URL/chunked" | tr '\n' '|')" "GET $T/chunked $V len=0 fnv=$FE trailers=|second piece|third piece|"
$C -s -o "$TMP/big" "$URL/big"
check "1 MiB response" "$(stat -c %s "$TMP/big") $(head -c 26 "$TMP/big")" "1048576 abcdefghijklmnopqrstuvwxyz"
check "HEAD" "$($C -s -I "$URL/big" | tr -d '\r' | grep -i '^content-length')" "content-length: 1048576"
check "HEAD has no body" "$($C -s -I "$URL/big" -w '%{size_download}' -o /dev/null)" "0"
$C -s -v "$URL/k1" "$URL/k2" -o /dev/null -o /dev/null 2> "$TMP/ka"
check "connection reuse" "$(grep -c 'Re-using existing connection' "$TMP/ka")" "1"
check "Date header" "$($C -s -I "$URL/" | grep -ci '^date: ')" "1"
if [ "$H2" = 1 ]; then
    check "POST 5 MB" "$($C -s --data-binary @"$TMP/5m" "$URL/up")" "POST $T/up $V len=5000000 fnv=$F5 trailers="
    # Ten transfers multiplexed on one connection.
    out=$($C -s -Z --parallel-max 10 $(for i in $(seq 10); do echo "$URL/m$i -o $TMP/m$i"; done) && cat "$TMP"/m* | sort | tr '\n' '|')
    check "10 multiplexed streams" "$out" "$(for i in $(seq 10); do echo "GET $T/m$i $V len=0 fnv=$FE trailers="; done | sort | tr '\n' '|')"
    check "response is HTTP/2" "$($C -s -o /dev/null -w '%{http_version}' "$URL/")" "2"
else
    check "HTTP/1.0 GET" "$(curl -s --http1.0 "$URL/")" "GET / HTTP/1.0 len=0 fnv=$FE trailers="
    # 5 MB: curl sends Expect: 100-continue and waits for the 100 before the body.
    out=$(curl -s -v --data-binary @"$TMP/5m" "$URL/up" 2> "$TMP/v")
    check "POST 5 MB with 100-continue" "$out" "POST /up HTTP/1.1 len=5000000 fnv=$F5 trailers="
    check "100 Continue received" "$(grep -c '< HTTP/1.1 100 Continue' "$TMP/v")" "1"
    check "chunked upload" "$(curl -s -H 'Transfer-Encoding: chunked' --data-binary @"$TMP/5m" "$URL/c")" \
        "POST /c HTTP/1.1 len=5000000 fnv=$F5 trailers="
    # httparse accepts only HTTP/1.0 and 1.1 in the request line: hyper answers 400 (Parse::Version).
    check "unknown version is 400" "$(printf 'GET / HTTP/2.0\r\n\r\n' | curl -s -m 2 telnet://127.0.0.1:$PORT | head -c 12)" "HTTP/1.1 400"
fi

echo "passed $pass failed $fail"
[ "$fail" -eq 0 ]

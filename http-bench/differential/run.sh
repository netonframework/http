#!/bin/bash
# The HTTP/1 parsing differential (SPEC §6): builds the reference probes (hyper 1.11.1, Go net/http, Node.js), starts
# them and neton.http's echoServer, and runs differential.py. Needs cargo, go and node.
# Usage: run.sh <echoServer.kexe> [first port, default 3200]
set -u
here=$(cd "$(dirname "$0")" && pwd)
echo_server=$1; base=${2:-3200}
(cd "$here/hyper" && cargo build --release --locked -q) || exit 1
(cd "$here/go" && go build -o probe .) || exit 1
pids=()
"$echo_server" 127.0.0.1 $base > neton-probe.log 2>&1 & pids+=($!)
"$here/hyper/target/release/probe" $((base + 1)) > hyper-probe.log 2>&1 & pids+=($!)
"$here/go/probe" $((base + 2)) > go-probe.log 2>&1 & pids+=($!)
node "$here/node/probe.js" $((base + 3)) > node-probe.log 2>&1 & pids+=($!)
for p in $base $((base + 1)) $((base + 2)) $((base + 3)); do
  for _ in $(seq 1 50); do (exec 3<>/dev/tcp/127.0.0.1/$p) 2>/dev/null && break; sleep 0.1; done
done
echo "node $(node --version), $(go version | cut -d' ' -f3)"
python3 -I "$here/differential.py" neton=$base hyper=$((base + 1)) go=$((base + 2)) node=$((base + 3))
status=$?
kill "${pids[@]}" 2>/dev/null
exit $status

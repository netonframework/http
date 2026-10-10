// An HTTP/1 parse probe on Node.js (llhttp, default strict settings) for differential.py: each request is answered
// with what the server took it to be, "METHOD TARGET len=N fnv=HEX", as http-bench's echoServer does.
// Usage: node probe.js <port>
const http = require('http');

const port = Number(process.argv[2] || 3203);
http.createServer((req, res) => {
  let n = 0;
  let h = 0xcbf29ce484222325n;
  req.on('data', (chunk) => {
    for (const b of chunk) h = BigInt.asUintN(64, (h ^ BigInt(b)) * 0x100000001b3n);
    n += chunk.length;
  });
  req.on('end', () => res.end(`${req.method} ${req.url} len=${n} fnv=${h.toString(16)}\n`));
  req.on('error', () => { res.statusCode = 400; res.end(); });
}).listen(port, '127.0.0.1', () => console.log(`node probe on 127.0.0.1:${port}`));

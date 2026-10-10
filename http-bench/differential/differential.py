#!/usr/bin/env python3
"""HTTP/1.1 parsing differential (SPEC §6): the same ambiguous requests, raw over TCP, to neton.http's echoServer and
to reference servers (hyper 1.11.1, Go net/http, Node.js llhttp), each answering every request with what it took it to
be ("METHOD TARGET ... len=N fnv=HEX"). Every case is followed by a sentinel request on the same connection, so bytes
a server reads as a new request show up as an extra or changed request.

For each case and reference, neton.http's sequence of requests is compared with the reference's:
  same       - the same requests (or both reject before any request);
  stricter   - neton.http rejects where the reference accepted (allowed: the safety baseline is stricter, SPEC §3.9);
  lenient    - neton.http accepts what every reference rejects            -> failure, unless listed in EXPECTED;
  divergent  - both accept, with different requests (a smuggling hazard)  -> failure, unless listed in EXPECTED.

Usage: differential.py neton=PORT [hyper=PORT] [go=PORT] [node=PORT]
"""
import re
import socket
import sys
import time

SENTINEL = b"GET /sentinel HTTP/1.1\r\nHost: s\r\n\r\n"


def req(head, body=b""):
    return head.encode("latin-1") + body


CASES = [
    # Baselines.
    ("get", req("GET /a HTTP/1.1\r\nHost: h\r\n\r\n")),
    ("post-cl", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\n", b"hello")),
    ("post-chunked", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("chunked-trailers", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"5\r\nhello\r\n0\r\nX-T: 1\r\n\r\n")),
    # Content-Length and Transfer-Encoding together (CL.TE / TE.CL).
    ("cl-then-te", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-then-cl", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nContent-Length: 30\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    # Content-Length forms.
    ("cl-twice-same", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\n", b"hello")),
    ("cl-twice-different", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nContent-Length: 3\r\n\r\n", b"hello")),
    ("cl-list", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 5, 5\r\n\r\n", b"hello")),
    ("cl-plus", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: +5\r\n\r\n", b"hello")),
    ("cl-negative", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: -5\r\n\r\n", b"hello")),
    ("cl-hex", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 0x5\r\n\r\n", b"hello")),
    ("cl-trailing-space", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 5 \r\n\r\n", b"hello")),
    ("cl-overflow", req("POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 99999999999999999999999\r\n\r\n", b"hello")),
    # Transfer-Encoding forms.
    ("te-chunked-twice", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-chunked-chunked", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked, chunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-gzip-chunked", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: gzip, chunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-chunked-gzip", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked, gzip\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-identity", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: identity\r\nContent-Length: 5\r\n\r\n", b"hello")),
    ("te-xchunked", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: xchunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-uppercase", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: CHUNKED\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-space-before-colon", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding : chunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-tab-value", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding:\tchunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-obs-fold", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding:\r\n chunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    ("te-http10", req("POST /a HTTP/1.0\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"5\r\nhello\r\n0\r\n\r\n")),
    # Chunk framing.
    ("chunk-ext", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"5;a=b\r\nhello\r\n0\r\n\r\n")),
    ("chunk-ext-quoted", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"5;a=\"b c\"\r\nhello\r\n0\r\n\r\n")),
    ("chunk-leading-zeros", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"0005\r\nhello\r\n0\r\n\r\n")),
    ("chunk-size-0x", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"0x5\r\nhello\r\n0\r\n\r\n")),
    ("chunk-size-plus", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"+5\r\nhello\r\n0\r\n\r\n")),
    ("chunk-size-space", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"5 \r\nhello\r\n0\r\n\r\n")),
    ("chunk-size-overflow", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"10000000000000005\r\nhello\r\n0\r\n\r\n")),
    ("chunk-data-overrun", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"3\r\nhello\r\n0\r\n\r\n")),
    ("chunk-lf-only", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"5\nhello\n0\n\n")),
    ("chunk-missing-last", req("POST /a HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n", b"5\r\nhello\r\n")),
    # Line endings and header syntax.
    ("bare-lf", req("GET /a HTTP/1.1\nHost: h\n\n")),
    ("bare-cr-in-value", req("GET /a HTTP/1.1\r\nHost: h\r\nX-A: a\rb\r\n\r\n")),
    ("nul-in-value", req("GET /a HTTP/1.1\r\nHost: h\r\nX-A: a\x00b\r\n\r\n")),
    ("space-in-name", req("GET /a HTTP/1.1\r\nHost: h\r\nX A: b\r\n\r\n")),
    ("obs-fold-other", req("GET /a HTTP/1.1\r\nHost: h\r\nX-A: a\r\n b\r\n\r\n")),
    ("leading-crlf", req("\r\nGET /a HTTP/1.1\r\nHost: h\r\n\r\n")),
    # Request line.
    ("double-space", req("GET  /a HTTP/1.1\r\nHost: h\r\n\r\n")),
    ("space-in-target", req("GET /a b HTTP/1.1\r\nHost: h\r\n\r\n")),
    ("absolute-form", req("GET http://h/a HTTP/1.1\r\nHost: h\r\n\r\n")),
    ("lowercase-method", req("get /a HTTP/1.1\r\nHost: h\r\n\r\n")),
    ("version-1.2", req("GET /a HTTP/1.2\r\nHost: h\r\n\r\n")),
    ("version-lowercase", req("GET /a http/1.1\r\nHost: h\r\n\r\n")),
    ("no-host", req("GET /a HTTP/1.1\r\n\r\n")),
    ("two-hosts", req("GET /a HTTP/1.1\r\nHost: h\r\nHost: i\r\n\r\n")),
    ("get-with-body", req("GET /a HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\n", b"hello")),
]

# Divergences examined and accepted (case -> (references, reason)). Empty until one is found and justified.
EXPECTED = {}

LINE = re.compile(rb"^(\S+) (\S+) (?:HTTP/\S+ )?len=(\d+) fnv=([0-9a-f]+)")


def exchange(port, data, idle=1.0, total=6.0):
    """Sends data and the sentinel; returns what was read until the server closed (True) or stayed quiet for idle s."""
    s = socket.create_connection(("127.0.0.1", port), timeout=total)
    out = b""
    closed = False
    try:
        s.sendall(data + SENTINEL)
        s.settimeout(idle)
        deadline = time.monotonic() + total
        while time.monotonic() < deadline:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                break
            except ConnectionResetError:
                closed = True
                break
            if not chunk:
                closed = True
                break
            out += chunk
    finally:
        s.close()
    return out, closed


def responses(buf):
    """Splits buf into (status, body) responses (Content-Length, chunked, or to the end)."""
    out = []
    while buf:
        end = buf.find(b"\r\n\r\n")
        if end < 0:
            break
        head, buf = buf[:end].decode("latin-1"), buf[end + 4:]
        lines = head.split("\r\n")
        m = re.match(r"HTTP/\d\.\d (\d{3})", lines[0])
        if not m:
            break
        status = int(m.group(1))
        headers = {}
        for line in lines[1:]:
            k, _, v = line.partition(":")
            headers[k.strip().lower()] = v.strip()
        if status // 100 == 1:
            continue
        if "content-length" in headers:
            n = int(headers["content-length"])
            body, buf = buf[:n], buf[n:]
        elif headers.get("transfer-encoding", "").lower() == "chunked":
            body = b""
            while True:
                e = buf.find(b"\r\n")
                n = int(buf[:e].split(b";")[0], 16)
                buf = buf[e + 2:]
                if n == 0:
                    buf = buf[buf.find(b"\r\n\r\n") + 4:] if not buf.startswith(b"\r\n") else buf[2:]
                    break
                body, buf = body + buf[:n], buf[n + 2:]
        else:
            body, buf = buf, b""
        out.append((status, body))
    return out


def interpret(exchanged):
    """The requests a server took the bytes to be: ('ok', method, target, len, fnv) ..., then ('reject', status), or
    ('hung',) when it neither answered nor closed the connection."""
    buf, closed = exchanged
    seq = []
    for status, body in responses(buf):
        m = LINE.match(body)
        if status == 200 and m:
            seq.append(("ok",) + tuple(x.decode("latin-1") for x in m.groups()))
        else:
            seq.append(("reject", status))
            break
    if not closed and not (seq and seq[-1] == ("ok", "GET", "/sentinel") + seq[-1][3:]):
        seq.append(("hung",))
    return tuple(seq)


def accepted(seq):
    return tuple(x for x in seq if x[0] == "ok")


def compare(ours, ref):
    if ours == ref or accepted(ours) == accepted(ref):
        return "same"
    a, b = accepted(ours), accepted(ref)
    if len(a) < len(b) and b[:len(a)] == a:
        return "stricter"
    if len(b) < len(a) and a[:len(b)] == b:
        return "lenient"
    return "divergent"


def show(seq):
    def one(x):
        if x[0] == "ok":
            return "%s %s" % (x[1], x[2])
        return "reject %s" % x[1] if x[0] == "reject" else "no answer, connection left open"
    return " ; ".join(one(x) for x in seq) or "no answer, closed"


def main():
    servers = dict(a.split("=") for a in sys.argv[1:])
    ports = {k: int(v) for k, v in servers.items()}
    refs = [k for k in ports if k != "neton"]
    failures = 0
    counts = {}
    for name, data in CASES:
        seqs = {k: interpret(exchange(p, data)) for k, p in ports.items()}
        ours = seqs["neton"]
        verdicts = {r: compare(ours, seqs[r]) for r in refs}
        for v in verdicts.values():
            counts[v] = counts.get(v, 0) + 1
        bad = [r for r, v in verdicts.items() if v == "divergent"]
        if ours and ours[-1] == ("hung",):
            bad = bad or ["(hangs)"]
        if refs and all(verdicts[r] == "lenient" for r in refs):
            bad = refs
        expected = EXPECTED.get(name)
        flag = ""
        if bad and not (expected and set(bad) <= set(expected[0])):
            failures += 1
            flag = "FAIL"
        elif bad:
            flag = "expected"
        print("%-22s %-5s neton: %s" % (name, flag, show(ours)))
        for r in refs:
            print("%22s %-10s %s: %s" % ("", verdicts[r], r, show(seqs[r])))
    print("%d cases, %d references; verdicts %s; %d failure(s)" % (len(CASES), len(refs), counts, failures))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()

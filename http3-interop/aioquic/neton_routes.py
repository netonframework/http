# ASGI application for aioquic's examples/http3_server.py with the routes of the neton and h3 interop peers:
#   GET /           200 "hello from aioquic\n"
#   GET /size/<n>   200 with n bytes of the pattern byte(i) = (i * 31 + 7) % 251
#   POST /echo      200, the request body echoed as it arrives
#   otherwise       404
# (Trailers and GOAWAY are not reachable through aioquic's ASGI server, so the neton client runs its "basic" scenarios.)
# Usage: python http3_server.py --host 127.0.0.1 --port P -c server.pem -k server.key neton_routes:app

_PERIOD = bytes((i * 31 + 7) % 251 for i in range(251))
_BASE = _PERIOD * (65536 // 251 + 2)


def pattern(offset: int, size: int) -> bytes:
    start = offset % 251
    return _BASE[start:start + size]


async def app(scope, receive, send):
    if scope["type"] != "http":
        return
    method, path = scope["method"], scope["path"]
    if method == "GET" and path == "/":
        await send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"text/plain")]})
        await send({"type": "http.response.body", "body": b"hello from aioquic\n"})
    elif method == "GET" and path.startswith("/size/"):
        size = int(path[len("/size/"):])
        await send({"type": "http.response.start", "status": 200, "headers": [(b"content-length", str(size).encode())]})
        sent = 0
        while sent < size:
            n = min(32768, size - sent)
            await send({"type": "http.response.body", "body": pattern(sent, n), "more_body": True})
            sent += n
        await send({"type": "http.response.body", "body": b""})
    elif method == "POST" and path == "/echo":
        await send({"type": "http.response.start", "status": 200, "headers": []})
        while True:
            message = await receive()
            if message["type"] != "http.request":
                break
            body = message.get("body", b"")
            if body:
                await send({"type": "http.response.body", "body": body, "more_body": True})
            if not message.get("more_body", False):
                break
        await send({"type": "http.response.body", "body": b""})
    else:
        await send({"type": "http.response.start", "status": 404, "headers": []})
        await send({"type": "http.response.body", "body": b""})

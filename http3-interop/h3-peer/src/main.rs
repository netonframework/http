//! HTTP/3 interop peer on h3 0.0.8 + h3-quinn (the reference implementation), mirroring the neton peer:
//!
//!   h3-peer server <addr:port> <cert.pem> <key.pem>
//!     GET /           200 "hello from h3 0.0.8\n"
//!     GET /size/<n>   200 with n bytes of the pattern byte(i) = (i * 31 + 7) % 251
//!     POST /echo      200, the request body echoed as it arrives; if the request had trailers, the response has
//!                     trailers x-echo-<name> for each of them and x-body-length
//!     GET /goaway     GOAWAY (`shutdown(0)`), then 200 "goaway"; the connection closes once accept() reports the end
//!     otherwise       404
//!   h3-peer client <addr:port> <server name> <ca.pem> [large bytes]
//!     the scenarios of the neton client's "peer" mode; exit status 0 only if all passed.

use std::{future::Future, net::SocketAddr, sync::Arc, time::Duration, time::Instant};

use anyhow::{anyhow, bail, ensure, Context, Result};
use bytes::{Buf, Bytes};
use futures::future;
use h3_quinn::quinn;
use http::{HeaderMap, HeaderName, HeaderValue, Method, Request, Response, StatusCode};
use quinn::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use rustls::pki_types::{CertificateDer, PrivateKeyDer};

type ServerStream = h3::server::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>;
type Sender = h3::client::SendRequest<h3_quinn::OpenStreams, Bytes>;

fn log(msg: impl AsRef<str>) {
    println!("[h3-peer] {}", msg.as_ref());
}

fn pattern_byte(offset: u64) -> u8 {
    ((offset * 31 + 7) % 251) as u8
}

fn pattern(offset: u64, size: usize) -> Bytes {
    (0..size as u64).map(|i| pattern_byte(offset + i)).collect::<Vec<u8>>().into()
}

fn check_pattern(chunk: &[u8], offset: u64) -> Result<u64> {
    for (i, b) in chunk.iter().enumerate() {
        ensure!(*b == pattern_byte(offset + i as u64), "body byte {} differs", offset + i as u64);
    }
    Ok(offset + chunk.len() as u64)
}

fn load_certs(path: &str) -> Result<Vec<CertificateDer<'static>>> {
    let mut r = std::io::BufReader::new(std::fs::File::open(path).with_context(|| path.to_string())?);
    Ok(rustls_pemfile::certs(&mut r).collect::<Result<Vec<_>, _>>()?)
}

fn load_key(path: &str) -> Result<PrivateKeyDer<'static>> {
    let mut r = std::io::BufReader::new(std::fs::File::open(path).with_context(|| path.to_string())?);
    rustls_pemfile::private_key(&mut r)?.ok_or_else(|| anyhow!("no private key in {path}"))
}

fn provider() -> Arc<rustls::crypto::CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("server") => server(args[2].parse()?, &args[3], &args[4]).await,
        Some("client") => {
            let large = args.get(5).map(|s| s.parse()).transpose()?.unwrap_or(16u64 << 20);
            let failed = client(args[2].parse()?, &args[3], &args[4], large).await?;
            std::process::exit(if failed == 0 { 0 } else { 1 });
        }
        _ => bail!("usage: h3-peer server <addr> <cert> <key> | client <addr> <name> <ca> [large]"),
    }
}

// ------------------------------------------------------------------------------------------------------------------
// Server

async fn server(listen: SocketAddr, cert: &str, key: &str) -> Result<()> {
    let mut tls = rustls::ServerConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_no_client_auth()
        .with_single_cert(load_certs(cert)?, load_key(key)?)?;
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let config = quinn::ServerConfig::with_crypto(Arc::new(QuicServerConfig::try_from(tls)?));
    let endpoint = quinn::Endpoint::server(config, listen)?;
    log(format!("server listening on {listen}"));
    let mut n = 0;
    while let Some(incoming) = endpoint.accept().await {
        let id = n;
        n += 1;
        tokio::spawn(async move {
            if let Err(e) = serve_connection(id, incoming).await {
                log(format!("conn {id}: {e:#}"));
            }
        });
    }
    Ok(())
}

async fn serve_connection(id: usize, incoming: quinn::Incoming) -> Result<()> {
    let conn = incoming.await.context("handshake")?;
    let alpn = conn
        .handshake_data()
        .and_then(|d| d.downcast::<quinn::crypto::rustls::HandshakeData>().ok())
        .and_then(|d| d.protocol.clone())
        .map(|p| String::from_utf8_lossy(&p).into_owned());
    log(format!("conn {id}: from {}, ALPN {alpn:?}", conn.remote_address()));
    let mut h3c: h3::server::Connection<h3_quinn::Connection, Bytes> =
        h3::server::Connection::new(h3_quinn::Connection::new(conn.clone())).await?;
    let mut requests = 0;
    loop {
        match h3c.accept().await {
            Ok(Some(resolver)) => {
                requests += 1;
                let (req, stream) = match resolver.resolve_request().await {
                    Ok(r) => r,
                    Err(e) => {
                        log(format!("conn {id}: request failed before its head: {e}"));
                        continue;
                    }
                };
                if req.method() == Method::GET && req.uri().path() == "/goaway" {
                    h3c.shutdown(0).await?;
                    log(format!("conn {id}: GOAWAY sent"));
                }
                tokio::spawn(async move {
                    let what = format!("{} {}", req.method(), req.uri().path());
                    match handle(req, stream).await {
                        Ok(()) => log(format!("conn {id}: {what} -> done")),
                        Err(e) => log(format!("conn {id}: {what} -> {e:#}")),
                    }
                });
            }
            Ok(None) => {
                log(format!("conn {id}: accept: end (GOAWAY, nothing in progress)"));
                break;
            }
            Err(e) => {
                log(format!("conn {id}: accept ended: {e}"));
                break;
            }
        }
    }
    drop(h3c); // closes the connection with H3_NO_ERROR, as h3's examples/server.rs does
    let reason = conn.closed().await;
    log(format!("conn {id}: done after {requests} requests; QUIC: {reason}"));
    Ok(())
}

async fn handle(req: Request<()>, mut stream: ServerStream) -> Result<()> {
    let path = req.uri().path().to_string();
    match (req.method().clone(), path.as_str()) {
        (Method::GET, "/") => {
            stream.send_response(Response::builder().status(200).header("content-type", "text/plain").body(())?).await?;
            stream.send_data(Bytes::from_static(b"hello from h3 0.0.8\n")).await?;
        }
        (Method::GET, p) if p.starts_with("/size/") => {
            let size: u64 = p["/size/".len()..].parse()?;
            stream.send_response(Response::builder().status(200).header("content-length", size).body(())?).await?;
            let mut sent = 0u64;
            while sent < size {
                let n = (size - sent).min(32 * 1024) as usize;
                stream.send_data(pattern(sent, n)).await?;
                sent += n as u64;
            }
        }
        (Method::POST, "/echo") => {
            stream.send_response(Response::builder().status(200).body(())?).await?;
            let mut length = 0usize;
            while let Some(mut chunk) = stream.recv_data().await? {
                let b = chunk.copy_to_bytes(chunk.remaining());
                length += b.len();
                stream.send_data(b).await?;
            }
            if let Some(trailers) = stream.recv_trailers().await? {
                let mut echo = HeaderMap::new();
                for (name, value) in trailers.iter() {
                    echo.append(HeaderName::from_bytes(format!("x-echo-{name}").as_bytes())?, value.clone());
                }
                echo.append("x-body-length", HeaderValue::from(length));
                stream.send_trailers(echo).await?;
            }
        }
        (Method::GET, "/goaway") => {
            stream.send_response(Response::builder().status(200).body(())?).await?;
            stream.send_data(Bytes::from_static(b"goaway")).await?;
        }
        _ => stream.send_response(Response::builder().status(404).body(())?).await?,
    }
    stream.finish().await?;
    Ok(())
}

// ------------------------------------------------------------------------------------------------------------------
// Client

struct Session {
    send: Sender,
    drive: tokio::task::JoinHandle<h3::error::ConnectionError>,
    goaway: Option<tokio::sync::oneshot::Sender<()>>,
    conn: quinn::Connection,
}

async fn connect(endpoint: &quinn::Endpoint, addr: SocketAddr, name: &str) -> Result<Session> {
    let conn = endpoint.connect(addr, name)?.await?;
    let alpn = conn
        .handshake_data()
        .and_then(|d| d.downcast::<quinn::crypto::rustls::HandshakeData>().ok())
        .and_then(|d| d.protocol.clone())
        .map(|p| String::from_utf8_lossy(&p).into_owned());
    log(format!("connected to {addr} ({name}): ALPN {alpn:?}"));
    let (mut driver, send) = h3::client::new(h3_quinn::Connection::new(conn.clone())).await?;
    let (tx, rx) = tokio::sync::oneshot::channel::<()>();
    let drive = tokio::spawn(async move {
        tokio::select! {
            e = future::poll_fn(|cx| driver.poll_close(cx)) => return e,
            _ = rx => {}
        }
        // The client's GOAWAY (`shutdown`), then keep driving until the connection closes.
        if let Err(e) = driver.shutdown(0).await {
            return e;
        }
        future::poll_fn(|cx| driver.poll_close(cx)).await
    });
    Ok(Session { send, drive, goaway: Some(tx), conn })
}

struct Results {
    passed: usize,
    failed: usize,
}

impl Results {
    async fn run<F: Future<Output = Result<String>>>(&mut self, name: &str, f: F) {
        let start = Instant::now();
        match tokio::time::timeout(Duration::from_secs(120), f).await {
            Ok(Ok(detail)) => {
                self.passed += 1;
                log(format!("PASS {name}: {detail} ({:?})", start.elapsed()));
            }
            Ok(Err(e)) => {
                self.failed += 1;
                log(format!("FAIL {name}: {e:#}"));
            }
            Err(_) => {
                self.failed += 1;
                log(format!("FAIL {name}: timed out"));
            }
        }
    }
}

async fn get(send: &mut Sender, authority: &str, path: &str) -> Result<(StatusCode, Vec<u8>)> {
    let mut s = send.send_request(Request::get(format!("https://{authority}{path}")).body(())?).await?;
    s.finish().await?;
    let resp = s.recv_response().await?;
    let mut body = Vec::new();
    while let Some(mut chunk) = s.recv_data().await? {
        body.extend_from_slice(&chunk.copy_to_bytes(chunk.remaining()));
    }
    Ok((resp.status(), body))
}

async fn get_pattern(mut send: Sender, authority: String, path: String, size: u64) -> Result<String> {
    let mut s = send.send_request(Request::get(format!("https://{authority}{path}")).body(())?).await?;
    s.finish().await?;
    let resp = s.recv_response().await?;
    ensure!(resp.status() == StatusCode::OK, "status {}", resp.status());
    let mut received = 0u64;
    while let Some(mut chunk) = s.recv_data().await? {
        received = check_pattern(&chunk.copy_to_bytes(chunk.remaining()), received)?;
    }
    ensure!(received == size, "received {received} of {size} bytes");
    Ok(format!("{size} bytes, pattern verified"))
}

async fn read_text<S: h3::quic::RecvStream>(s: &mut h3::client::RequestStream<S, Bytes>) -> Result<String> {
    let mut out = Vec::new();
    while let Some(mut chunk) = s.recv_data().await? {
        out.extend_from_slice(&chunk.copy_to_bytes(chunk.remaining()));
    }
    Ok(String::from_utf8(out)?)
}

async fn client(addr: SocketAddr, name: &str, ca: &str, large: u64) -> Result<usize> {
    let mut roots = rustls::RootCertStore::empty();
    for c in load_certs(ca)? {
        roots.add(c)?;
    }
    let mut tls = rustls::ClientConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_root_certificates(roots)
        .with_no_client_auth();
    tls.alpn_protocols = vec![b"h3".to_vec()];
    let mut endpoint = quinn::Endpoint::client("0.0.0.0:0".parse()?)?;
    endpoint.set_default_client_config(quinn::ClientConfig::new(Arc::new(QuicClientConfig::try_from(tls)?)));
    let authority = format!("{name}:{}", addr.port());
    let a = authority.as_str();
    let mut r = Results { passed: 0, failed: 0 };

    let mut s = connect(&endpoint, addr, name).await?;
    r.run("get", async {
        let (status, body) = get(&mut s.send, a, "/").await?;
        ensure!(status == StatusCode::OK && !body.is_empty(), "status {status}");
        Ok(format!("200 {:?}", String::from_utf8_lossy(&body).trim()))
    })
    .await;
    r.run("not-found", async {
        let (status, _) = get(&mut s.send, a, "/missing").await?;
        ensure!(status == StatusCode::NOT_FOUND, "status {status}");
        Ok("404".to_string())
    })
    .await;
    r.run("post", async {
        let body = "hello over HTTP/3 ".repeat(64);
        let mut st = s.send.send_request(Request::post(format!("https://{a}/echo")).header("content-type", "text/plain").body(())?).await?;
        st.send_data(Bytes::from(body.clone())).await?;
        st.finish().await?;
        ensure!(st.recv_response().await?.status() == StatusCode::OK);
        ensure!(read_text(&mut st).await? == body, "echo differs");
        Ok(format!("{} bytes echoed", body.len()))
    })
    .await;
    r.run("large-upload-echo", async {
        let st = s.send.send_request(Request::post(format!("https://{a}/echo")).body(())?).await?;
        let (mut tx, mut rx) = st.split();
        let writer = tokio::spawn(async move {
            let mut sent = 0u64;
            while sent < large {
                let n = (large - sent).min(64 * 1024) as usize;
                tx.send_data(pattern(sent, n)).await?;
                sent += n as u64;
            }
            tx.finish().await?;
            Ok::<_, anyhow::Error>(())
        });
        ensure!(rx.recv_response().await?.status() == StatusCode::OK);
        let mut received = 0u64;
        while let Some(mut chunk) = rx.recv_data().await? {
            received = check_pattern(&chunk.copy_to_bytes(chunk.remaining()), received)?;
        }
        writer.await??;
        ensure!(received == large, "echoed {received} of {large}");
        Ok(format!("{large} bytes up and echoed back, pattern verified"))
    })
    .await;
    r.run("large-download", get_pattern(s.send.clone(), authority.clone(), format!("/size/{large}"), large)).await;
    r.run("many-requests-one-connection", async {
        let all = (0..100u64).map(|i| get_pattern(s.send.clone(), authority.clone(), format!("/size/{}", 1000 + i), 1000 + i));
        for res in future::join_all(all).await {
            res?;
        }
        for i in 0..50u64 {
            get_pattern(s.send.clone(), authority.clone(), format!("/size/{i}"), i).await?;
        }
        Ok("100 concurrent + 50 sequential requests on one connection".to_string())
    })
    .await;
    r.run("trailers", async {
        let mut st = s.send.send_request(Request::post(format!("https://{a}/echo")).body(())?).await?;
        st.send_data(Bytes::from_static(b"body with trailers")).await?;
        let mut t = HeaderMap::new();
        t.insert("x-checksum", HeaderValue::from_static("abc123"));
        st.send_trailers(t).await?;
        st.finish().await?;
        ensure!(st.recv_response().await?.status() == StatusCode::OK);
        ensure!(read_text(&mut st).await? == "body with trailers");
        let trailers = st.recv_trailers().await?.ok_or_else(|| anyhow!("no response trailers"))?;
        ensure!(trailers.get("x-echo-x-checksum").map(|v| v.as_bytes()) == Some(&b"abc123"[..]), "trailers {trailers:?}");
        ensure!(trailers.get("x-body-length").map(|v| v.as_bytes()) == Some(&b"18"[..]), "trailers {trailers:?}");
        Ok("request trailer echoed in response trailers (x-echo-x-checksum, x-body-length 18)".to_string())
    })
    .await;
    r.run("server-goaway", async move {
        let mut open = s.send.send_request(Request::post(format!("https://{a}/echo")).body(())?).await?;
        open.send_data(Bytes::from_static(b"part1 ")).await?;
        ensure!(open.recv_response().await?.status() == StatusCode::OK);
        let (status, body) = get(&mut s.send, a, "/goaway").await?;
        ensure!(status == StatusCode::OK && body == b"goaway", "goaway: {status}");
        // h3's client has no "is closing" accessor: a request is refused once the driver has seen the GOAWAY.
        let deadline = Instant::now() + Duration::from_secs(10);
        let refused = loop {
            tokio::time::sleep(Duration::from_millis(20)).await;
            match s.send.send_request(Request::get(format!("https://{a}/")).body(())?).await {
                Err(e) => break e,
                Ok(mut late) => {
                    // Sent before the GOAWAY was seen: the server may refuse it (H3_REQUEST_REJECTED) or answer it.
                    let _ = late.finish().await;
                    let _ = late.recv_response().await;
                    ensure!(Instant::now() < deadline, "the GOAWAY never arrived");
                }
            }
        };
        open.send_data(Bytes::from_static(b"part2")).await?;
        open.finish().await?;
        ensure!(read_text(&mut open).await? == "part1 part2", "in-flight echo differs");
        drop(open);
        // Nothing left in progress: the server may close now (RFC 9114 §5.2); if it has not within 3 s, the client
        // closes by dropping its last SendRequest.
        let waited = tokio::time::timeout(Duration::from_secs(3), &mut s.drive).await;
        let (end, by) = match waited {
            Ok(end) => (end?, "the server closed the connection"),
            Err(_) => {
                let Session { send, drive, .. } = s;
                drop(send);
                (tokio::time::timeout(Duration::from_secs(20), drive).await??, "the server left it open; the client closed it")
            }
        };
        ensure!(end.is_h3_no_error(), "connection ended with {end:?}");
        Ok(format!("in-flight request completed, new request refused ({refused}), {by}: {end}"))
    })
    .await;

    let mut s2 = connect(&endpoint, addr, name).await?;
    r.run("client-goaway-and-close", async move {
        let (status, _) = get(&mut s2.send, a, "/").await?;
        ensure!(status == StatusCode::OK);
        s2.goaway.take().unwrap().send(()).ok();
        tokio::time::sleep(Duration::from_millis(100)).await;
        let Session { send, drive, conn, .. } = s2;
        drop(send); // the last SendRequest: h3 closes the connection with H3_NO_ERROR
        let end = tokio::time::timeout(Duration::from_secs(20), drive).await??;
        ensure!(end.is_h3_no_error(), "connection ended with {end:?}");
        Ok(format!("client GOAWAY sent; connection ended {end}; QUIC {:?}", conn.close_reason()))
    })
    .await;

    endpoint.wait_idle().await;
    log(format!("client done: {} passed, {} failed", r.passed, r.failed));
    Ok(r.failed)
}

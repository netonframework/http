// An HTTP/1 parse probe on hyper (differential.py): each request is answered with what the server took it to be,
// "METHOD TARGET len=N fnv=HEX", as http-bench's echoServer does. Usage: probe <port>
use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Request, Response};
use hyper_util::rt::TokioIo;
use tokio::net::TcpListener;

async fn probe(req: Request<Incoming>) -> Result<Response<Full<Bytes>>, hyper::Error> {
    let method = req.method().to_string();
    let target = req.uri().to_string();
    let body = req.into_body().collect().await?.to_bytes();
    let mut h: u64 = 0xcbf29ce484222325;
    for b in body.iter() {
        h = (h ^ *b as u64).wrapping_mul(0x100000001b3);
    }
    let line = format!("{} {} len={} fnv={:x}\n", method, target, body.len(), h);
    Ok(Response::new(Full::new(Bytes::from(line))))
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let port: u16 = std::env::args().nth(1).and_then(|p| p.parse().ok()).unwrap_or(3201);
    let listener = TcpListener::bind(("127.0.0.1", port)).await.unwrap();
    println!("hyper probe on 127.0.0.1:{}", port);
    loop {
        let (stream, _) = listener.accept().await.unwrap();
        tokio::spawn(async move {
            let _ = http1::Builder::new().serve_connection(TokioIo::new(stream), service_fn(probe)).await;
        });
    }
}

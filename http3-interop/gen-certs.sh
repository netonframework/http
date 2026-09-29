#!/bin/bash
# A throwaway test CA and a server certificate it issues for localhost / 127.0.0.1 (ECDSA P-256), for the HTTP/3
# interop runs only. Every client trusts the CA explicitly. PEM for neton, aioquic and the Rust h3 peer; DER for h3's
# examples (examples/server.rs reads a DER certificate and a DER PKCS#8 key, examples/client.rs a DER CA).
set -euo pipefail
mkdir -p certs && cd certs
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 30 \
  -subj "/O=neton http3 interop/CN=interop test CA" -keyout ca.key -out ca.pem \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj "/O=neton http3 interop/CN=localhost" \
  -keyout server.key -out server.csr
printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n' > server.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 30 -extfile server.ext -out server.pem
openssl x509 -in ca.pem -outform DER -out ca.der
openssl x509 -in server.pem -outform DER -out server.der
openssl pkcs8 -topk8 -nocrypt -in server.key -outform DER -out server.key.der
# A second CA nobody's certificate chains to, for the rejection runs
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 30 \
  -subj "/O=neton http3 interop/CN=unrelated CA" -keyout other-ca.key -out other-ca.pem
openssl x509 -in other-ca.pem -outform DER -out other-ca.der
openssl x509 -in server.pem -noout -subject -issuer -ext subjectAltName

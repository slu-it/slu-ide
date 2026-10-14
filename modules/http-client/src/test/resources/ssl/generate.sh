#!/usr/bin/env bash
# Regenerates the TLS test fixtures in this folder (see README.md). Needs OpenSSL 3.
set -euo pipefail
cd "$(dirname "$0")"

# LibreSSL (the default openssl on macOS) lacks options used below, e.g. `rsa -traditional`.
if ! openssl version | grep -q '^OpenSSL 3'; then
    echo "OpenSSL 3 is needed, found: $(openssl version)" >&2
    echo "On macOS: brew install openssl@3 and put its bin folder first on the PATH" >&2
    exit 1
fi

days=36500           # about 100 years
passphrase=secret    # client-encrypted.key and client.p12
server_password=changeit

# Test CA
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days "$days" -subj "/CN=SluIde Test CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"

# Server certificate for the name localhost only (no IP address), so 127.0.0.1 fails host name verification
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=localhost"
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -set_serial 2 -days "$days" -out server.pem \
    -extfile <(printf "subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n")
openssl pkcs12 -export -in server.pem -inkey server.key -certfile ca.pem -name server -out server.p12 \
    -passout "pass:$server_password"

# Client certificate and its key in all supported forms
openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr -subj "/CN=SluIde Test Client"
openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -set_serial 3 -days "$days" -out client.pem \
    -extfile <(printf "extendedKeyUsage=clientAuth\n")
openssl pkcs8 -topk8 -in client.key -out client-encrypted.key -passout "pass:$passphrase"
openssl pkcs8 -topk8 -v2 des3 -in client.key -out client-des3.key -passout "pass:$passphrase"
openssl rsa -in client.key -traditional -out client-pkcs1.key
openssl pkcs12 -export -in client.pem -inkey client.key -name client -out client.p12 -passout "pass:$passphrase"
openssl x509 -in client.pem -outform DER -out client-cert.der
openssl pkcs8 -topk8 -nocrypt -in client.key -outform DER -out client-key.der

# Keys that cannot be used with client.pem
openssl ecparam -name prime256v1 -genkey -noout -out client-ec.key
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out other.key

# The CA key is not needed by the tests; a new CA is made on each run.
rm -f server.csr client.csr server.key ca.key

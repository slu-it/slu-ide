# TLS test fixtures

> **Test-only keys, never used anywhere else.** These private keys are public by being in this repository; they
> protect nothing and must never be trusted or reused outside these tests.

Certificates and keys for the TLS tests (`SslContextsTest`, `RequestRunnerTest`), made with OpenSSL 3 by
`generate.sh` and valid for about 100 years.

| File | Content |
|------|---------|
| `ca.pem` | Test CA (self-signed, RSA 2048) that signed all certificates below; its key is not kept |
| `server.pem` | Server certificate for `localhost` (SAN `DNS:localhost` only, no IP address) |
| `server.p12` | Server certificate, key and CA in PKCS#12, password `changeit` |
| `client.pem` | Client certificate (`CN=SluIde Test Client`, extended key usage `clientAuth`) |
| `client.key` | Its key, PEM PKCS#8 (`BEGIN PRIVATE KEY`) |
| `client-encrypted.key` | Its key, encrypted PEM PKCS#8 (PBES2, AES-256), passphrase `secret` |
| `client-des3.key` | Its key, encrypted PEM PKCS#8 with PBES2 and 3DES, which the JDK cannot read |
| `client-pkcs1.key` | Its key, PEM PKCS#1 (`BEGIN RSA PRIVATE KEY`) |
| `client.p12` | Client certificate and key in PKCS#12, passphrase `secret` |
| `client-cert.der`, `client-key.der` | Client certificate and PKCS#8 key without PEM armour |
| `client-ec.key` | An EC key in SEC1 form (`BEGIN EC PRIVATE KEY`), which is not supported |
| `other.key` | An RSA key that does not belong to `client.pem` |
| `client-ecdsa.pem`, `client-ecdsa.key` | Self-signed certificate and its EC P-256 key, PEM PKCS#8 |
| `client-ecdsa-encrypted.key` | The same EC key, encrypted PEM PKCS#8 (PBES2, AES-256), passphrase `secret` |
| `client-ed25519.pem`, `client-ed25519.key` | Self-signed certificate and its Ed25519 key, PEM PKCS#8 |
| `client-ed448.pem`, `client-ed448.key` | Self-signed certificate and its Ed448 key, PEM PKCS#8 |
| `client-dsa.pem`, `client-dsa.key` | Self-signed certificate and its DSA (2048/256) key, PEM PKCS#8 |
| `client-bad-cert.p12` | `client.p12` re-made with `-certpbe NONE -nomac` (passphrase `secret`), then the first byte inside its certificate (the tbsCertificate SEQUENCE tag `0x30`) changed to `0x31`: loading it throws a `CertificateParsingException` |
| `other-ecdsa.key`, `other-ed25519.key`, `other-ed448.key`, `other-dsa.key` | Keys of those algorithms that belong to none of the certificates |

To regenerate all of them (the tests do not depend on particular keys), run `./generate.sh` in this folder; it needs
OpenSSL 3 (not LibreSSL, the default on macOS).

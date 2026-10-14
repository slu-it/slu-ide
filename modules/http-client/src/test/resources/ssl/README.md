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

To regenerate all of them (the tests do not depend on particular keys), run `./generate.sh` in this folder; it needs
OpenSSL 3 (not LibreSSL, the default on macOS).

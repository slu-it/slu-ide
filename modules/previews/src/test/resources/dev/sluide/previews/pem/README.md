# PEM test fixtures

Generated with OpenSSL 3.5 for `PemRendererTest` and the PEM UI test only. No usable private key is checked in:
`PemRendererTest` generates its unencrypted and encrypted PKCS #8 keys when it runs, and the one key file here is
encrypted with a random passphrase that was thrown away.

| File | What |
|------|------|
| `test-only-self-signed.crt` | RSA 2048, `CN=sluide.test`, valid 2026-01-01 to 2036-01-01, SANs, key usage, `serverAuth` |
| `test-only-expired.pem` | EC P-256, `CN=expired.sluide.test`, valid 2020-01-01 to 2021-01-01 |
| `test-only-chain.pem` | leaf `CN=leaf.sluide.test` (serial `0x1234`) followed by its CA `CN=SluIde Test CA` (CA, path length 0) |
| `test-only-traditional-encrypted.key` | EC P-256 private key in OpenSSL's traditional format, `Proc-Type: 4,ENCRYPTED` |
| `test-only-public.pem` | an EC P-256 public key |
| `test-only-request.csr` | certificate request for `CN=leaf.sluide.test` |
| `test-only-markup-subject.pem` | EC P-256, `CN=<script>esc&ape`, for escaping |

Commands, e.g.:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -keyout rsa.key -out test-only-self-signed.crt \
  -subj "/CN=sluide.test/O=SluIde Test Fixtures" -not_before 20260101000000Z -not_after 20360101000000Z \
  -addext "subjectAltName=DNS:sluide.test,DNS:www.sluide.test,IP:127.0.0.1" \
  -addext "keyUsage=critical,digitalSignature,keyEncipherment" -addext "extendedKeyUsage=serverAuth" \
  -addext "basicConstraints=critical,CA:FALSE"
openssl rand -hex -out pass.txt 24
openssl ecparam -name prime256v1 -genkey -noout -out ec.key
openssl ec -in ec.key -aes128 -passout file:pass.txt -out test-only-traditional-encrypted.key
rm ec.key pass.txt
```

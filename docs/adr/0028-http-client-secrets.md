# 0028. HTTP client: tokens, certificates and other secrets

- Status: accepted
- Date: 2026-10-15

## Context
Requests need secrets: OAuth2 client secrets and passwords, the tokens obtained with them, client certificates with
passphrases. IntelliJ keeps secrets in `http-client.private.env.json`, which must not be committed, and acquires tokens
from `Security.Auth` configurations. SluIde has no secret store of its own and should not write secrets anywhere.

## Decision
- **Where secrets come from:** only the environment files. Private values win over public ones key by key, so a
  `Client Secret` can live in the private file under the same `Security.Auth.<id>` as the public configuration.
  `SSLConfiguration` is read from the private file only. Nothing is ever written to disk.
- **Private file not ignored:** when the window's folder is a git repository and git would commit the private file
  (it is in the repository and not ignored), the view shows a warning above the editor (`HttpClientSession.wouldCommit`,
  set by `MainWindow` from the git status, asked again whenever the git status changes). The git status is only
  followed when the window's folder is the repository root ([ADR 0013](0013-git-status.md)); in a subfolder of a
  repository, and without git, there is no warning.
- **OAuth2** (`OAuth2Client`, `OAuth2TokenCache`): Client Credentials and Password grants; client credentials sent as
  `basic` (default), `in body` or `none`; `Scope`; `Custom Request Parameters` (`In Token Request`, `Everywhere`; `In
  Auth Request` has no use without the Authorization Code grant); token requests time out after 30 s and use the
  environment's `SSLConfiguration`. Authorization Code, Device, Implicit, Kerberos/NTLM and AWS are not supported.
- **Tokens** live in memory per window, keyed by environment name and auth id, and are used while the resolved
  configuration stays equal: a changed secret, and dynamic variables in the configuration (which change on each use),
  lead to a new token. A token counts as valid until 10 s before it expires (forever without `expires_in`), then it is
  refreshed with its refresh token, or acquired anew if that fails or there is none. `Acquire Automatically: false`
  makes requests fail until the token is acquired from the Auth menu. Failed token requests are not cached; the next
  run tries again. `Use ID Token` (or `$auth.idToken`) sends the ID token. Clear Token forgets an id in all
  environments, Clear All Tokens all of them.
- **Masking:** the Request tab (the request as sent) masks the credentials of `Authorization` and
  `Proxy-Authorization` (the scheme stays), cookie values in `Cookie`, the values of headers named like keys, tokens
  and secrets (`*-Key`, `*-Token`, `*-Secret`, `ApiKey`), and every occurrence of the tokens that `$auth.token` and
  `$auth.idToken` put into the request (URL, headers, body). Response headers, including `Set-Cookie`, are shown as
  received; secrets written literally into a body or URL are shown.
- **Client certificates** (`SslContexts`): PEM (PKCS#8, encrypted PKCS#8, PKCS#1 RSA), PKCS#12 and DER. With
  `hasCertificatePassphrase`, the passphrase is asked for once per certificate and key file pair and window, in a
  dialog shown with `Platform.runLater` while the worker thread waits (the FX thread never waits for it); Cancel
  fails the request. A rejected passphrase is forgotten and asked for again next time. Passphrases are zeroed when the
  window closes; a changed certificate file makes a new context, whose old clients are stopped.
- **`verifyHostCertificate: false`** trusts every server certificate and skips host name verification for the
  requests and token requests of that environment only; other environments keep the JDK's checks.

## Consequences
- Tokens and passphrases are lost when the window closes, and never reach the disk or the log (`OAuth2Config.toString`
  leaves the secrets out).
- The git warning depends on the git status being current; it is re-checked whenever the environment files are read
  (each run, opening the environment list).
- Added to [../index.md](../index.md).

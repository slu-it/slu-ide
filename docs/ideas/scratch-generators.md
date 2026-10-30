# Idea: More scratch file generators

**Status:** idea, collected 2026-10-30 while building generated scratch files
([ADR 0114](../adr/0114-generated-scratch-files.md)); nothing decided or built. These were left out of the first
version on purpose; each needs a decision of its own before it is built.

## Test data
Values that look real but belong to nobody, for forms, fixtures and demo databases:

- Names, e-mail addresses, phone numbers, postal addresses.
- IBANs with valid check digits, Luhn-valid test card numbers.
- IPv4 and IPv6 addresses, MAC addresses, random integers, Lorem Ipsum.
- Whole person records as CSV or JSON, the fields consistent with each other.
- Entries per locale, at least German and English: name lists, address formats, phone number formats.
- Stay inside reserved, safe ranges so no generated value reaches a real person or host: `example.com` (and `.org`,
  `.net`), `192.0.2.0/24` and the other documentation ranges, `2001:db8::/32`, phone numbers `555-01xx` (US) and
  `07700 900xxx` (UK), the published test card numbers.
- Open: where the name lists come from (licence, size of the jar), and whether this is a generator group or a tool
  of its own with options.

## Key pairs and certificates
- RSA, EC and Ed25519 key pairs as PEM, OpenSSH keys, a self-signed `localhost` certificate.
- The JDK generates the keys, but writing a certificate (and OpenSSH's format) needs BouncyCastle or own DER code.
- Private keys would lie unencrypted in `~/.sluide/scratches`; a scratch file is easy to forget there.

## UUID v3 and v5
Name-based UUIDs need a namespace (DNS, URL, OID, X.500 or a custom UUID) and the names to hash: a small dialog
before the file is created, unlike every other generator.

## A count and format form
Choose how many values (instead of the fixed 50), the separator, quoting or a JSON array. A second step before every
use; the first version keeps the one-pick flow.

## Inserting at the caret
Generate one value (or the selection's count) into the editor at the caret instead of a new file, e.g. from the
editor's context menu. Needs a decision on keys, undo and which generators make sense for a single value.

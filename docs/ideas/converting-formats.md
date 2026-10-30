# Idea: More formats for the Converting tool

**Status:** idea, collected 2026-10-30 while planning the Converting tool
([ADR 0115](../adr/0115-converting-tool.md)); nothing decided or built. These were left out of the first version on
purpose; each needs a decision of its own before it is built.

## Data formats
- **XML** ↔ the data tree: lossy, attributes and mixed content need a convention (Jackson's: attributes as fields, text
  in an empty-named field; or `@attr` / `#text`). Handy for pom fragments, SOAP and config. Read with XXE off as the
  XML preview does ([ADR 0065](../adr/0065-xml-preview.md)).
- **`.env` files and environment variable names**: `KEY=value` lines, and Spring Boot's relaxed binding
  (`spring.datasource.url` ↔ `SPRING_DATASOURCE_URL`, lists as `_0_`) for Docker Compose and Kubernetes manifests.
- **INI**: sections and keys, close to TOML without types.
- **HCL**: Terraform variables to and from JSON; no Java library, an own reader would be needed.
- **JSON5** output, beside the JSONC-tolerant JSON reading.

## Other conversions
- **Query string** ↔ JSON object (`a=1&b=x` ↔ `{"a": "1", "b": "x"}`), repeated keys as arrays.
- **Markdown table** ↔ CSV.
- **JSON → code**: Kotlin data classes, JSON Schema from a sample. One-way and a family of its own.

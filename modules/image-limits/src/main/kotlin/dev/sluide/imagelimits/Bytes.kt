package dev.sluide.imagelimits

// Reads of the header bytes that never throw: a byte outside the data reads as -1, so a value read past the end is
// negative or garbage, and the callers check the length or the sign before they trust it.

internal fun u8(
    b: ByteArray,
    i: Int,
) = if (i in b.indices) b[i].toInt() and 0xFF else -1

internal fun be16(
    b: ByteArray,
    i: Int,
) = (u8(b, i) shl 8) or u8(b, i + 1)

internal fun be32(
    b: ByteArray,
    i: Int,
) = (be16(b, i) shl 16) or be16(b, i + 2)

internal fun le16(
    b: ByteArray,
    i: Int,
) = u8(b, i) or (u8(b, i + 1) shl 8)

internal fun le32(
    b: ByteArray,
    i: Int,
) = le16(b, i) or (le16(b, i + 2) shl 16)

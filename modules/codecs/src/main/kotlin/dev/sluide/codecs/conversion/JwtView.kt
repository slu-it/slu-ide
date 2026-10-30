package dev.sluide.codecs.conversion

import dev.sluide.codecs.codec.DecodedJwt
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A decoded token with the text the text side shows; [expired] is true when `exp` lies in the past. */
data class JwtView(
    val jwt: DecodedJwt,
    val summary: String,
    val expired: Boolean,
)

private val localFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_DAY = 86_400L

internal fun jwtView(
    jwt: DecodedJwt,
    clock: Clock,
): JwtView {
    val now = clock.instant()
    val expired = jwt.times.any { it.claim == "exp" && it.instant.isBefore(now) }
    val sb = StringBuilder()
    sb
        .append("Header\n")
        .append(jwt.header)
        .append("\n\nPayload\n")
        .append(jwt.payload)
        .append("\n\n")
    if (jwt.times.isNotEmpty()) {
        for (time in jwt.times) {
            val iso = time.instant.truncatedTo(ChronoUnit.SECONDS)
            val local = localFormat.format(iso.atZone(clock.zone))
            val relative = relativeTime(time.instant, now)
            val phrase = if (time.claim == "exp" && expired) "expired $relative" else relative
            sb.append("${time.claim}  $iso · $local local · $phrase\n")
        }
        sb.append('\n')
    }
    sb.append(signatureLine(jwt))
    return JwtView(jwt, sb.toString(), expired)
}

private fun signatureLine(jwt: DecodedJwt): String =
    when {
        !jwt.signed && jwt.algorithm.equals("none", ignoreCase = true) -> "Not signed (alg: none)"
        !jwt.signed -> "Not signed"
        jwt.algorithm != null -> "Signature (${jwt.algorithm}): not verified"
        else -> "Signature: not verified"
    }

/** "in 5 min" or "3 h ago", in the largest unit of s, min, h, days. */
internal fun relativeTime(
    instant: Instant,
    now: Instant,
): String {
    val seconds = Duration.between(now, instant).seconds
    val amount = abs(seconds)
    val text =
        when {
            amount < SECONDS_PER_MINUTE -> "$amount s"
            amount < SECONDS_PER_HOUR -> "${amount / SECONDS_PER_MINUTE} min"
            amount < SECONDS_PER_DAY -> "${amount / SECONDS_PER_HOUR} h"
            amount / SECONDS_PER_DAY == 1L -> "1 day"
            else -> "${amount / SECONDS_PER_DAY} days"
        }
    return if (seconds > 0) "in $text" else "$text ago"
}

/** "expired 3 h ago" for a [JwtView] whose `exp` is in the past, else null. */
internal fun expiredText(
    view: JwtView,
    clock: Clock,
): String? {
    if (!view.expired) return null
    val exp = view.jwt.times.first { it.claim == "exp" }
    return "expired ${relativeTime(exp.instant, clock.instant())}"
}

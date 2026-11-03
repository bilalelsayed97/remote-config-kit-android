package io.github.bilalelsayed97.remoteconfigkit

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** Parses ISO 8601 timestamps; values without a zone are local time, like Dart's `DateTime.parse`. */
internal fun parseInstant(text: String): Instant? {
    val parsers = listOf<(String) -> Instant>(
        { Instant.parse(it) },
        { OffsetDateTime.parse(it).toInstant() },
        { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant() },
        { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant() },
    )
    for (parser in parsers) {
        try {
            return parser(text)
        } catch (_: DateTimeParseException) {
        }
    }
    return null
}

/** UTC ISO 8601 text, the stored and compared form of every timestamp. */
internal fun formatInstant(instant: Instant): String = instant.truncatedTo(ChronoUnit.MILLIS).toString()

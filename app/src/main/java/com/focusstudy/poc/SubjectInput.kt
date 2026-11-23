package com.focusstudy.poc

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Pure subject-form rules: the exam date never becomes part of the subject name. */
internal object SubjectInput {

    fun nameForSave(raw: String): String = raw.trim()

    fun teacherForSave(raw: String): String = raw.trim()

    fun examMillis(date: LocalDate?, zone: ZoneId): Long? =
        date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

    fun examDate(millis: Long?, zone: ZoneId): LocalDate? =
        millis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    fun daysLeft(examAt: Long?, today: LocalDate, zone: ZoneId): Int? =
        examDate(examAt, zone)?.let { ChronoUnit.DAYS.between(today, it).toInt() }

    fun daysLeftText(days: Int): String = when {
        days < 0 -> "انقضى الموعد"
        days == 0 -> "الامتحان اليوم"
        days == 1 -> "باقي يوم واحد"
        days == 2 -> "باقي يومان"
        days in 3..10 -> "باقي $days أيام"
        else -> "باقي $days يومًا"
    }
}

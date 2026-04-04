package com.sleepcare.mobile.ui.components

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN)
private val dateFormatter = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
private val dateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN)

fun LocalTime.toDisplayTime(): String = format(timeFormatter)

fun LocalDate.toDisplayDate(): String = format(dateFormatter)

fun LocalDateTime.toDisplayDateTime(): String = format(dateTimeFormatter)

fun Int.toDurationText(): String {
    val hours = this / 60
    val minutes = this % 60
    return "${hours}시간 ${minutes}분"
}

fun DayOfWeek.toKoreanShort(): String = when (this) {
    DayOfWeek.MONDAY -> "월"
    DayOfWeek.TUESDAY -> "화"
    DayOfWeek.WEDNESDAY -> "수"
    DayOfWeek.THURSDAY -> "목"
    DayOfWeek.FRIDAY -> "금"
    DayOfWeek.SATURDAY -> "토"
    DayOfWeek.SUNDAY -> "일"
}

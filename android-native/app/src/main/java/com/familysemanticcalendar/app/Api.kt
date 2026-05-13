package com.familysemanticcalendar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

const val API_BASE_URL = "https://desktop-lnu5cl7.tail96fe59.ts.net"

data class User(val id: String, val displayName: String)
data class CalendarItem(val id: String, val name: String, val inviteCode: String)
data class EventItem(
    val id: String,
    val calendarId: String,
    val createdBy: String?,
    val title: String,
    val body: String,
    val location: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime?,
    val recurrenceRule: JSONObject? = null,
    val similarity: Double? = null,
)

const val ALL_OWNER_ID = "__all__"

object NativeStore {
    private const val PREFS = "family-calendar-native"
    private const val USER_ID = "user_id"
    private const val DISPLAY_NAME = "display_name"
    private const val SEARCH_MAX_DISTANCE = "search_max_distance"

    fun saveUser(context: Context, user: User) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(USER_ID, user.id)
            .putString(DISPLAY_NAME, user.displayName)
            .apply()
    }

    fun savedUser(context: Context): User? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(USER_ID, null) ?: return null
        val name = prefs.getString(DISPLAY_NAME, null) ?: return null
        return User(id, name)
    }

    fun searchMaxDistance(context: Context): Double {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SEARCH_MAX_DISTANCE, "0.2")
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 2.0) ?: 0.2
    }

    fun saveSearchMaxDistance(context: Context, value: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SEARCH_MAX_DISTANCE, value.coerceIn(0.0, 2.0).toString())
            .apply()
    }
}

object CalendarApi {
    fun listSignInUsers(): List<User> {
        val array = JSONArray(request("GET", "/auth/users"))
        return (0 until array.length()).map { array.getJSONObject(it).toUser() }
    }

    fun signIn(displayName: String): User {
        val body = JSONObject().put("display_name", displayName).toString()
        return JSONObject(request("POST", "/auth/sign-in", body)).toUser()
    }

    fun listCalendars(userId: String): List<CalendarItem> {
        val array = JSONArray(request("GET", "/calendars", userId = userId))
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            CalendarItem(
                id = item.getString("id"),
                name = item.getString("name"),
                inviteCode = item.getString("invite_code"),
            )
        }
    }

    fun createCalendar(name: String, userId: String): CalendarItem {
        val body = JSONObject()
            .put("name", name)
            .put("owner_user_id", userId)
            .toString()
        val item = JSONObject(request("POST", "/calendars", body, userId))
        return CalendarItem(
            id = item.getString("id"),
            name = item.getString("name"),
            inviteCode = item.getString("invite_code"),
        )
    }

    fun joinCalendar(inviteCode: String, userId: String): CalendarItem {
        val body = JSONObject()
            .put("invite_code", inviteCode)
            .put("user_id", userId)
            .toString()
        val item = JSONObject(request("POST", "/calendars/join", body, userId))
        return CalendarItem(
            id = item.getString("id"),
            name = item.getString("name"),
            inviteCode = item.getString("invite_code"),
        )
    }

    fun listUsers(userId: String): List<User> {
        val array = JSONArray(request("GET", "/users", userId = userId))
        return (0 until array.length()).map { array.getJSONObject(it).toUser() }
    }

    fun listEvents(calendarId: String, userId: String): List<EventItem> {
        val encodedCalendarId = URLEncoder.encode(calendarId, Charsets.UTF_8.name())
        val array = JSONArray(request("GET", "/events?calendar_id=$encodedCalendarId", userId = userId))
        return (0 until array.length()).map { array.getJSONObject(it).toEvent() }
    }

    fun createEvent(
        calendarId: String,
        userId: String,
        title: String,
        bodyText: String,
        location: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime?,
        ownerId: String?,
        recurrenceRule: JSONObject? = null,
    ): EventItem {
        val body = eventPayload(calendarId, ownerId, title, bodyText, location, startsAt, endsAt, recurrenceRule).toString()
        return JSONObject(request("POST", "/events", body, userId)).toEvent()
    }

    fun updateEvent(
        eventId: String,
        userId: String,
        title: String,
        bodyText: String,
        location: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime?,
        ownerId: String?,
        recurrenceRule: JSONObject? = null,
    ): EventItem {
        val body = eventPayload(null, ownerId, title, bodyText, location, startsAt, endsAt, recurrenceRule).toString()
        val encodedEventId = URLEncoder.encode(eventId, Charsets.UTF_8.name())
        return JSONObject(request("PUT", "/events/$encodedEventId", body, userId)).toEvent()
    }

    fun deleteEvent(eventId: String, userId: String) {
        val encodedEventId = URLEncoder.encode(eventId, Charsets.UTF_8.name())
        request("DELETE", "/events/$encodedEventId", userId = userId)
    }

    fun searchEvents(calendarIds: List<String>, query: String, userId: String, maxDistance: Double = 0.2): List<EventItem> {
        val body = JSONObject()
            .put("calendar_ids", JSONArray(calendarIds))
            .put("query", query)
            .put("limit", 20)
            .put("max_distance", maxDistance)
            .toString()
        val array = JSONArray(request("POST", "/events/search", body, userId))
        return (0 until array.length()).map { array.getJSONObject(it).toEvent() }
    }

    private fun JSONObject.toUser() = User(
        id = getString("id"),
        displayName = getString("display_name"),
    )

    private fun JSONObject.toEvent() = EventItem(
        id = getString("id"),
        calendarId = getString("calendar_id"),
        createdBy = optString("created_by").takeIf { it.isNotBlank() && it != "null" },
        title = getString("title"),
        body = optString("body"),
        location = optString("location"),
        startsAt = parseDateTime(getString("starts_at")),
        endsAt = optString("ends_at").takeIf { it.isNotBlank() && it != "null" }?.let { parseDateTime(it) },
        recurrenceRule = optJSONObject("recurrence_rule"),
        similarity = if (has("similarity")) optDouble("similarity") else null,
    )

    private fun eventPayload(
        calendarId: String?,
        ownerId: String?,
        title: String,
        bodyText: String,
        location: String,
        startsAt: LocalDateTime,
        endsAt: LocalDateTime?,
        recurrenceRule: JSONObject?,
    ): JSONObject {
        val payload = JSONObject()
            .put("title", title)
            .put("body", bodyText)
            .put("location", location)
            .put("starts_at", startsAt.toString())
        if (ownerId == null) payload.put("created_by", JSONObject.NULL) else payload.put("created_by", ownerId)
        if (calendarId != null) payload.put("calendar_id", calendarId)
        if (endsAt != null) payload.put("ends_at", endsAt.toString())
        if (recurrenceRule != null) payload.put("recurrence_rule", recurrenceRule)
        return payload
    }

    private fun parseDateTime(value: String): LocalDateTime {
        return try {
            Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            } catch (_: Exception) {
                LocalDateTime.parse(value)
            }
        }
    }

    private fun request(method: String, path: String, body: String? = null, userId: String? = null): String {
        val connection = (URL("$API_BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            if (userId != null) setRequestProperty("X-User-Id", userId)
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }

        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (connection.responseCode !in 200..299) {
            val detail = try { JSONObject(text).optString("detail", text) } catch (_: Exception) { text }
            throw IllegalStateException(detail.ifBlank { "HTTP ${connection.responseCode}" })
        }
        return text
    }
}

fun eventsForDate(events: List<EventItem>, date: LocalDate): List<EventItem> {
    return events.filter { it.occursOn(date) }.sortedBy { it.startsAt.toLocalTime() }
}

fun ownerName(members: List<User>, ownerId: String?): String {
    if (ownerId == null || ownerId == ALL_OWNER_ID) return "모두"
    return members.find { it.id == ownerId }?.displayName ?: "알 수 없음"
}

fun holidayName(date: LocalDate): String? {
    return when ("%02d-%02d".format(date.monthValue, date.dayOfMonth)) {
        "01-01" -> "신정"
        "03-01" -> "삼일절"
        "05-01" -> "노동절"
        "05-05" -> "어린이날"
        "06-06" -> "현충일"
        "08-15" -> "광복절"
        "10-03" -> "개천절"
        "10-09" -> "한글날"
        "12-25" -> "성탄절"
        else -> null
    }
}

private fun EventItem.occursOn(date: LocalDate): Boolean {
    val startDate = startsAt.toLocalDate()
    val endDate = endsAt?.toLocalDate()
    if (recurrenceRule == null) {
        return if (endDate != null) !date.isBefore(startDate) && !date.isAfter(endDate) else date == startDate
    }
    if (date.isBefore(startDate)) return false
    val interval = recurrenceRule.optInt("interval", 1).coerceAtLeast(1)
    return when (recurrenceRule.optString("frequency")) {
        "daily" -> java.time.temporal.ChronoUnit.DAYS.between(startDate, date) % interval == 0L
        "weekly" -> {
            val weekday = if (date.dayOfWeek.value == 7) 0 else date.dayOfWeek.value
            val weekdays = recurrenceRule.optJSONArray("weekdays")
            val weekdayMatches = weekdays == null || (0 until weekdays.length()).any { weekdays.optInt(it) == weekday }
            java.time.temporal.ChronoUnit.WEEKS.between(startDate, date) % interval == 0L && weekdayMatches
        }
        "monthly" -> {
            val monthDay = recurrenceRule.optInt("monthDay", startDate.dayOfMonth)
            java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(startDate), YearMonth.from(date)) % interval == 0L && date.dayOfMonth == monthDay
        }
        "yearly" -> date.monthValue == startDate.monthValue && date.dayOfMonth == startDate.dayOfMonth
        else -> false
    }
}

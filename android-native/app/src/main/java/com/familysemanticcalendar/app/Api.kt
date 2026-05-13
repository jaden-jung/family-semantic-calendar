package com.familysemanticcalendar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

const val API_BASE_URL = "https://desktop-lnu5cl7.tail96fe59.ts.net"

data class User(val id: String, val displayName: String)
data class CalendarItem(val id: String, val name: String, val inviteCode: String)
data class EventItem(
    val id: String,
    val calendarId: String,
    val title: String,
    val body: String,
    val location: String,
    val startsAt: LocalDateTime,
)

object NativeStore {
    private const val PREFS = "family-calendar-native"
    private const val USER_ID = "user_id"
    private const val DISPLAY_NAME = "display_name"

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

    fun listEvents(calendarId: String, userId: String): List<EventItem> {
        val array = JSONArray(request("GET", "/events?calendar_id=$calendarId", userId = userId))
        return (0 until array.length()).map { array.getJSONObject(it).toEvent() }
    }

    private fun JSONObject.toUser() = User(
        id = getString("id"),
        displayName = getString("display_name"),
    )

    private fun JSONObject.toEvent() = EventItem(
        id = getString("id"),
        calendarId = getString("calendar_id"),
        title = getString("title"),
        body = optString("body"),
        location = optString("location"),
        startsAt = parseDateTime(getString("starts_at")),
    )

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
    return events.filter { it.startsAt.toLocalDate() == date }.sortedBy { it.startsAt }
}

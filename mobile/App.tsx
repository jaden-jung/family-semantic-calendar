import { StatusBar } from "expo-status-bar";
import { useEffect, useMemo, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { Feather } from "@expo/vector-icons";

import { ApiClient, Calendar, EventItem, SpendingRow } from "./src/api";

const api = new ApiClient(process.env.EXPO_PUBLIC_API_BASE_URL || "http://localhost:8000");

export default function App() {
  const [userId, setUserId] = useState("");
  const [displayName, setDisplayName] = useState("가족");
  const [calendarName, setCalendarName] = useState("우리집 달력");
  const [inviteCode, setInviteCode] = useState("");
  const [calendars, setCalendars] = useState<Calendar[]>([]);
  const [selectedCalendarId, setSelectedCalendarId] = useState("");
  const [events, setEvents] = useState<EventItem[]>([]);
  const [report, setReport] = useState<SpendingRow[]>([]);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);

  const selectedCalendar = useMemo(
    () => calendars.find((calendar) => calendar.id === selectedCalendarId),
    [calendars, selectedCalendarId],
  );

  useEffect(() => {
    if (userId) {
      void refreshCalendars();
    }
  }, [userId]);

  useEffect(() => {
    if (selectedCalendarId && userId) {
      void refreshEvents(selectedCalendarId);
      void refreshReport(selectedCalendarId);
    }
  }, [selectedCalendarId, userId]);

  async function withLoading(work: () => Promise<void>) {
    setLoading(true);
    try {
      await work();
    } catch (error) {
      Alert.alert("요청 실패", error instanceof Error ? error.message : "알 수 없는 오류");
    } finally {
      setLoading(false);
    }
  }

  async function createUser() {
    await withLoading(async () => {
      const user = await api.createUser(displayName);
      setUserId(user.id);
    });
  }

  async function createCalendar() {
    if (!userId) return;
    await withLoading(async () => {
      const calendar = await api.createCalendar(calendarName, userId);
      setCalendars((current) => [calendar, ...current]);
      setSelectedCalendarId(calendar.id);
    });
  }

  async function joinCalendar() {
    if (!userId || !inviteCode) return;
    await withLoading(async () => {
      const calendar = await api.joinCalendar(inviteCode, userId);
      setCalendars((current) => [calendar, ...current.filter((item) => item.id !== calendar.id)]);
      setSelectedCalendarId(calendar.id);
    });
  }

  async function refreshCalendars() {
    if (!userId) return;
    const items = await api.listCalendars(userId);
    setCalendars(items);
    setSelectedCalendarId((current) => current || items[0]?.id || "");
  }

  async function refreshEvents(calendarId: string) {
    const items = await api.listEvents(calendarId, userId);
    setEvents(items);
  }

  async function refreshReport(calendarId: string) {
    const rows = await api.spendingReport(calendarId, userId);
    setReport(rows);
  }

  async function createEvent() {
    if (!selectedCalendarId || !userId || !title) return;
    await withLoading(async () => {
      await api.createEvent({
        calendar_id: selectedCalendarId,
        created_by: userId,
        title,
        body,
        starts_at: new Date().toISOString(),
      });
      setTitle("");
      setBody("");
      await refreshEvents(selectedCalendarId);
    });
  }

  async function searchEvents() {
    if (!selectedCalendarId || !query) return;
    await withLoading(async () => {
      const items = await api.searchEvents(selectedCalendarId, query, userId);
      setEvents(items);
    });
  }

  async function addSamplePayment() {
    if (!selectedCalendarId || !userId) return;
    await withLoading(async () => {
      await api.ingestPaymentSms({
        calendar_id: selectedCalendarId,
        created_by: userId,
        raw_text: "[카드승인] 12,500원 스타벅스 강남점",
        received_at: new Date().toISOString(),
      });
      await refreshEvents(selectedCalendarId);
      await refreshReport(selectedCalendarId);
    });
  }

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar style="dark" />
      <ScrollView contentContainerStyle={styles.container}>
        <View style={styles.header}>
          <Feather name="calendar" size={28} color="#1d4ed8" />
          <View>
            <Text style={styles.title}>Family Calendar</Text>
            <Text style={styles.subtitle}>공유 일정, 의미 검색, 카드내역 집계</Text>
          </View>
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>시작</Text>
          <TextInput value={displayName} onChangeText={setDisplayName} placeholder="이름" style={styles.input} />
          <Pressable style={styles.primaryButton} onPress={createUser}>
            <Feather name="users" size={18} color="#fff" />
            <Text style={styles.primaryButtonText}>{userId ? "사용자 생성됨" : "사용자 만들기"}</Text>
          </Pressable>
          {userId ? <Text style={styles.meta}>User ID: {userId}</Text> : null}
        </View>

        <View style={styles.section}>
          <Text style={styles.sectionTitle}>캘린더</Text>
          <TextInput value={calendarName} onChangeText={setCalendarName} placeholder="캘린더 이름" style={styles.input} />
          <Pressable style={styles.secondaryButton} onPress={createCalendar}>
            <Feather name="plus" size={18} color="#0f172a" />
            <Text style={styles.secondaryButtonText}>캘린더 만들기</Text>
          </Pressable>
          <TextInput value={inviteCode} onChangeText={setInviteCode} placeholder="초대 코드" style={styles.input} />
          <Pressable style={styles.secondaryButton} onPress={joinCalendar}>
            <Feather name="users" size={18} color="#0f172a" />
            <Text style={styles.secondaryButtonText}>초대 코드로 참여</Text>
          </Pressable>
          {calendars.map((calendar) => (
            <Pressable
              key={calendar.id}
              style={[styles.calendarRow, calendar.id === selectedCalendarId && styles.calendarRowActive]}
              onPress={() => setSelectedCalendarId(calendar.id)}
            >
              <Text style={styles.calendarName}>{calendar.name}</Text>
              <Text style={styles.meta}>초대 코드 {calendar.invite_code}</Text>
            </Pressable>
          ))}
        </View>

        {selectedCalendar ? (
          <>
            <View style={styles.section}>
              <Text style={styles.sectionTitle}>일정 등록</Text>
              <TextInput value={title} onChangeText={setTitle} placeholder="제목" style={styles.input} />
              <TextInput
                value={body}
                onChangeText={setBody}
                placeholder="내용"
                style={[styles.input, styles.textArea]}
                multiline
              />
              <Pressable style={styles.primaryButton} onPress={createEvent}>
                <Feather name="plus" size={18} color="#fff" />
                <Text style={styles.primaryButtonText}>등록</Text>
              </Pressable>
            </View>

            <View style={styles.section}>
              <Text style={styles.sectionTitle}>의미 검색</Text>
              <TextInput value={query} onChangeText={setQuery} placeholder="예: 지난번 병원 결제" style={styles.input} />
              <Pressable style={styles.secondaryButton} onPress={searchEvents}>
                <Feather name="search" size={18} color="#0f172a" />
                <Text style={styles.secondaryButtonText}>검색</Text>
              </Pressable>
            </View>

            <View style={styles.section}>
              <Text style={styles.sectionTitle}>카드 SMS</Text>
              <Pressable style={styles.secondaryButton} onPress={addSamplePayment}>
                <Feather name="credit-card" size={18} color="#0f172a" />
                <Text style={styles.secondaryButtonText}>샘플 결제문자 등록</Text>
              </Pressable>
              {report.map((row) => (
                <Text key={`${row.month}-${row.category}`} style={styles.reportRow}>
                  {row.month} · {row.category || "기타"} · {Number(row.total_amount).toLocaleString()}원
                </Text>
              ))}
            </View>

            <View style={styles.section}>
              <Text style={styles.sectionTitle}>최근 항목</Text>
              {events.map((event) => (
                <View key={event.id} style={styles.eventRow}>
                  <Text style={styles.eventTitle}>{event.title}</Text>
                  <Text style={styles.meta}>{new Date(event.starts_at).toLocaleString()}</Text>
                  {event.body ? <Text style={styles.eventBody}>{event.body}</Text> : null}
                </View>
              ))}
            </View>
          </>
        ) : null}

        {loading ? (
          <View style={styles.loading}>
            <ActivityIndicator />
          </View>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: "#f8fafc",
  },
  container: {
    padding: 20,
    gap: 16,
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    paddingVertical: 8,
  },
  title: {
    fontSize: 24,
    fontWeight: "700",
    color: "#0f172a",
  },
  subtitle: {
    fontSize: 13,
    color: "#64748b",
    marginTop: 2,
  },
  section: {
    backgroundColor: "#fff",
    borderRadius: 8,
    borderWidth: 1,
    borderColor: "#e2e8f0",
    padding: 14,
    gap: 10,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: "700",
    color: "#0f172a",
  },
  input: {
    minHeight: 44,
    borderWidth: 1,
    borderColor: "#cbd5e1",
    borderRadius: 6,
    paddingHorizontal: 12,
    paddingVertical: 10,
    color: "#0f172a",
    backgroundColor: "#fff",
  },
  textArea: {
    minHeight: 86,
    textAlignVertical: "top",
  },
  primaryButton: {
    minHeight: 44,
    borderRadius: 6,
    backgroundColor: "#1d4ed8",
    alignItems: "center",
    justifyContent: "center",
    flexDirection: "row",
    gap: 8,
  },
  primaryButtonText: {
    color: "#fff",
    fontWeight: "700",
  },
  secondaryButton: {
    minHeight: 44,
    borderRadius: 6,
    backgroundColor: "#e2e8f0",
    alignItems: "center",
    justifyContent: "center",
    flexDirection: "row",
    gap: 8,
  },
  secondaryButtonText: {
    color: "#0f172a",
    fontWeight: "700",
  },
  calendarRow: {
    borderWidth: 1,
    borderColor: "#e2e8f0",
    borderRadius: 6,
    padding: 10,
  },
  calendarRowActive: {
    borderColor: "#1d4ed8",
    backgroundColor: "#eff6ff",
  },
  calendarName: {
    color: "#0f172a",
    fontWeight: "700",
  },
  meta: {
    color: "#64748b",
    fontSize: 12,
  },
  eventRow: {
    borderTopWidth: 1,
    borderTopColor: "#e2e8f0",
    paddingTop: 10,
    gap: 4,
  },
  eventTitle: {
    color: "#0f172a",
    fontSize: 15,
    fontWeight: "700",
  },
  eventBody: {
    color: "#334155",
    lineHeight: 20,
  },
  reportRow: {
    color: "#334155",
  },
  loading: {
    paddingVertical: 12,
  },
});

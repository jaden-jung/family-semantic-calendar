import { Feather } from "@expo/vector-icons";
import { StatusBar } from "expo-status-bar";
import { useEffect, useMemo, useState } from "react";
import {
  ActivityIndicator,
  Alert,
  Modal,
  PanResponder,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import { ApiClient, Calendar, EventItem, User } from "./src/api";

const api = new ApiClient(process.env.EXPO_PUBLIC_API_BASE_URL || "http://localhost:8000");
const dayLabels = ["일", "월", "화", "수", "목", "금", "토"];

type ViewMode = "month" | "week";
type EventForm = {
  title: string;
  body: string;
  location: string;
  date: string;
  time: string;
  ownerId: string;
};

function pad(value: number) {
  return String(value).padStart(2, "0");
}

function toDateKey(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function monthLabel(date: Date) {
  return `${date.getFullYear()}년 ${date.getMonth() + 1}월`;
}

function startOfMonthGrid(date: Date) {
  const first = new Date(date.getFullYear(), date.getMonth(), 1);
  first.setDate(first.getDate() - first.getDay());
  first.setHours(0, 0, 0, 0);
  return first;
}

function addDays(date: Date, amount: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + amount);
  return next;
}

function addMonths(date: Date, amount: number) {
  return new Date(date.getFullYear(), date.getMonth() + amount, 1);
}

function dateFromKey(key: string) {
  const [year, month, day] = key.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function combineDateTime(date: string, time: string) {
  const safeTime = time.trim();
  return new Date(`${date}T${safeTime || "00:00"}:00`).toISOString();
}

function timeFromIso(value: string) {
  const date = new Date(value);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export default function App() {
  const [userId, setUserId] = useState("");
  const [displayName, setDisplayName] = useState("가족");
  const [calendarName, setCalendarName] = useState("우리집 달력");
  const [inviteCode, setInviteCode] = useState("");
  const [calendars, setCalendars] = useState<Calendar[]>([]);
  const [members, setMembers] = useState<User[]>([]);
  const [selectedCalendarId, setSelectedCalendarId] = useState("");
  const [events, setEvents] = useState<EventItem[]>([]);
  const [visibleDate, setVisibleDate] = useState(() => new Date());
  const [selectedDateKey, setSelectedDateKey] = useState(() => toDateKey(new Date()));
  const [viewMode, setViewMode] = useState<ViewMode>("month");
  const [formOpen, setFormOpen] = useState(false);
  const [editingEvent, setEditingEvent] = useState<EventItem | null>(null);
  const [form, setForm] = useState<EventForm>({
    title: "",
    body: "",
    location: "",
    date: toDateKey(new Date()),
    time: "",
    ownerId: "",
  });
  const [loading, setLoading] = useState(false);

  const selectedCalendar = useMemo(
    () => calendars.find((calendar) => calendar.id === selectedCalendarId),
    [calendars, selectedCalendarId],
  );

  const eventsByDate = useMemo(() => {
    const map = new Map<string, EventItem[]>();
    events.forEach((event) => {
      const key = toDateKey(new Date(event.starts_at));
      map.set(key, [...(map.get(key) || []), event]);
    });
    return map;
  }, [events]);

  const selectedEvents = eventsByDate.get(selectedDateKey) || [];

  const calendarDays = useMemo(() => {
    if (viewMode === "week") {
      const selected = dateFromKey(selectedDateKey);
      const weekStart = addDays(selected, -selected.getDay());
      return Array.from({ length: 7 }, (_, index) => addDays(weekStart, index));
    }
    const gridStart = startOfMonthGrid(visibleDate);
    return Array.from({ length: 42 }, (_, index) => addDays(gridStart, index));
  }, [selectedDateKey, viewMode, visibleDate]);

  const swipeResponder = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_, gesture) => Math.abs(gesture.dx) > 24 && Math.abs(gesture.dy) < 18,
        onPanResponderRelease: (_, gesture) => {
          if (gesture.dx < -45) movePeriod(1);
          if (gesture.dx > 45) movePeriod(-1);
        },
      }),
    [selectedDateKey, viewMode, visibleDate],
  );

  useEffect(() => {
    if (userId) {
      void refreshCalendars();
    }
  }, [userId]);

  useEffect(() => {
    if (selectedCalendarId && userId) {
      void refreshEvents(selectedCalendarId);
      void refreshMembers(selectedCalendarId);
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
      const user = await api.createUser(displayName.trim() || "가족");
      setUserId(user.id);
      const calendar = await api.createCalendar(calendarName.trim() || "우리집 달력", user.id);
      setCalendars([calendar]);
      setSelectedCalendarId(calendar.id);
      setMembers([user]);
    });
  }

  async function joinCalendar() {
    if (!userId || !inviteCode.trim()) return;
    await withLoading(async () => {
      const calendar = await api.joinCalendar(inviteCode.trim(), userId);
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

  async function refreshMembers(calendarId: string) {
    const items = await api.listCalendarMembers(calendarId, userId);
    setMembers(items);
  }

  async function refreshEvents(calendarId: string) {
    const items = await api.listEvents(calendarId, userId);
    setEvents(items);
  }

  function movePeriod(direction: number) {
    if (viewMode === "week") {
      const next = addDays(dateFromKey(selectedDateKey), direction * 7);
      setSelectedDateKey(toDateKey(next));
      setVisibleDate(new Date(next.getFullYear(), next.getMonth(), 1));
      return;
    }
    const next = addMonths(visibleDate, direction);
    setVisibleDate(next);
    setSelectedDateKey(toDateKey(new Date(next.getFullYear(), next.getMonth(), 1)));
  }

  function openCreateForm(dateKey = selectedDateKey) {
    setEditingEvent(null);
    setForm({
      title: "",
      body: "",
      location: "",
      date: dateKey,
      time: "",
      ownerId: userId,
    });
    setFormOpen(true);
  }

  function openEditForm(event: EventItem) {
    setEditingEvent(event);
    setForm({
      title: event.title,
      body: event.body,
      location: event.location || "",
      date: toDateKey(new Date(event.starts_at)),
      time: event.ends_at ? timeFromIso(event.starts_at) : "",
      ownerId: event.created_by || userId,
    });
    setFormOpen(true);
  }

  async function saveEvent() {
    if (!selectedCalendarId || !form.title.trim() || !form.ownerId.trim()) return;
    await withLoading(async () => {
      const payload = {
        created_by: form.ownerId.trim(),
        title: form.title.trim(),
        body: form.body.trim(),
        location: form.location.trim(),
        starts_at: combineDateTime(form.date, form.time),
        ends_at: form.time.trim() ? combineDateTime(form.date, form.time) : null,
      };
      if (editingEvent) {
        await api.updateEvent(editingEvent.id, payload, userId);
      } else {
        await api.createEvent({
          calendar_id: selectedCalendarId,
          ...payload,
        });
      }
      setSelectedDateKey(form.date);
      setVisibleDate(new Date(dateFromKey(form.date).getFullYear(), dateFromKey(form.date).getMonth(), 1));
      setFormOpen(false);
      await refreshEvents(selectedCalendarId);
    });
  }

  async function deleteSelectedEvent(event: EventItem) {
    await withLoading(async () => {
      await api.deleteEvent(event.id, userId);
      await refreshEvents(selectedCalendarId);
    });
  }

  if (!userId) {
    return (
      <SafeAreaView style={styles.safe}>
        <StatusBar style="dark" />
        <View style={styles.setup}>
          <View style={styles.brandRow}>
            <Feather name="calendar" size={30} color="#0f766e" />
            <Text style={styles.appTitle}>Family Calendar</Text>
          </View>
          <TextInput value={displayName} onChangeText={setDisplayName} placeholder="사용자 이름" style={styles.input} />
          <TextInput value={calendarName} onChangeText={setCalendarName} placeholder="처음 만들 달력 이름" style={styles.input} />
          <Pressable style={styles.primaryButton} onPress={createUser}>
            <Feather name="user-plus" size={18} color="#fff" />
            <Text style={styles.primaryButtonText}>사용자 생성</Text>
          </Pressable>
          {loading ? <ActivityIndicator /> : null}
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar style="dark" />
      <View style={styles.screen}>
        <View style={styles.topBar}>
          <View>
            <Text style={styles.appTitle}>Family Calendar</Text>
            <Text style={styles.muted}>{selectedCalendar?.name || "달력 없음"}</Text>
          </View>
          <Pressable style={styles.iconButton} onPress={() => openCreateForm()}>
            <Feather name="plus" size={22} color="#fff" />
          </Pressable>
        </View>

        <View style={styles.shareRow}>
          <Text style={styles.muted}>초대 코드 {selectedCalendar?.invite_code || "-"}</Text>
          <TextInput value={inviteCode} onChangeText={setInviteCode} placeholder="초대 코드" style={styles.inviteInput} />
          <Pressable style={styles.smallButton} onPress={joinCalendar}>
            <Text style={styles.smallButtonText}>참여</Text>
          </Pressable>
        </View>

        <View style={styles.monthBar}>
          <Pressable style={styles.navButton} onPress={() => movePeriod(-1)}>
            <Feather name="chevron-left" size={22} color="#0f172a" />
          </Pressable>
          <Text style={styles.monthTitle}>{viewMode === "week" ? `${selectedDateKey} 주` : monthLabel(visibleDate)}</Text>
          <Pressable style={styles.navButton} onPress={() => movePeriod(1)}>
            <Feather name="chevron-right" size={22} color="#0f172a" />
          </Pressable>
          <View style={styles.segment}>
            <Pressable style={[styles.segmentButton, viewMode === "month" && styles.segmentActive]} onPress={() => setViewMode("month")}>
              <Text style={[styles.segmentText, viewMode === "month" && styles.segmentTextActive]}>월</Text>
            </Pressable>
            <Pressable style={[styles.segmentButton, viewMode === "week" && styles.segmentActive]} onPress={() => setViewMode("week")}>
              <Text style={[styles.segmentText, viewMode === "week" && styles.segmentTextActive]}>주</Text>
            </Pressable>
          </View>
        </View>

        <View style={styles.calendarWrap} {...swipeResponder.panHandlers}>
          <View style={styles.weekHeader}>
            {dayLabels.map((label) => (
              <Text key={label} style={styles.weekLabel}>
                {label}
              </Text>
            ))}
          </View>
          <View style={[styles.grid, viewMode === "week" && styles.weekGrid]}>
            {calendarDays.map((date) => {
              const key = toDateKey(date);
              const dayEvents = eventsByDate.get(key) || [];
              const isCurrentMonth = date.getMonth() === visibleDate.getMonth();
              const isSelected = key === selectedDateKey;
              return (
                <Pressable
                  key={key}
                  style={[
                    styles.dayCell,
                    viewMode === "week" && styles.weekCell,
                    !isCurrentMonth && viewMode === "month" && styles.outsideCell,
                    isSelected && styles.selectedCell,
                  ]}
                  onPress={() => {
                    setSelectedDateKey(key);
                    setVisibleDate(new Date(date.getFullYear(), date.getMonth(), 1));
                  }}
                >
                  <Text style={[styles.dayNumber, !isCurrentMonth && viewMode === "month" && styles.outsideText]}>{date.getDate()}</Text>
                  {dayEvents.slice(0, viewMode === "week" ? 4 : 2).map((event) => (
                    <Text key={event.id} numberOfLines={1} style={styles.eventChip}>
                      {event.title}
                    </Text>
                  ))}
                  {dayEvents.length > (viewMode === "week" ? 4 : 2) ? <Text style={styles.moreText}>+{dayEvents.length - 2}</Text> : null}
                </Pressable>
              );
            })}
          </View>
        </View>

        <View style={styles.listHeader}>
          <Text style={styles.sectionTitle}>{selectedDateKey} 일정</Text>
          <Pressable style={styles.textButton} onPress={() => openCreateForm(selectedDateKey)}>
            <Text style={styles.textButtonLabel}>추가</Text>
          </Pressable>
        </View>

        <ScrollView style={styles.eventList} contentContainerStyle={styles.eventListContent}>
          {selectedEvents.length === 0 ? <Text style={styles.emptyText}>등록된 일정이 없습니다.</Text> : null}
          {selectedEvents.map((event) => (
            <View key={event.id} style={styles.eventRow}>
              <Pressable style={styles.eventMain} onPress={() => openEditForm(event)}>
                <Text style={styles.eventTitle}>{event.title}</Text>
                <Text style={styles.muted}>
                  {timeFromIso(event.starts_at)} {event.location ? `· ${event.location}` : ""}
                </Text>
                {event.body ? <Text style={styles.eventBody}>{event.body}</Text> : null}
              </Pressable>
              <Pressable style={styles.deleteButton} onPress={() => deleteSelectedEvent(event)}>
                <Feather name="trash-2" size={18} color="#b91c1c" />
              </Pressable>
            </View>
          ))}
        </ScrollView>
      </View>

      <Modal visible={formOpen} animationType="slide" transparent>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalPanel}>
            <View style={styles.modalHeader}>
              <Text style={styles.sectionTitle}>{editingEvent ? "일정 수정" : "일정 추가"}</Text>
              <Pressable style={styles.navButton} onPress={() => setFormOpen(false)}>
                <Feather name="x" size={22} color="#0f172a" />
              </Pressable>
            </View>
            <TextInput value={form.date} onChangeText={(date) => setForm((current) => ({ ...current, date }))} placeholder="날짜 YYYY-MM-DD" style={styles.input} />
            <TextInput value={form.time} onChangeText={(time) => setForm((current) => ({ ...current, time }))} placeholder="시간 HH:MM (선택)" style={styles.input} />
            <TextInput value={form.title} onChangeText={(title) => setForm((current) => ({ ...current, title }))} placeholder="제목" style={styles.input} />
            <TextInput value={form.body} onChangeText={(body) => setForm((current) => ({ ...current, body }))} placeholder="설명" style={[styles.input, styles.textArea]} multiline />
            <TextInput value={form.location} onChangeText={(location) => setForm((current) => ({ ...current, location }))} placeholder="장소" style={styles.input} />
            <TextInput value={form.ownerId} onChangeText={(ownerId) => setForm((current) => ({ ...current, ownerId }))} placeholder="사용자 ID" style={styles.input} />
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.memberScroller}>
              {members.map((member) => (
                <Pressable key={member.id} style={[styles.memberPill, form.ownerId === member.id && styles.memberPillActive]} onPress={() => setForm((current) => ({ ...current, ownerId: member.id }))}>
                  <Text style={[styles.memberText, form.ownerId === member.id && styles.memberTextActive]}>{member.display_name}</Text>
                </Pressable>
              ))}
            </ScrollView>
            <Pressable style={styles.primaryButton} onPress={saveEvent}>
              <Feather name="check" size={18} color="#fff" />
              <Text style={styles.primaryButtonText}>{editingEvent ? "변경 저장" : "등록"}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      {loading ? (
        <View style={styles.loadingOverlay}>
          <ActivityIndicator color="#fff" />
        </View>
      ) : null}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: "#f7faf9",
  },
  screen: {
    flex: 1,
    padding: 14,
    gap: 10,
  },
  setup: {
    flex: 1,
    justifyContent: "center",
    padding: 22,
    gap: 12,
  },
  brandRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    marginBottom: 12,
  },
  topBar: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  appTitle: {
    fontSize: 24,
    fontWeight: "700",
    color: "#0f172a",
  },
  muted: {
    color: "#64748b",
    fontSize: 12,
  },
  shareRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  inviteInput: {
    flex: 1,
    minHeight: 34,
    borderWidth: 1,
    borderColor: "#cbd5e1",
    borderRadius: 6,
    paddingHorizontal: 10,
    color: "#0f172a",
    backgroundColor: "#fff",
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
    minHeight: 82,
    textAlignVertical: "top",
  },
  primaryButton: {
    minHeight: 44,
    borderRadius: 6,
    backgroundColor: "#0f766e",
    alignItems: "center",
    justifyContent: "center",
    flexDirection: "row",
    gap: 8,
  },
  primaryButtonText: {
    color: "#fff",
    fontWeight: "700",
  },
  smallButton: {
    minHeight: 34,
    borderRadius: 6,
    backgroundColor: "#dbeafe",
    paddingHorizontal: 12,
    alignItems: "center",
    justifyContent: "center",
  },
  smallButtonText: {
    color: "#1e40af",
    fontWeight: "700",
  },
  iconButton: {
    width: 42,
    height: 42,
    borderRadius: 21,
    backgroundColor: "#0f766e",
    alignItems: "center",
    justifyContent: "center",
  },
  monthBar: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  monthTitle: {
    flex: 1,
    textAlign: "center",
    fontSize: 18,
    fontWeight: "700",
    color: "#0f172a",
  },
  navButton: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: "#e2e8f0",
  },
  segment: {
    flexDirection: "row",
    borderWidth: 1,
    borderColor: "#cbd5e1",
    borderRadius: 6,
    overflow: "hidden",
  },
  segmentButton: {
    paddingHorizontal: 12,
    minHeight: 34,
    justifyContent: "center",
    backgroundColor: "#fff",
  },
  segmentActive: {
    backgroundColor: "#0f766e",
  },
  segmentText: {
    color: "#334155",
    fontWeight: "700",
  },
  segmentTextActive: {
    color: "#fff",
  },
  calendarWrap: {
    backgroundColor: "#fff",
    borderWidth: 1,
    borderColor: "#cbd5e1",
    borderRadius: 8,
    overflow: "hidden",
  },
  weekHeader: {
    flexDirection: "row",
    backgroundColor: "#f1f5f9",
    borderBottomWidth: 1,
    borderBottomColor: "#cbd5e1",
  },
  weekLabel: {
    width: "14.2857%",
    textAlign: "center",
    paddingVertical: 8,
    color: "#334155",
    fontWeight: "700",
  },
  grid: {
    flexDirection: "row",
    flexWrap: "wrap",
  },
  weekGrid: {
    minHeight: 112,
  },
  dayCell: {
    width: "14.2857%",
    height: 76,
    borderRightWidth: 1,
    borderBottomWidth: 1,
    borderColor: "#e2e8f0",
    padding: 5,
    gap: 2,
    backgroundColor: "#fff",
  },
  weekCell: {
    height: 112,
  },
  outsideCell: {
    backgroundColor: "#d8dee7",
  },
  selectedCell: {
    backgroundColor: "#ccfbf1",
  },
  dayNumber: {
    color: "#0f172a",
    fontSize: 12,
    fontWeight: "700",
  },
  outsideText: {
    color: "#475569",
  },
  eventChip: {
    backgroundColor: "#e0f2fe",
    color: "#075985",
    borderRadius: 4,
    paddingHorizontal: 4,
    fontSize: 10,
  },
  moreText: {
    fontSize: 10,
    color: "#64748b",
  },
  listHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginTop: 2,
  },
  sectionTitle: {
    fontSize: 17,
    fontWeight: "700",
    color: "#0f172a",
  },
  textButton: {
    minHeight: 32,
    paddingHorizontal: 12,
    justifyContent: "center",
  },
  textButtonLabel: {
    color: "#0f766e",
    fontWeight: "700",
  },
  eventList: {
    flex: 1,
  },
  eventListContent: {
    gap: 8,
    paddingBottom: 28,
  },
  emptyText: {
    color: "#64748b",
    paddingVertical: 20,
    textAlign: "center",
  },
  eventRow: {
    flexDirection: "row",
    alignItems: "center",
    borderWidth: 1,
    borderColor: "#e2e8f0",
    borderRadius: 8,
    backgroundColor: "#fff",
    padding: 10,
  },
  eventMain: {
    flex: 1,
    gap: 3,
  },
  eventTitle: {
    color: "#0f172a",
    fontSize: 15,
    fontWeight: "700",
  },
  eventBody: {
    color: "#334155",
    lineHeight: 19,
  },
  deleteButton: {
    width: 38,
    height: 38,
    alignItems: "center",
    justifyContent: "center",
  },
  modalBackdrop: {
    flex: 1,
    justifyContent: "flex-end",
    backgroundColor: "rgba(15, 23, 42, 0.34)",
  },
  modalPanel: {
    backgroundColor: "#f8fafc",
    padding: 16,
    borderTopLeftRadius: 8,
    borderTopRightRadius: 8,
    gap: 10,
  },
  modalHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  memberScroller: {
    gap: 8,
    paddingVertical: 2,
  },
  memberPill: {
    borderWidth: 1,
    borderColor: "#cbd5e1",
    borderRadius: 16,
    paddingHorizontal: 12,
    minHeight: 32,
    justifyContent: "center",
    backgroundColor: "#fff",
  },
  memberPillActive: {
    backgroundColor: "#0f766e",
    borderColor: "#0f766e",
  },
  memberText: {
    color: "#334155",
    fontWeight: "700",
  },
  memberTextActive: {
    color: "#fff",
  },
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "rgba(15, 23, 42, 0.26)",
    alignItems: "center",
    justifyContent: "center",
  },
});

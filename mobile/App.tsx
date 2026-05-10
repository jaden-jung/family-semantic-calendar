import { Feather } from "@expo/vector-icons";
import * as SecureStore from "expo-secure-store";
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
const savedUserKey = "family-calendar:user";
const holidayKeys = new Set(["01-01", "03-01", "05-05", "06-06", "08-15", "10-03", "10-09", "12-25"]);

type ViewMode = "month" | "week" | "day";
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

function dateFromKey(key: string) {
  const [year, month, day] = key.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function addDays(date: Date, amount: number) {
  const next = new Date(date);
  next.setDate(next.getDate() + amount);
  return next;
}

function addMonths(date: Date, amount: number) {
  return new Date(date.getFullYear(), date.getMonth() + amount, 1);
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

function normalizeTime(input: string) {
  const value = input.trim();
  if (!value) return "";

  const colonMatch = value.match(/^(\d{1,2}):(\d{1,2})$/);
  if (colonMatch) {
    const hour = Number(colonMatch[1]);
    const minute = Number(colonMatch[2]);
    if (hour <= 23 && minute <= 59) return `${pad(hour)}:${pad(minute)}`;
    return null;
  }

  const compact = value.replace(/\D/g, "");
  if (compact.length === 1 || compact.length === 2) {
    const hour = Number(compact);
    if (hour <= 23) return `${pad(hour)}:00`;
    return null;
  }
  if (compact.length === 3 || compact.length === 4) {
    const hour = Number(compact.slice(0, -2));
    const minute = Number(compact.slice(-2));
    if (hour <= 23 && minute <= 59) return `${pad(hour)}:${pad(minute)}`;
    return null;
  }
  return null;
}

function combineDateTime(date: string, time: string) {
  const normalized = normalizeTime(time);
  if (normalized === null) return null;
  const timestamp = new Date(`${date}T${normalized || "00:00"}:00`);
  if (Number.isNaN(timestamp.getTime())) return null;
  return timestamp.toISOString();
}

function timeFromIso(value: string) {
  const date = new Date(value);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function isHoliday(date: Date) {
  return date.getDay() === 0 || holidayKeys.has(`${pad(date.getMonth() + 1)}-${pad(date.getDate())}`);
}

function ownerName(members: User[], id: string | null) {
  return members.find((member) => member.id === id)?.display_name || "알 수 없음";
}

async function readSavedUser() {
  try {
    return await SecureStore.getItemAsync(savedUserKey);
  } catch {
    return null;
  }
}

async function writeSavedUser(user: User) {
  try {
    await SecureStore.setItemAsync(savedUserKey, JSON.stringify(user));
  } catch {
    // SecureStore is only a convenience cache. Account creation should still succeed.
  }
}

export default function App() {
  const [booting, setBooting] = useState(true);
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
  const [modeMenuOpen, setModeMenuOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [ownerMenuOpen, setOwnerMenuOpen] = useState(false);
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
  const todayKey = toDateKey(new Date());

  const calendarDays = useMemo(() => {
    if (viewMode === "day") return [dateFromKey(selectedDateKey)];
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
    void restoreUser();
  }, []);

  useEffect(() => {
    if (userId) void refreshCalendars();
  }, [userId]);

  useEffect(() => {
    if (selectedCalendarId && userId) {
      void refreshEvents(selectedCalendarId);
      void refreshMembers(selectedCalendarId);
    } else {
      setEvents([]);
      setMembers([]);
    }
  }, [selectedCalendarId, userId]);

  async function restoreUser() {
    try {
      const stored = await readSavedUser();
      if (stored) {
        const user = JSON.parse(stored) as User;
        setUserId(user.id);
        setDisplayName(user.display_name);
      }
    } finally {
      setBooting(false);
    }
  }

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
      await writeSavedUser(user);
      setUserId(user.id);
      setDisplayName(user.display_name);
      const calendar = await api.createCalendar(calendarName.trim() || "우리집 달력", user.id);
      setCalendars([calendar]);
      setSelectedCalendarId(calendar.id);
      setMembers([user]);
    });
  }

  async function createCalendar() {
    if (!userId) return;
    await withLoading(async () => {
      const calendar = await api.createCalendar(calendarName.trim() || "새 달력", userId);
      setCalendars((current) => [calendar, ...current]);
      setSelectedCalendarId(calendar.id);
      setSettingsOpen(false);
    });
  }

  async function joinCalendar() {
    if (!userId || !inviteCode.trim()) return;
    await withLoading(async () => {
      const calendar = await api.joinCalendar(inviteCode.trim(), userId);
      setCalendars((current) => [calendar, ...current.filter((item) => item.id !== calendar.id)]);
      setSelectedCalendarId(calendar.id);
      setInviteCode("");
      setSettingsOpen(false);
    });
  }

  async function refreshCalendars() {
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
    if (viewMode === "day") {
      const next = addDays(dateFromKey(selectedDateKey), direction);
      setSelectedDateKey(toDateKey(next));
      setVisibleDate(new Date(next.getFullYear(), next.getMonth(), 1));
      return;
    }
    if (viewMode === "week") {
      const next = addDays(dateFromKey(selectedDateKey), direction * 7);
      setSelectedDateKey(toDateKey(next));
      setVisibleDate(new Date(next.getFullYear(), next.getMonth(), 1));
      return;
    }
    const next = addMonths(visibleDate, direction);
    setVisibleDate(next);
  }

  function openCreateForm(dateKey = selectedDateKey) {
    setEditingEvent(null);
    setForm({
      title: "",
      body: "",
      location: "",
      date: dateKey,
      time: "",
      ownerId: members[0]?.id || userId,
    });
    setOwnerMenuOpen(false);
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
    setOwnerMenuOpen(false);
    setFormOpen(true);
  }

  async function saveEvent() {
    const startsAt = combineDateTime(form.date, form.time);
    if (!startsAt) {
      Alert.alert("시간 확인", "시간은 15:00, 1500, 15처럼 입력할 수 있습니다.");
      return;
    }
    const normalizedTime = normalizeTime(form.time);
    if (!selectedCalendarId || !form.title.trim() || !form.ownerId) {
      Alert.alert("입력 확인", "제목과 사용자 선택은 필수입니다.");
      return;
    }

    await withLoading(async () => {
      const payload = {
        created_by: form.ownerId,
        title: form.title.trim(),
        body: form.body.trim(),
        location: form.location.trim(),
        starts_at: startsAt,
        ends_at: normalizedTime ? startsAt : null,
      };
      if (editingEvent) {
        await api.updateEvent(editingEvent.id, payload, userId);
      } else {
        await api.createEvent({ calendar_id: selectedCalendarId, ...payload });
      }
      setSelectedDateKey(form.date);
      const savedDate = dateFromKey(form.date);
      setVisibleDate(new Date(savedDate.getFullYear(), savedDate.getMonth(), 1));
      setFormOpen(false);
      await refreshEvents(selectedCalendarId);
    });
  }

  function confirmDeleteEvent() {
    if (!editingEvent) return;
    Alert.alert("일정 삭제", "이 일정을 진짜 삭제할까요?", [
      { text: "취소", style: "cancel" },
      {
        text: "삭제",
        style: "destructive",
        onPress: () => {
          void withLoading(async () => {
            await api.deleteEvent(editingEvent.id, userId);
            setFormOpen(false);
            await refreshEvents(selectedCalendarId);
          });
        },
      },
    ]);
  }

  function modeLabel(mode: ViewMode) {
    if (mode === "month") return "월";
    if (mode === "week") return "주";
    return "일";
  }

  if (booting) {
    return (
      <SafeAreaView style={styles.safe}>
        <View style={styles.centerPanel}>
          <ActivityIndicator />
        </View>
      </SafeAreaView>
    );
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
            <Text style={styles.primaryButtonText}>사용자 등록</Text>
          </Pressable>
          {loading ? <ActivityIndicator /> : null}
        </View>
      </SafeAreaView>
    );
  }

  if (!selectedCalendarId) {
    return (
      <SafeAreaView style={styles.safe}>
        <StatusBar style="dark" />
        <View style={styles.topBarOnly}>
          <Text style={styles.appTitle}>Family Calendar</Text>
          <Pressable style={styles.navButton} onPress={() => setSettingsOpen(true)}>
            <Feather name="settings" size={22} color="#0f172a" />
          </Pressable>
        </View>
        <View style={styles.centerPanel}>
          <Text style={styles.sectionTitle}>참여 중인 달력이 없습니다.</Text>
          <Pressable style={styles.primaryButtonWide} onPress={() => setSettingsOpen(true)}>
            <Feather name="calendar" size={18} color="#fff" />
            <Text style={styles.primaryButtonText}>달력 추가/선택</Text>
          </Pressable>
        </View>
        {renderSettingsModal()}
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
          <Pressable style={styles.navButton} onPress={() => setSettingsOpen(true)}>
            <Feather name="settings" size={22} color="#0f172a" />
          </Pressable>
        </View>

        <View style={styles.monthBar}>
          <Pressable style={styles.navButton} onPress={() => movePeriod(-1)}>
            <Feather name="chevron-left" size={22} color="#0f172a" />
          </Pressable>
          <Text style={styles.monthTitle}>{viewMode === "month" ? monthLabel(visibleDate) : selectedDateKey}</Text>
          <Pressable style={styles.navButton} onPress={() => movePeriod(1)}>
            <Feather name="chevron-right" size={22} color="#0f172a" />
          </Pressable>
          <Pressable style={styles.comboButton} onPress={() => setModeMenuOpen(true)}>
            <Text style={styles.comboText}>{modeLabel(viewMode)}</Text>
            <Feather name="chevron-down" size={16} color="#0f172a" />
          </Pressable>
        </View>

        <View style={styles.calendarWrap} {...swipeResponder.panHandlers}>
          {viewMode !== "day" ? (
            <View style={styles.weekHeader}>
              {dayLabels.map((label, index) => (
                <Text key={label} style={[styles.weekLabel, index === 0 && styles.holidayText]}>
                  {label}
                </Text>
              ))}
            </View>
          ) : null}
          <View style={[styles.grid, viewMode === "week" && styles.weekGrid, viewMode === "day" && styles.dayGrid]}>
            {calendarDays.map((date) => {
              const key = toDateKey(date);
              const dayEvents = eventsByDate.get(key) || [];
              const isCurrentMonth = date.getMonth() === visibleDate.getMonth();
              const isSelected = key === selectedDateKey;
              const isToday = key === todayKey;
              const redDay = isHoliday(date);
              const eventLimit = viewMode === "day" ? 50 : viewMode === "week" ? 8 : 2;
              return (
                <Pressable
                  key={key}
                  style={[
                    styles.dayCell,
                    viewMode === "week" && styles.weekCell,
                    viewMode === "day" && styles.singleDayCell,
                    !isCurrentMonth && viewMode === "month" && styles.outsideCell,
                    isToday && styles.todayCell,
                    isSelected && styles.selectedCell,
                  ]}
                  onPress={() => {
                    setSelectedDateKey(key);
                    setVisibleDate(new Date(date.getFullYear(), date.getMonth(), 1));
                  }}
                >
                  <Text style={[styles.dayNumber, redDay && styles.holidayText, !isCurrentMonth && viewMode === "month" && styles.outsideText]}>
                    {viewMode === "day" ? `${key} ${dayLabels[date.getDay()]}` : date.getDate()}
                  </Text>
                  {dayEvents.slice(0, eventLimit).map((event) => (
                    <Text key={event.id} numberOfLines={viewMode === "day" ? 2 : 1} style={[styles.eventChip, viewMode === "day" && styles.dayEventChip]}>
                      {viewMode === "day" ? `${timeFromIso(event.starts_at)} ${event.title} ${event.location ? `· ${event.location}` : ""}` : event.title}
                    </Text>
                  ))}
                  {dayEvents.length > eventLimit ? <Text style={styles.moreText}>+{dayEvents.length - eventLimit}</Text> : null}
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
            <Pressable key={event.id} style={styles.eventRow} onPress={() => openEditForm(event)}>
              <Text style={styles.eventTitle}>{event.title}</Text>
              <Text style={styles.muted}>
                {timeFromIso(event.starts_at)} · {ownerName(members, event.created_by)}
                {event.location ? ` · ${event.location}` : ""}
              </Text>
              {event.body ? <Text style={styles.eventBody}>{event.body}</Text> : null}
            </Pressable>
          ))}
        </ScrollView>
      </View>

      {renderModeMenu()}
      {renderEventModal()}
      {renderSettingsModal()}
      {loading ? (
        <View style={styles.loadingOverlay}>
          <ActivityIndicator color="#fff" />
        </View>
      ) : null}
    </SafeAreaView>
  );

  function renderModeMenu() {
    return (
      <Modal visible={modeMenuOpen} transparent animationType="fade">
        <Pressable style={styles.menuBackdrop} onPress={() => setModeMenuOpen(false)}>
          <View style={styles.menuPanel}>
            {(["month", "week", "day"] as ViewMode[]).map((mode) => (
              <Pressable
                key={mode}
                style={[styles.menuItem, viewMode === mode && styles.menuItemActive]}
                onPress={() => {
                  setViewMode(mode);
                  setModeMenuOpen(false);
                }}
              >
                <Text style={[styles.menuItemText, viewMode === mode && styles.menuItemTextActive]}>{modeLabel(mode)} 보기</Text>
              </Pressable>
            ))}
          </View>
        </Pressable>
      </Modal>
    );
  }

  function renderEventModal() {
    return (
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
            <TextInput
              value={form.time}
              onChangeText={(time) => setForm((current) => ({ ...current, time }))}
              onBlur={() => {
                const normalized = normalizeTime(form.time);
                if (normalized) setForm((current) => ({ ...current, time: normalized }));
              }}
              placeholder="시간 15:00 또는 1500 (선택)"
              keyboardType="numbers-and-punctuation"
              style={styles.input}
            />
            <TextInput value={form.title} onChangeText={(title) => setForm((current) => ({ ...current, title }))} placeholder="제목" style={styles.input} />
            <TextInput value={form.body} onChangeText={(body) => setForm((current) => ({ ...current, body }))} placeholder="설명" style={[styles.input, styles.textArea]} multiline />
            <TextInput value={form.location} onChangeText={(location) => setForm((current) => ({ ...current, location }))} placeholder="장소" style={styles.input} />
            <Text style={styles.fieldLabel}>누구의 일정인가요?</Text>
            <Pressable style={styles.ownerSelect} onPress={() => setOwnerMenuOpen((current) => !current)}>
              <Text style={styles.ownerSelectText}>{ownerName(members, form.ownerId)}</Text>
              <Feather name="chevron-down" size={18} color="#0f172a" />
            </Pressable>
            {ownerMenuOpen ? (
              <View style={styles.ownerMenu}>
                {members.map((member) => (
                  <Pressable
                    key={member.id}
                    style={[styles.ownerMenuItem, form.ownerId === member.id && styles.ownerMenuItemActive]}
                    onPress={() => {
                      setForm((current) => ({ ...current, ownerId: member.id }));
                      setOwnerMenuOpen(false);
                    }}
                  >
                    <Text style={[styles.ownerMenuText, form.ownerId === member.id && styles.ownerMenuTextActive]}>{member.display_name}</Text>
                  </Pressable>
                ))}
              </View>
            ) : null}
            <Pressable style={styles.primaryButton} onPress={saveEvent}>
              <Feather name="check" size={18} color="#fff" />
              <Text style={styles.primaryButtonText}>{editingEvent ? "변경 저장" : "등록"}</Text>
            </Pressable>
            {editingEvent ? (
              <Pressable style={styles.dangerButton} onPress={confirmDeleteEvent}>
                <Feather name="trash-2" size={18} color="#fff" />
                <Text style={styles.primaryButtonText}>삭제</Text>
              </Pressable>
            ) : null}
          </View>
        </View>
      </Modal>
    );
  }

  function renderSettingsModal() {
    return (
      <Modal visible={settingsOpen} animationType="slide" transparent>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalPanel}>
            <View style={styles.modalHeader}>
              <Text style={styles.sectionTitle}>설정</Text>
              <Pressable style={styles.navButton} onPress={() => setSettingsOpen(false)}>
                <Feather name="x" size={22} color="#0f172a" />
              </Pressable>
            </View>
            <Text style={styles.fieldLabel}>달력 선택</Text>
            {calendars.map((calendar) => (
              <Pressable
                key={calendar.id}
                style={[styles.calendarOption, calendar.id === selectedCalendarId && styles.calendarOptionActive]}
                onPress={() => {
                  setSelectedCalendarId(calendar.id);
                  setSettingsOpen(false);
                }}
              >
                <Text style={styles.eventTitle}>{calendar.name}</Text>
                <Text style={styles.muted}>초대 코드 {calendar.invite_code}</Text>
              </Pressable>
            ))}
            <TextInput value={calendarName} onChangeText={setCalendarName} placeholder="새 달력 이름" style={styles.input} />
            <Pressable style={styles.primaryButton} onPress={createCalendar}>
              <Feather name="plus" size={18} color="#fff" />
              <Text style={styles.primaryButtonText}>달력 만들기</Text>
            </Pressable>
            <TextInput value={inviteCode} onChangeText={setInviteCode} placeholder="초대 코드 입력" style={styles.input} />
            <Pressable style={styles.secondaryButton} onPress={joinCalendar}>
              <Feather name="users" size={18} color="#0f172a" />
              <Text style={styles.secondaryButtonText}>초대 코드로 참여</Text>
            </Pressable>
            <View style={styles.preferencePreview}>
              <Text style={styles.fieldLabel}>개인화 설정 예정</Text>
              <Text style={styles.muted}>글자 크기, 색상, 시작 요일, 공휴일 기준 국가를 이곳에서 설정할 예정입니다.</Text>
            </View>
          </View>
        </View>
      </Modal>
    );
  }
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
  centerPanel: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: 14,
    padding: 22,
  },
  topBarOnly: {
    padding: 14,
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
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
  primaryButtonWide: {
    minHeight: 44,
    minWidth: 180,
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
  dangerButton: {
    minHeight: 44,
    borderRadius: 6,
    backgroundColor: "#b91c1c",
    alignItems: "center",
    justifyContent: "center",
    flexDirection: "row",
    gap: 8,
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
  comboButton: {
    minHeight: 36,
    minWidth: 62,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: "#cbd5e1",
    backgroundColor: "#fff",
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 4,
  },
  comboText: {
    color: "#0f172a",
    fontWeight: "700",
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
    minHeight: 168,
  },
  dayGrid: {
    minHeight: 260,
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
    height: 168,
  },
  singleDayCell: {
    width: "100%",
    height: 260,
  },
  outsideCell: {
    backgroundColor: "#d8dee7",
  },
  todayCell: {
    backgroundColor: "#fef3c7",
  },
  selectedCell: {
    borderWidth: 2,
    borderColor: "#0f766e",
  },
  dayNumber: {
    color: "#0f172a",
    fontSize: 12,
    fontWeight: "700",
  },
  holidayText: {
    color: "#dc2626",
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
  dayEventChip: {
    fontSize: 13,
    paddingVertical: 5,
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
  fieldLabel: {
    fontSize: 13,
    fontWeight: "700",
    color: "#334155",
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
    borderWidth: 1,
    borderColor: "#e2e8f0",
    borderRadius: 8,
    backgroundColor: "#fff",
    padding: 10,
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
    maxHeight: "92%",
  },
  modalHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  ownerSelect: {
    borderWidth: 1,
    borderColor: "#cbd5e1",
    borderRadius: 6,
    paddingHorizontal: 12,
    minHeight: 44,
    backgroundColor: "#fff",
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  ownerSelectText: {
    color: "#0f172a",
    fontWeight: "700",
  },
  ownerMenu: {
    borderWidth: 1,
    borderColor: "#cbd5e1",
    borderRadius: 6,
    backgroundColor: "#fff",
    overflow: "hidden",
  },
  ownerMenuItem: {
    minHeight: 40,
    justifyContent: "center",
    paddingHorizontal: 12,
    borderBottomWidth: 1,
    borderBottomColor: "#e2e8f0",
  },
  ownerMenuItemActive: {
    backgroundColor: "#0f766e",
  },
  ownerMenuText: {
    color: "#0f172a",
    fontWeight: "700",
  },
  ownerMenuTextActive: {
    color: "#fff",
  },
  menuBackdrop: {
    flex: 1,
    alignItems: "flex-end",
    paddingTop: 96,
    paddingRight: 14,
    backgroundColor: "rgba(15, 23, 42, 0.16)",
  },
  menuPanel: {
    width: 132,
    borderRadius: 8,
    backgroundColor: "#fff",
    borderWidth: 1,
    borderColor: "#cbd5e1",
    overflow: "hidden",
  },
  menuItem: {
    minHeight: 42,
    justifyContent: "center",
    paddingHorizontal: 12,
  },
  menuItemActive: {
    backgroundColor: "#0f766e",
  },
  menuItemText: {
    color: "#0f172a",
    fontWeight: "700",
  },
  menuItemTextActive: {
    color: "#fff",
  },
  calendarOption: {
    borderWidth: 1,
    borderColor: "#e2e8f0",
    borderRadius: 8,
    backgroundColor: "#fff",
    padding: 10,
    gap: 2,
  },
  calendarOptionActive: {
    borderColor: "#0f766e",
    backgroundColor: "#ccfbf1",
  },
  preferencePreview: {
    borderWidth: 1,
    borderColor: "#e2e8f0",
    borderRadius: 8,
    backgroundColor: "#fff",
    padding: 10,
    gap: 4,
  },
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "rgba(15, 23, 42, 0.26)",
    alignItems: "center",
    justifyContent: "center",
  },
});

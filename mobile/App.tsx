import { Feather } from "@expo/vector-icons";
import * as Clipboard from "expo-clipboard";
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

import { ApiClient, Calendar, EventItem, RecurrenceRule, User } from "./src/api";

const apiBaseUrl = process.env.EXPO_PUBLIC_API_BASE_URL || "http://100.68.67.109:8000";
const api = new ApiClient(apiBaseUrl);
const dayLabels = ["일", "월", "화", "수", "목", "금", "토"];
const savedUserKey = "family-calendar:user";
const calendarColors = ["#0f766e", "#2563eb", "#c2410c", "#7c3aed", "#be123c", "#15803d"];
const holidays: Record<string, string> = {
  "01-01": "신정",
  "03-01": "삼일절",
  "05-01": "노동절",
  "05-05": "어린이날",
  "06-06": "현충일",
  "08-15": "광복절",
  "10-03": "개천절",
  "10-09": "한글날",
  "12-25": "성탄절",
};

type RepeatType = "daily" | "weekly" | "monthly" | "yearly";
type MonthMode = "day" | "weekday";
type DisplayEvent = EventItem & {
  occurrenceKey: string;
  originalStartsAt: string;
  color: string;
  calendarName: string;
};
type EventForm = {
  calendarId: string;
  title: string;
  body: string;
  location: string;
  date: string;
  time: string;
  ownerId: string;
  repeatEnabled: boolean;
  repeatType: RepeatType;
  repeatInterval: string;
  repeatWeekday: string;
  repeatMonthMode: MonthMode;
  repeatMonthDay: string;
  repeatWeekOfMonth: string;
  repeatLunar: boolean;
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
  if (compact.length <= 2) {
    const hour = Number(compact);
    if (hour <= 23) return `${pad(hour)}:00`;
    return null;
  }
  if (compact.length === 3 || compact.length === 4) {
    const hour = Number(compact.slice(0, -2));
    const minute = Number(compact.slice(-2));
    if (hour <= 23 && minute <= 59) return `${pad(hour)}:${pad(minute)}`;
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

function holidayName(date: Date) {
  return holidays[`${pad(date.getMonth() + 1)}-${pad(date.getDate())}`] || "";
}

function isHoliday(date: Date) {
  return date.getDay() === 0 || Boolean(holidayName(date));
}

function ownerName(members: User[], id: string | null) {
  return members.find((member) => member.id === id)?.display_name || "알 수 없음";
}

function nthWeekdayDate(year: number, month: number, weekday: number, weekOfMonth: number) {
  const first = new Date(year, month, 1);
  const offset = (weekday - first.getDay() + 7) % 7;
  return new Date(year, month, 1 + offset + (weekOfMonth - 1) * 7);
}

function buildRule(form: EventForm): RecurrenceRule | null {
  if (!form.repeatEnabled) return null;
  const interval = Math.max(1, Number(form.repeatInterval || 1));
  if (form.repeatType === "daily") return { frequency: "daily", interval };
  if (form.repeatType === "weekly") {
    return { frequency: "weekly", interval: Math.min(3, interval), weekdays: [Number(form.repeatWeekday)] };
  }
  if (form.repeatType === "monthly") {
    if (form.repeatMonthMode === "weekday") {
      return {
        frequency: "monthly",
        interval: 1,
        weekdays: [Number(form.repeatWeekday)],
        weekOfMonth: Math.max(1, Math.min(5, Number(form.repeatWeekOfMonth || 1))),
      };
    }
    return { frequency: "monthly", interval: 1, monthDay: Math.max(1, Math.min(31, Number(form.repeatMonthDay || 1))) };
  }
  return { frequency: "yearly", interval: 1, lunar: form.repeatLunar };
}

function expandEvents(events: EventItem[], calendars: Calendar[], rangeStart: Date, rangeEnd: Date): DisplayEvent[] {
  const displays: DisplayEvent[] = [];
  const calendarInfo = new Map(calendars.map((calendar, index) => [calendar.id, { name: calendar.name, color: calendarColors[index % calendarColors.length] }]));

  function pushEvent(event: EventItem, startsAt: Date) {
    if (startsAt < new Date(event.starts_at)) return;
    if (startsAt < rangeStart || startsAt > rangeEnd) return;
    const info = calendarInfo.get(event.calendar_id) || { name: "달력", color: "#0f766e" };
    displays.push({
      ...event,
      starts_at: startsAt.toISOString(),
      occurrenceKey: `${event.id}:${toDateKey(startsAt)}`,
      originalStartsAt: event.starts_at,
      color: info.color,
      calendarName: info.name,
    });
  }

  events.forEach((event) => {
    const start = new Date(event.starts_at);
    const rule = event.recurrence_rule;
    if (!rule) {
      pushEvent(event, start);
      return;
    }

    const interval = Math.max(1, rule.interval || 1);
    if (rule.frequency === "daily") {
      for (let current = new Date(start); current <= rangeEnd; current = addDays(current, interval)) pushEvent(event, current);
    }
    if (rule.frequency === "weekly") {
      for (let cursor = new Date(start); cursor <= rangeEnd; cursor = addDays(cursor, 1)) {
        const weeks = Math.floor((cursor.getTime() - start.getTime()) / (7 * 24 * 60 * 60 * 1000));
        if (weeks >= 0 && weeks % interval === 0 && (rule.weekdays || [start.getDay()]).includes(cursor.getDay())) {
          const occurrence = new Date(cursor);
          occurrence.setHours(start.getHours(), start.getMinutes(), 0, 0);
          pushEvent(event, occurrence);
        }
      }
    }
    if (rule.frequency === "monthly") {
      for (let cursor = new Date(start.getFullYear(), start.getMonth(), 1); cursor <= rangeEnd; cursor = new Date(cursor.getFullYear(), cursor.getMonth() + interval, 1)) {
        const occurrence = rule.weekOfMonth
          ? nthWeekdayDate(cursor.getFullYear(), cursor.getMonth(), (rule.weekdays || [start.getDay()])[0], rule.weekOfMonth)
          : new Date(cursor.getFullYear(), cursor.getMonth(), rule.monthDay || start.getDate());
        occurrence.setHours(start.getHours(), start.getMinutes(), 0, 0);
        pushEvent(event, occurrence);
      }
    }
    if (rule.frequency === "yearly") {
      for (let year = start.getFullYear(); year <= rangeEnd.getFullYear(); year += interval) {
        const occurrence = new Date(year, start.getMonth(), start.getDate(), start.getHours(), start.getMinutes(), 0, 0);
        pushEvent(event, occurrence);
      }
    }
  });

  return displays.sort((a, b) => new Date(a.starts_at).getTime() - new Date(b.starts_at).getTime());
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
    // Best-effort local cache only.
  }
}

export default function App() {
  const [booting, setBooting] = useState(true);
  const [userId, setUserId] = useState("");
  const [displayName, setDisplayName] = useState("가족");
  const [password, setPassword] = useState("");
  const [calendarName, setCalendarName] = useState("우리집 달력");
  const [inviteCode, setInviteCode] = useState("");
  const [calendars, setCalendars] = useState<Calendar[]>([]);
  const [visibleCalendarIds, setVisibleCalendarIds] = useState<string[]>([]);
  const [members, setMembers] = useState<User[]>([]);
  const [selectedCalendarId, setSelectedCalendarId] = useState("");
  const [events, setEvents] = useState<EventItem[]>([]);
  const [visibleDate, setVisibleDate] = useState(() => new Date());
  const [selectedDateKey, setSelectedDateKey] = useState(() => toDateKey(new Date()));
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<DisplayEvent[]>([]);
  const [searchMaxDistance, setSearchMaxDistance] = useState("0.2");
  const [yearMonthOpen, setYearMonthOpen] = useState(false);
  const [yearInput, setYearInput] = useState(String(new Date().getFullYear()));
  const [monthInput, setMonthInput] = useState(String(new Date().getMonth() + 1));
  const [formOpen, setFormOpen] = useState(false);
  const [ownerMenuOpen, setOwnerMenuOpen] = useState(false);
  const [calendarMenuOpen, setCalendarMenuOpen] = useState(false);
  const [repeatOpen, setRepeatOpen] = useState(false);
  const [editingEvent, setEditingEvent] = useState<DisplayEvent | null>(null);
  const [form, setForm] = useState<EventForm>(blankForm(toDateKey(new Date()), "", ""));
  const [loading, setLoading] = useState(false);

  const selectedCalendar = calendars.find((calendar) => calendar.id === selectedCalendarId);
  const activeCalendars = calendars.filter((calendar) => visibleCalendarIds.includes(calendar.id));
  const rangeStart = addDays(startOfMonthGrid(visibleDate), -35);
  const rangeEnd = addDays(rangeStart, 520);
  const displayEvents = useMemo(() => expandEvents(events, calendars, rangeStart, rangeEnd), [events, calendars, rangeStart.getTime(), rangeEnd.getTime()]);
  const eventsByDate = useMemo(() => {
    const map = new Map<string, DisplayEvent[]>();
    displayEvents.forEach((event) => {
      const key = toDateKey(new Date(event.starts_at));
      map.set(key, [...(map.get(key) || []), event]);
    });
    return map;
  }, [displayEvents]);
  const selectedEvents = eventsByDate.get(selectedDateKey) || [];
  const todayKey = toDateKey(new Date());

  const calendarDays = useMemo(() => {
    const gridStart = startOfMonthGrid(visibleDate);
    return Array.from({ length: 42 }, (_, index) => addDays(gridStart, index));
  }, [visibleDate]);

  const swipeResponder = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_, gesture) => Math.abs(gesture.dx) > 24 && Math.abs(gesture.dy) < 18,
        onPanResponderRelease: (_, gesture) => {
          if (gesture.dx < -45) movePeriod(1);
          if (gesture.dx > 45) movePeriod(-1);
        },
      }),
    [visibleDate],
  );

  useEffect(() => {
    void restoreUser();
  }, []);

  useEffect(() => {
    if (userId) void refreshCalendars();
  }, [userId]);

  useEffect(() => {
    if (userId && visibleCalendarIds.length) void refreshEvents(visibleCalendarIds);
  }, [visibleCalendarIds.join(","), userId]);

  useEffect(() => {
    if (selectedCalendarId && userId) void refreshMembers(selectedCalendarId);
  }, [selectedCalendarId, userId]);

  function blankForm(dateKey: string, ownerId: string, calendarId: string): EventForm {
    const date = dateFromKey(dateKey);
    return {
      calendarId,
      title: "",
      body: "",
      location: "",
      date: dateKey,
      time: "",
      ownerId,
      repeatEnabled: false,
      repeatType: "daily",
      repeatInterval: "1",
      repeatWeekday: String(date.getDay()),
      repeatMonthMode: "day",
      repeatMonthDay: String(date.getDate()),
      repeatWeekOfMonth: String(Math.ceil(date.getDate() / 7)),
      repeatLunar: false,
    };
  }

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
    if (!password.trim()) {
      Alert.alert("비밀번호 확인", "비밀번호를 입력해 주세요.");
      return;
    }
    await withLoading(async () => {
      const user = await api.createUser(displayName.trim() || "가족", password);
      await writeSavedUser(user);
      setUserId(user.id);
      setDisplayName(user.display_name);
      setCalendars([]);
      setSelectedCalendarId("");
      setVisibleCalendarIds([]);
      setMembers([user]);
    });
  }

  async function signIn() {
    if (!displayName.trim() || !password.trim()) {
      Alert.alert("로그인 확인", "사용자 이름과 비밀번호를 입력해 주세요.");
      return;
    }
    await withLoading(async () => {
      const user = await api.signIn(displayName.trim(), password);
      await writeSavedUser(user);
      setUserId(user.id);
      setDisplayName(user.display_name);
    });
  }

  async function createCalendar() {
    const name = calendarName.trim();
    if (!userId || !name) return;
    if (calendars.some((calendar) => calendar.name.trim() === name)) {
      Alert.alert("중복된 달력", "같은 이름의 달력이 이미 있습니다.");
      return;
    }
    await withLoading(async () => {
      const calendar = await api.createCalendar(name, userId);
      setCalendars((current) => [calendar, ...current]);
      setSelectedCalendarId(calendar.id);
      setVisibleCalendarIds((current) => [calendar.id, ...current]);
      setSettingsOpen(false);
    });
  }

  async function joinCalendar() {
    if (!userId || !inviteCode.trim()) return;
    await withLoading(async () => {
      const calendar = await api.joinCalendar(inviteCode.trim(), userId);
      setCalendars((current) => [calendar, ...current.filter((item) => item.id !== calendar.id)]);
      setSelectedCalendarId(calendar.id);
      setVisibleCalendarIds((current) => Array.from(new Set([calendar.id, ...current])));
      setInviteCode("");
      setSettingsOpen(false);
    });
  }

  async function refreshCalendars() {
    const items = await api.listCalendars(userId);
    setCalendars(items);
    setSelectedCalendarId((current) => current || items[0]?.id || "");
    setVisibleCalendarIds((current) => (current.length ? current.filter((id) => items.some((calendar) => calendar.id === id)) : items.map((calendar) => calendar.id)));
  }

  async function refreshMembers(calendarId: string) {
    const items = await api.listCalendarMembers(calendarId, userId);
    setMembers(items);
  }

  async function refreshEvents(calendarIds: string[]) {
    const groups = await Promise.all(calendarIds.map((calendarId) => api.listEvents(calendarId, userId)));
    setEvents(groups.flat());
  }

  function displayFromEvent(event: EventItem): DisplayEvent {
    const calendarIndex = calendars.findIndex((calendar) => calendar.id === event.calendar_id);
    const calendar = calendars.find((item) => item.id === event.calendar_id);
    const start = new Date(event.starts_at);
    return {
      ...event,
      occurrenceKey: `${event.id}:search`,
      originalStartsAt: event.starts_at,
      color: calendarColors[Math.max(0, calendarIndex) % calendarColors.length] || "#0f766e",
      calendarName: calendar?.name || "캘린더",
      starts_at: start.toISOString(),
    };
  }

  async function runSearch() {
    const query = searchQuery.trim();
    if (!query) {
      Alert.alert("검색어 확인", "검색어를 입력해 주세요.");
      return;
    }
    const maxDistance = Number(searchMaxDistance);
    if (Number.isNaN(maxDistance) || maxDistance < 0 || maxDistance > 2) {
      Alert.alert("임계치 확인", "검색 임계치는 0~2 사이 숫자로 입력해 주세요. 낮을수록 더 엄격합니다.");
      return;
    }
    if (!visibleCalendarIds.length) return;
    await withLoading(async () => {
      const groups = await Promise.all(visibleCalendarIds.map((calendarId) => api.searchEvents(calendarId, query, userId, maxDistance)));
      const unique = new Map<string, EventItem>();
      groups.flat().forEach((event) => unique.set(event.id, event));
      setSearchResults(Array.from(unique.values()).map(displayFromEvent));
    });
  }

  function goToEventDate(event: DisplayEvent) {
    const date = new Date(event.starts_at);
    const key = toDateKey(date);
    setSelectedDateKey(key);
    setVisibleDate(new Date(date.getFullYear(), date.getMonth(), 1));
    setSearchOpen(false);
  }

  function openYearMonthPicker() {
    setYearInput(String(visibleDate.getFullYear()));
    setMonthInput(String(visibleDate.getMonth() + 1));
    setYearMonthOpen(true);
  }

  function applyYearMonth() {
    const year = Number(yearInput);
    const month = Number(monthInput);
    if (!Number.isInteger(year) || !Number.isInteger(month) || year < 1900 || year > 2100 || month < 1 || month > 12) {
      Alert.alert("년월 확인", "연도는 1900~2100, 월은 1~12 사이로 입력해 주세요.");
      return;
    }
    const next = new Date(year, month - 1, 1);
    setVisibleDate(next);
    setSelectedDateKey(toDateKey(next));
    setYearMonthOpen(false);
  }

  function movePeriod(direction: number) {
    setVisibleDate((current) => {
      const next = new Date(current.getFullYear(), current.getMonth() + direction, 1);
      setSelectedDateKey(toDateKey(next));
      return next;
    });
  }

  function openCreateForm(dateKey = selectedDateKey) {
    const defaultCalendarId = selectedCalendarId || visibleCalendarIds[0] || calendars[0]?.id || "";
    setEditingEvent(null);
    setForm(blankForm(dateKey, userId, defaultCalendarId));
    setOwnerMenuOpen(false);
    setCalendarMenuOpen(false);
    setRepeatOpen(false);
    setFormOpen(true);
  }

  function openEditForm(event: DisplayEvent) {
    const originalDate = new Date(event.originalStartsAt);
    const rule = event.recurrence_rule;
    setEditingEvent(event);
    setForm({
      ...blankForm(toDateKey(originalDate), event.created_by || userId, event.calendar_id),
      title: event.title,
      body: event.body,
      location: event.location || "",
      time: event.ends_at ? timeFromIso(event.originalStartsAt) : "",
      repeatEnabled: Boolean(rule),
      repeatType: (rule?.frequency as RepeatType) || "daily",
      repeatInterval: String(rule?.interval || 1),
      repeatWeekday: String(rule?.weekdays?.[0] ?? originalDate.getDay()),
      repeatMonthMode: rule?.weekOfMonth ? "weekday" : "day",
      repeatMonthDay: String(rule?.monthDay || originalDate.getDate()),
      repeatWeekOfMonth: String(rule?.weekOfMonth || Math.ceil(originalDate.getDate() / 7)),
      repeatLunar: Boolean(rule?.lunar),
    });
    setOwnerMenuOpen(false);
    setCalendarMenuOpen(false);
    setRepeatOpen(Boolean(rule));
    setFormOpen(true);
  }

  async function saveEvent() {
    const startsAt = combineDateTime(form.date, form.time);
    if (!startsAt) {
      Alert.alert("시간 확인", "시간은 15:00, 1500, 15처럼 입력할 수 있습니다.");
      return;
    }
    const normalizedTime = normalizeTime(form.time);
    if (!form.calendarId || !form.title.trim() || !form.ownerId) {
      Alert.alert("입력 확인", "달력, 제목, 사용자 선택은 필수입니다.");
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
        recurrence_rule: buildRule(form),
      };
      if (editingEvent) await api.updateEvent(editingEvent.id, payload, userId);
      else await api.createEvent({ calendar_id: form.calendarId, ...payload });
      setSelectedDateKey(form.date);
      const savedDate = dateFromKey(form.date);
      setVisibleDate(new Date(savedDate.getFullYear(), savedDate.getMonth(), 1));
      setFormOpen(false);
      await refreshEvents(visibleCalendarIds);
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
            await refreshEvents(visibleCalendarIds);
          });
        },
      },
    ]);
  }

  function toggleVisibleCalendar(calendarId: string) {
    setVisibleCalendarIds((current) => {
      if (current.includes(calendarId)) return current.filter((id) => id !== calendarId);
      return [...current, calendarId];
    });
  }

  function copyInviteCode(code: string) {
    void Clipboard.setStringAsync(code);
    Alert.alert("복사됨", "초대코드를 클립보드에 복사했습니다.");
  }

  if (booting) return <LoadingScreen />;
  if (!userId) return <SetupScreen displayName={displayName} setDisplayName={setDisplayName} password={password} setPassword={setPassword} createUser={createUser} signIn={signIn} loading={loading} />;

  if (!visibleCalendarIds.length) {
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
          <Text style={styles.sectionTitle}>표시할 달력이 없습니다.</Text>
          <Pressable style={styles.primaryButtonWide} onPress={() => setSettingsOpen(true)}>
            <Feather name="calendar" size={18} color="#fff" />
            <Text style={styles.primaryButtonText}>달력 추가/선택</Text>
          </Pressable>
        </View>
        {renderSettingsModal()}
        {loading ? <LoadingOverlay /> : null}
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar style="dark" />
      <View style={styles.screen}>
        <View style={styles.topBar}>
          <View>
            <Text style={styles.activeCalendarTitle}>{activeCalendars.map((calendar) => calendar.name).join(", ")}</Text>
            <Text style={styles.muted}>{activeCalendars.length > 1 ? `${activeCalendars.length}개 달력 표시 중` : "일정 달력"}</Text>
          </View>
          <View style={styles.topActions}>
            <Pressable style={styles.navButton} onPress={() => setSearchOpen(true)}>
              <Feather name="search" size={21} color="#0f172a" />
            </Pressable>
            <Pressable style={styles.navButton} onPress={() => setSettingsOpen(true)}>
              <Feather name="settings" size={22} color="#0f172a" />
            </Pressable>
          </View>
        </View>

        <View style={styles.monthBar}>
          <Pressable style={styles.navButton} onPress={() => movePeriod(-1)}>
            <Feather name="chevron-left" size={22} color="#0f172a" />
          </Pressable>
          <Pressable style={styles.monthTitleButton} onPress={openYearMonthPicker}>
            <Text style={styles.monthTitle}>{monthLabel(visibleDate)}</Text>
          </Pressable>
          <Pressable style={styles.navButton} onPress={() => movePeriod(1)}>
            <Feather name="chevron-right" size={22} color="#0f172a" />
          </Pressable>
        </View>

        <View style={styles.calendarWrap} {...swipeResponder.panHandlers}>
          <View style={styles.weekHeader}>
            {dayLabels.map((label, index) => (
              <Text key={label} style={[styles.weekLabel, index === 0 && styles.holidayText]}>
                {label}
              </Text>
            ))}
          </View>
          <View style={styles.grid}>
            {calendarDays.map((date) => {
              const key = toDateKey(date);
              const dayEvents = eventsByDate.get(key) || [];
              const name = holidayName(date);
              const isCurrentMonth = date.getMonth() === visibleDate.getMonth();
              const isSelected = key === selectedDateKey;
              const isToday = key === toDateKey(new Date());
              const eventLimit = 2;
              return (
                <Pressable
                  key={key}
                  style={[
                    styles.dayCell,
                    !isCurrentMonth && styles.outsideCell,
                    isToday && styles.todayCell,
                    isSelected && styles.selectedCell,
                  ]}
                  onPress={() => {
                    setSelectedDateKey(key);
                    setVisibleDate(new Date(date.getFullYear(), date.getMonth(), 1));
                  }}
                >
                  <Text style={[styles.dayNumber, isHoliday(date) && styles.holidayText, !isCurrentMonth && styles.outsideText]}>
                    {date.getDate()}
                  </Text>
                  {name ? <Text numberOfLines={1} style={styles.holidayName}>{name}</Text> : null}
                  {dayEvents.slice(0, eventLimit).map((event) => (
                    <Text key={event.occurrenceKey} numberOfLines={1} style={[styles.eventChip, { borderLeftColor: event.color }]}>
                      {event.title}
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
            <Pressable key={event.occurrenceKey} style={[styles.eventRow, { borderLeftColor: event.color }]} onPress={() => openEditForm(event)}>
              <Text style={styles.eventTitle}>{event.title}</Text>
              <Text style={styles.muted}>
                {timeFromIso(event.starts_at)} · {event.calendarName} · {ownerName(members, event.created_by)}
                {event.location ? ` · ${event.location}` : ""}
                {event.recurrence_rule ? " · 반복" : ""}
              </Text>
              {event.body ? <Text style={styles.eventBody}>{event.body}</Text> : null}
            </Pressable>
          ))}
        </ScrollView>
      </View>

      {renderSearchModal()}
      {renderYearMonthModal()}
      {renderEventModal()}
      {renderSettingsModal()}
      {loading ? <LoadingOverlay /> : null}
    </SafeAreaView>
  );

  function renderSearchModal() {
    return (
      <Modal visible={searchOpen} animationType="slide" transparent>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalPanel}>
            <View style={styles.modalContent}>
              <View style={styles.modalHeader}>
                <Text style={styles.sectionTitle}>일정 검색</Text>
                <Pressable style={styles.navButton} onPress={() => setSearchOpen(false)}>
                  <Feather name="x" size={22} color="#0f172a" />
                </Pressable>
              </View>
              <View style={styles.searchInputRow}>
                <TextInput
                  value={searchQuery}
                  onChangeText={setSearchQuery}
                  placeholder="예: 병원, 어린이날, 카드 식비"
                  returnKeyType="search"
                  onSubmitEditing={runSearch}
                  style={[styles.input, styles.searchInput]}
                />
                <Pressable style={styles.navButton} onPress={runSearch}>
                  <Feather name="search" size={20} color="#0f172a" />
                </Pressable>
              </View>
              <ScrollView style={styles.searchResults} contentContainerStyle={styles.searchResultContent}>
                {searchResults.length === 0 ? <Text style={styles.emptyText}>검색 결과가 없습니다.</Text> : null}
                {searchResults.map((event) => (
                  <Pressable key={`${event.id}:${event.starts_at}`} style={[styles.eventRow, { borderLeftColor: event.color }]} onPress={() => goToEventDate(event)}>
                    <Text style={styles.eventTitle}>{event.title}</Text>
                    <Text style={styles.muted}>
                      {toDateKey(new Date(event.starts_at))} · {timeFromIso(event.starts_at)} · {event.calendarName}
                    </Text>
                    {event.body ? <Text numberOfLines={2} style={styles.eventBody}>{event.body}</Text> : null}
                  </Pressable>
                ))}
              </ScrollView>
              <Text style={styles.muted}>검색 결과가 너무 많으면 설정에서 임계치를 낮춰 보세요.</Text>
            </View>
          </View>
        </View>
      </Modal>
    );
  }

  function renderYearMonthModal() {
    return (
      <Modal visible={yearMonthOpen} animationType="fade" transparent>
        <View style={styles.modalBackdrop}>
          <View style={styles.smallModalPanel}>
            <View style={styles.modalContent}>
              <View style={styles.modalHeader}>
                <Text style={styles.sectionTitle}>년월 선택</Text>
                <Pressable style={styles.navButton} onPress={() => setYearMonthOpen(false)}>
                  <Feather name="x" size={22} color="#0f172a" />
                </Pressable>
              </View>
              <View style={styles.twoColumnRow}>
                <TextInput value={yearInput} onChangeText={setYearInput} keyboardType="number-pad" placeholder="YYYY" style={[styles.input, styles.flexInput]} />
                <TextInput value={monthInput} onChangeText={setMonthInput} keyboardType="number-pad" placeholder="MM" style={[styles.input, styles.flexInput]} />
              </View>
              <Pressable style={styles.primaryButton} onPress={applyYearMonth}>
                <Text style={styles.primaryButtonText}>이동</Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>
    );
  }

  function renderEventModal() {
    return (
      <Modal visible={formOpen} animationType="slide" transparent>
        <View style={styles.modalBackdrop}>
          <ScrollView style={styles.modalPanel} contentContainerStyle={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text style={styles.sectionTitle}>{editingEvent ? "일정 수정" : "일정 추가"}</Text>
              <Pressable style={styles.navButton} onPress={() => setFormOpen(false)}>
                <Feather name="x" size={22} color="#0f172a" />
              </Pressable>
            </View>
            <Text style={styles.fieldLabel}>등록할 달력</Text>
            <Pressable style={styles.ownerSelect} onPress={() => setCalendarMenuOpen((current) => !current)}>
              <Text style={styles.ownerSelectText}>{calendars.find((calendar) => calendar.id === form.calendarId)?.name || "달력 선택"}</Text>
              <Feather name="chevron-down" size={18} color="#0f172a" />
            </Pressable>
            {calendarMenuOpen ? (
              <View style={styles.ownerMenu}>
                {calendars.map((calendar) => (
                  <Pressable
                    key={calendar.id}
                    style={[styles.ownerMenuItem, form.calendarId === calendar.id && styles.ownerMenuItemActive]}
                    onPress={() => {
                      setForm((current) => ({ ...current, calendarId: calendar.id, ownerId: userId }));
                      setSelectedCalendarId(calendar.id);
                      setCalendarMenuOpen(false);
                    }}
                  >
                    <Text style={[styles.ownerMenuText, form.calendarId === calendar.id && styles.ownerMenuTextActive]}>{calendar.name}</Text>
                  </Pressable>
                ))}
              </View>
            ) : null}
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

            <Pressable style={styles.detailToggle} onPress={() => setRepeatOpen((current) => !current)}>
              <Text style={styles.fieldLabel}>반복 상세</Text>
              <Feather name={repeatOpen ? "chevron-up" : "chevron-down"} size={18} color="#0f172a" />
            </Pressable>
            {repeatOpen ? renderRepeatControls() : null}

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
          </ScrollView>
        </View>
      </Modal>
    );
  }

  function renderRepeatControls() {
    return (
      <View style={styles.repeatBox}>
        <Pressable style={styles.checkRow} onPress={() => setForm((current) => ({ ...current, repeatEnabled: !current.repeatEnabled }))}>
          <Feather name={form.repeatEnabled ? "check-square" : "square"} size={20} color="#0f766e" />
          <Text style={styles.eventTitle}>반복 사용</Text>
        </Pressable>
        {form.repeatEnabled ? (
          <>
            <View style={styles.pillRow}>
              {(["daily", "weekly", "monthly", "yearly"] as RepeatType[]).map((type) => (
                <Pressable key={type} style={[styles.modePill, form.repeatType === type && styles.modePillActive]} onPress={() => setForm((current) => ({ ...current, repeatType: type }))}>
                  <Text style={[styles.modePillText, form.repeatType === type && styles.modePillTextActive]}>{type === "daily" ? "일" : type === "weekly" ? "주" : type === "monthly" ? "월" : "년"}</Text>
                </Pressable>
              ))}
            </View>
            {form.repeatType === "daily" ? <TextInput value={form.repeatInterval} onChangeText={(repeatInterval) => setForm((current) => ({ ...current, repeatInterval }))} placeholder="며칠마다" keyboardType="number-pad" style={styles.input} /> : null}
            {form.repeatType === "weekly" ? (
              <>
                <TextInput value={form.repeatInterval} onChangeText={(repeatInterval) => setForm((current) => ({ ...current, repeatInterval }))} placeholder="1~3주마다" keyboardType="number-pad" style={styles.input} />
                <View style={styles.pillRow}>{renderWeekdayPills()}</View>
              </>
            ) : null}
            {form.repeatType === "monthly" ? (
              <>
                <View style={styles.pillRow}>
                  <Pressable style={[styles.modePill, form.repeatMonthMode === "day" && styles.modePillActive]} onPress={() => setForm((current) => ({ ...current, repeatMonthMode: "day" }))}>
                    <Text style={[styles.modePillText, form.repeatMonthMode === "day" && styles.modePillTextActive]}>일자</Text>
                  </Pressable>
                  <Pressable style={[styles.modePill, form.repeatMonthMode === "weekday" && styles.modePillActive]} onPress={() => setForm((current) => ({ ...current, repeatMonthMode: "weekday" }))}>
                    <Text style={[styles.modePillText, form.repeatMonthMode === "weekday" && styles.modePillTextActive]}>몇번째 주 요일</Text>
                  </Pressable>
                </View>
                {form.repeatMonthMode === "day" ? <TextInput value={form.repeatMonthDay} onChangeText={(repeatMonthDay) => setForm((current) => ({ ...current, repeatMonthDay }))} placeholder="매월 몇 일" keyboardType="number-pad" style={styles.input} /> : null}
                {form.repeatMonthMode === "weekday" ? (
                  <>
                    <TextInput value={form.repeatWeekOfMonth} onChangeText={(repeatWeekOfMonth) => setForm((current) => ({ ...current, repeatWeekOfMonth }))} placeholder="몇번째 주 (1~5)" keyboardType="number-pad" style={styles.input} />
                    <View style={styles.pillRow}>{renderWeekdayPills()}</View>
                  </>
                ) : null}
              </>
            ) : null}
            {form.repeatType === "yearly" ? (
              <Pressable style={styles.checkRow} onPress={() => setForm((current) => ({ ...current, repeatLunar: !current.repeatLunar }))}>
                <Feather name={form.repeatLunar ? "check-square" : "square"} size={20} color="#0f766e" />
                <Text style={styles.eventTitle}>음력 일자로 저장</Text>
              </Pressable>
            ) : null}
          </>
        ) : null}
      </View>
    );
  }

  function renderWeekdayPills() {
    return dayLabels.map((label, index) => (
      <Pressable key={label} style={[styles.weekdayPill, form.repeatWeekday === String(index) && styles.modePillActive]} onPress={() => setForm((current) => ({ ...current, repeatWeekday: String(index) }))}>
        <Text style={[styles.modePillText, form.repeatWeekday === String(index) && styles.modePillTextActive]}>{label}</Text>
      </Pressable>
    ));
  }

  function renderSettingsModal() {
    return (
      <Modal visible={settingsOpen} animationType="slide" transparent>
        <View style={styles.modalBackdrop}>
          <ScrollView style={styles.modalPanel} contentContainerStyle={styles.modalContent}>
            <View style={styles.modalHeader}>
              <Text style={styles.sectionTitle}>설정</Text>
              <Pressable style={styles.navButton} onPress={() => setSettingsOpen(false)}>
                <Feather name="x" size={22} color="#0f172a" />
              </Pressable>
            </View>
            <Text style={styles.fieldLabel}>한 화면에 표시할 달력</Text>
            {calendars.map((calendar, index) => (
              <View key={calendar.id} style={[styles.calendarOption, visibleCalendarIds.includes(calendar.id) && styles.calendarOptionActive]}>
                <Pressable style={styles.calendarLine} onPress={() => toggleVisibleCalendar(calendar.id)}>
                  <Feather name={visibleCalendarIds.includes(calendar.id) ? "check-square" : "square"} size={20} color={calendarColors[index % calendarColors.length]} />
                  <Text style={styles.eventTitle}>{calendar.name}</Text>
                </Pressable>
                <Pressable onLongPress={() => copyInviteCode(calendar.invite_code)}>
                  <Text style={styles.muted}>초대 코드 {calendar.invite_code} · 길게 눌러 복사</Text>
                </Pressable>
                <Pressable onPress={() => setSelectedCalendarId(calendar.id)}>
                  <Text style={styles.textButtonLabel}>
                    {selectedCalendarId === calendar.id ? "기본 등록 달력" : "기본 등록 달력으로 선택"}
                  </Text>
                </Pressable>
              </View>
            ))}
            <Text style={styles.fieldLabel}>검색 임계치</Text>
            <TextInput value={searchMaxDistance} onChangeText={setSearchMaxDistance} placeholder="0.2" keyboardType="decimal-pad" style={styles.input} />
            <Text style={styles.muted}>낮을수록 더 비슷한 일정만 나옵니다. 로컬 임베딩은 보통 0.1~0.3 사이에서 조정하면 됩니다.</Text>
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
          </ScrollView>
        </View>
      </Modal>
    );
  }
}

function LoadingScreen() {
  return (
    <SafeAreaView style={styles.safe}>
      <View style={styles.centerPanel}>
        <ActivityIndicator />
      </View>
    </SafeAreaView>
  );
}

function LoadingOverlay() {
  return (
    <View style={styles.loadingOverlay}>
      <ActivityIndicator color="#fff" />
    </View>
  );
}

function SetupScreen({
  displayName,
  setDisplayName,
  password,
  setPassword,
  createUser,
  signIn,
  loading,
}: {
  displayName: string;
  setDisplayName: (value: string) => void;
  password: string;
  setPassword: (value: string) => void;
  createUser: () => void;
  signIn: () => void;
  loading: boolean;
}) {
  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar style="dark" />
      <View style={styles.setup}>
        <View style={styles.brandRow}>
          <Feather name="calendar" size={30} color="#0f766e" />
          <Text style={styles.appTitle}>Family Calendar</Text>
        </View>
        <TextInput value={displayName} onChangeText={setDisplayName} placeholder="사용자 이름" style={styles.input} />
        <TextInput value={password} onChangeText={setPassword} placeholder="비밀번호" secureTextEntry style={styles.input} />
        <Pressable style={styles.secondaryButton} onPress={signIn}>
          <Feather name="log-in" size={18} color="#0f172a" />
          <Text style={styles.secondaryButtonText}>기존 사용자 로그인</Text>
        </Pressable>
        <Pressable style={styles.primaryButton} onPress={createUser}>
          <Feather name="user-plus" size={18} color="#fff" />
          <Text style={styles.primaryButtonText}>새 사용자 등록</Text>
        </Pressable>
        <Text style={styles.muted}>API {apiBaseUrl}</Text>
        {loading ? <ActivityIndicator /> : null}
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: "#f7faf9", paddingTop: 14 },
  screen: { flex: 1, paddingHorizontal: 14, paddingBottom: 14, paddingTop: 8, gap: 10 },
  setup: { flex: 1, justifyContent: "center", padding: 22, gap: 12 },
  centerPanel: { flex: 1, alignItems: "center", justifyContent: "center", gap: 14, padding: 22 },
  topBarOnly: { padding: 14, flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  brandRow: { flexDirection: "row", alignItems: "center", gap: 10, marginBottom: 12 },
  topBar: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  topActions: { flexDirection: "row", alignItems: "center", gap: 8 },
  appTitle: { fontSize: 24, fontWeight: "700", color: "#0f172a" },
  activeCalendarTitle: { fontSize: 22, fontWeight: "800", color: "#0f172a", maxWidth: 260 },
  muted: { color: "#64748b", fontSize: 12 },
  input: { minHeight: 44, borderWidth: 1, borderColor: "#cbd5e1", borderRadius: 6, paddingHorizontal: 12, paddingVertical: 10, color: "#0f172a", backgroundColor: "#fff" },
  textArea: { minHeight: 82, textAlignVertical: "top" },
  primaryButton: { minHeight: 44, borderRadius: 6, backgroundColor: "#0f766e", alignItems: "center", justifyContent: "center", flexDirection: "row", gap: 8 },
  primaryButtonWide: { minHeight: 44, minWidth: 180, borderRadius: 6, backgroundColor: "#0f766e", alignItems: "center", justifyContent: "center", flexDirection: "row", gap: 8 },
  primaryButtonText: { color: "#fff", fontWeight: "700" },
  secondaryButton: { minHeight: 44, borderRadius: 6, backgroundColor: "#e2e8f0", alignItems: "center", justifyContent: "center", flexDirection: "row", gap: 8 },
  secondaryButtonText: { color: "#0f172a", fontWeight: "700" },
  dangerButton: { minHeight: 44, borderRadius: 6, backgroundColor: "#b91c1c", alignItems: "center", justifyContent: "center", flexDirection: "row", gap: 8 },
  monthBar: { flexDirection: "row", alignItems: "center", gap: 8 },
  monthTitleButton: { flex: 1, minHeight: 36, alignItems: "center", justifyContent: "center" },
  monthTitle: { textAlign: "center", fontSize: 18, fontWeight: "700", color: "#0f172a" },
  navButton: { width: 36, height: 36, borderRadius: 18, alignItems: "center", justifyContent: "center", backgroundColor: "#e2e8f0" },
  calendarWrap: { backgroundColor: "#fff", borderWidth: 1, borderColor: "#cbd5e1", borderRadius: 8, overflow: "hidden" },
  weekHeader: { flexDirection: "row", backgroundColor: "#f1f5f9", borderBottomWidth: 1, borderBottomColor: "#cbd5e1" },
  weekLabel: { width: "14.2857%", textAlign: "center", paddingVertical: 8, color: "#334155", fontWeight: "700" },
  grid: { flexDirection: "row", flexWrap: "wrap" },
  dayCell: { width: "14.2857%", height: 76, borderRightWidth: 1, borderBottomWidth: 1, borderColor: "#e2e8f0", padding: 5, gap: 2, backgroundColor: "#fff" },
  outsideCell: { backgroundColor: "#d8dee7" },
  todayCell: { backgroundColor: "#fef3c7" },
  selectedCell: { borderWidth: 2, borderColor: "#0f766e" },
  dayNumber: { color: "#0f172a", fontSize: 12, fontWeight: "700" },
  holidayText: { color: "#dc2626" },
  holidayName: { color: "#dc2626", fontSize: 9 },
  outsideText: { color: "#475569" },
  eventChip: { backgroundColor: "#e0f2fe", color: "#075985", borderRadius: 4, paddingHorizontal: 4, borderLeftWidth: 3, fontSize: 10 },
  moreText: { fontSize: 10, color: "#64748b" },
  listHeader: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", marginTop: 2 },
  sectionTitle: { fontSize: 17, fontWeight: "700", color: "#0f172a" },
  fieldLabel: { fontSize: 13, fontWeight: "700", color: "#334155" },
  textButton: { minHeight: 32, paddingHorizontal: 12, justifyContent: "center" },
  textButtonLabel: { color: "#0f766e", fontWeight: "700" },
  eventList: { flex: 1 },
  eventListContent: { gap: 8, paddingBottom: 28 },
  emptyText: { color: "#64748b", paddingVertical: 20, textAlign: "center" },
  eventRow: { borderWidth: 1, borderLeftWidth: 4, borderColor: "#e2e8f0", borderRadius: 8, backgroundColor: "#fff", padding: 10, gap: 3 },
  eventTitle: { color: "#0f172a", fontSize: 15, fontWeight: "700" },
  eventBody: { color: "#334155", lineHeight: 19 },
  modalBackdrop: { flex: 1, justifyContent: "flex-end", backgroundColor: "rgba(15, 23, 42, 0.34)" },
  modalPanel: { backgroundColor: "#f8fafc", borderTopLeftRadius: 8, borderTopRightRadius: 8, maxHeight: "92%" },
  smallModalPanel: { backgroundColor: "#f8fafc", borderTopLeftRadius: 8, borderTopRightRadius: 8 },
  modalContent: { padding: 16, gap: 10 },
  modalHeader: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  searchInputRow: { flexDirection: "row", alignItems: "center", gap: 8 },
  searchInput: { flex: 1 },
  searchResults: { maxHeight: 420 },
  searchResultContent: { gap: 8, paddingBottom: 12 },
  twoColumnRow: { flexDirection: "row", gap: 8 },
  flexInput: { flex: 1 },
  ownerSelect: { borderWidth: 1, borderColor: "#cbd5e1", borderRadius: 6, paddingHorizontal: 12, minHeight: 44, backgroundColor: "#fff", flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  ownerSelectText: { color: "#0f172a", fontWeight: "700" },
  ownerMenu: { borderWidth: 1, borderColor: "#cbd5e1", borderRadius: 6, backgroundColor: "#fff", overflow: "hidden" },
  ownerMenuItem: { minHeight: 40, justifyContent: "center", paddingHorizontal: 12, borderBottomWidth: 1, borderBottomColor: "#e2e8f0" },
  ownerMenuItemActive: { backgroundColor: "#0f766e" },
  ownerMenuText: { color: "#0f172a", fontWeight: "700" },
  ownerMenuTextActive: { color: "#fff" },
  detailToggle: { minHeight: 40, flexDirection: "row", alignItems: "center", justifyContent: "space-between" },
  repeatBox: { borderWidth: 1, borderColor: "#e2e8f0", borderRadius: 8, backgroundColor: "#fff", padding: 10, gap: 10 },
  checkRow: { minHeight: 36, flexDirection: "row", alignItems: "center", gap: 8 },
  pillRow: { flexDirection: "row", flexWrap: "wrap", gap: 6 },
  modePill: { minHeight: 32, borderRadius: 16, borderWidth: 1, borderColor: "#cbd5e1", backgroundColor: "#fff", paddingHorizontal: 12, alignItems: "center", justifyContent: "center" },
  weekdayPill: { width: 34, minHeight: 32, borderRadius: 16, borderWidth: 1, borderColor: "#cbd5e1", backgroundColor: "#fff", alignItems: "center", justifyContent: "center" },
  modePillActive: { backgroundColor: "#0f766e", borderColor: "#0f766e" },
  modePillText: { color: "#334155", fontWeight: "700" },
  modePillTextActive: { color: "#fff" },
  calendarOption: { borderWidth: 1, borderColor: "#e2e8f0", borderRadius: 8, backgroundColor: "#fff", padding: 10, gap: 6 },
  calendarOptionActive: { borderColor: "#0f766e", backgroundColor: "#ccfbf1" },
  calendarLine: { flexDirection: "row", alignItems: "center", gap: 8 },
  preferencePreview: { borderWidth: 1, borderColor: "#e2e8f0", borderRadius: 8, backgroundColor: "#fff", padding: 10, gap: 4 },
  loadingOverlay: { ...StyleSheet.absoluteFillObject, backgroundColor: "rgba(15, 23, 42, 0.26)", alignItems: "center", justifyContent: "center" },
});

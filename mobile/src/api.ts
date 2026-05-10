export type User = {
  id: string;
  display_name: string;
};

export type Calendar = {
  id: string;
  name: string;
  invite_code: string;
  role?: string;
};

export type RecurrenceRule = {
  frequency: "daily" | "weekly" | "monthly" | "yearly";
  interval?: number;
  weekdays?: number[];
  monthDay?: number;
  weekOfMonth?: number;
  lunar?: boolean;
};

export type EventItem = {
  id: string;
  calendar_id: string;
  created_by: string | null;
  title: string;
  body: string;
  location: string;
  starts_at: string;
  ends_at: string | null;
  recurrence_rule?: RecurrenceRule | null;
  source: "manual" | "sms_payment";
  merchant: string | null;
  amount: string | null;
  category: string | null;
};

export type SpendingRow = {
  month: string;
  category: string | null;
  total_amount: string;
  transaction_count: number;
};

type CreateEventPayload = {
  calendar_id: string;
  created_by: string;
  title: string;
  body: string;
  location: string;
  starts_at: string;
  ends_at?: string | null;
  recurrence_rule?: RecurrenceRule | null;
};

type UpdateEventPayload = {
  created_by: string;
  title: string;
  body: string;
  location: string;
  starts_at: string;
  ends_at?: string | null;
  recurrence_rule?: RecurrenceRule | null;
};

type PaymentSmsPayload = {
  calendar_id: string;
  created_by: string;
  raw_text: string;
  received_at: string;
};

export class ApiClient {
  constructor(private readonly baseUrl: string) {}

  async createUser(displayName: string, password: string): Promise<User> {
    return this.request("/users", {
      method: "POST",
      body: { display_name: displayName, password },
    });
  }

  async signIn(displayName: string, password: string): Promise<User> {
    return this.request("/auth/sign-in", {
      method: "POST",
      body: { display_name: displayName, password },
    });
  }

  async createCalendar(name: string, ownerUserId: string): Promise<Calendar> {
    return this.request("/calendars", {
      method: "POST",
      body: { name, owner_user_id: ownerUserId },
    });
  }

  async joinCalendar(inviteCode: string, userId: string): Promise<Calendar> {
    return this.request("/calendars/join", {
      method: "POST",
      body: { invite_code: inviteCode, user_id: userId },
    });
  }

  async listCalendars(userId: string): Promise<Calendar[]> {
    return this.request("/calendars", {
      headers: { "X-User-Id": userId },
    });
  }

  async listCalendarMembers(calendarId: string, userId: string): Promise<User[]> {
    return this.request(`/calendars/${encodeURIComponent(calendarId)}/members`, {
      headers: { "X-User-Id": userId },
    });
  }

  async createEvent(payload: CreateEventPayload): Promise<EventItem> {
    return this.request("/events", {
      method: "POST",
      body: payload,
    });
  }

  async updateEvent(eventId: string, payload: UpdateEventPayload, userId: string): Promise<EventItem> {
    return this.request(`/events/${encodeURIComponent(eventId)}`, {
      method: "PUT",
      headers: { "X-User-Id": userId },
      body: payload,
    });
  }

  async deleteEvent(eventId: string, userId: string): Promise<void> {
    await this.request(`/events/${encodeURIComponent(eventId)}`, {
      method: "DELETE",
      headers: { "X-User-Id": userId },
    });
  }

  async listEvents(calendarId: string, userId: string): Promise<EventItem[]> {
    return this.request(`/events?calendar_id=${encodeURIComponent(calendarId)}`, {
      headers: { "X-User-Id": userId },
    });
  }

  async searchEvents(calendarId: string, query: string, userId: string): Promise<EventItem[]> {
    return this.request("/events/search", {
      method: "POST",
      headers: { "X-User-Id": userId },
      body: { calendar_id: calendarId, query, limit: 20 },
    });
  }

  async ingestPaymentSms(payload: PaymentSmsPayload): Promise<EventItem> {
    return this.request("/sms/card-payments", {
      method: "POST",
      body: payload,
    });
  }

  async spendingReport(calendarId: string, userId: string): Promise<SpendingRow[]> {
    return this.request(`/reports/spending?calendar_id=${encodeURIComponent(calendarId)}`, {
      headers: { "X-User-Id": userId },
    });
  }

  private async request<T>(path: string, options: { method?: string; body?: unknown; headers?: Record<string, string> } = {}) {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method: options.method || "GET",
      headers: {
        "Content-Type": "application/json",
        ...options.headers,
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || `${response.status} ${response.statusText}`);
    }

    return (await response.json()) as T;
  }
}

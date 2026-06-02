from __future__ import annotations

import asyncio
from datetime import date, datetime, time, timedelta
import html
import json
import urllib.error
import urllib.request
from zoneinfo import ZoneInfo

from openai import OpenAI

from app.config import Settings
from app.db import get_conn


def parse_chat_ids(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def next_run_at(now: datetime, settings: Settings) -> datetime:
    hour, minute = parse_hhmm(settings.notification_time)
    run_at = datetime.combine(now.date(), time(hour, minute), tzinfo=now.tzinfo)
    if run_at <= now:
        run_at += timedelta(days=1)
    return run_at


async def notification_loop(settings: Settings) -> None:
    timezone = ZoneInfo(settings.notification_timezone)
    while True:
        now = datetime.now(timezone)
        run_at = next_run_at(now, settings)
        await asyncio.sleep((run_at - now).total_seconds())
        try:
            send_tomorrow_notification(settings, target_date=run_at.date() + timedelta(days=1))
        except Exception as error:
            print(f"notification failed: {error}")


def send_tomorrow_notification(settings: Settings, target_date: date | None = None, force: bool = False) -> dict:
    chat_ids = parse_chat_ids(settings.telegram_chat_ids)
    if not settings.telegram_bot_token or not chat_ids:
        raise ValueError("TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_IDS are required")

    timezone = ZoneInfo(settings.notification_timezone)
    target = target_date or (datetime.now(timezone).date() + timedelta(days=1))
    if not force and notification_already_sent(target):
        return {"status": "skipped", "reason": "already_sent", "date": target.isoformat()}

    events = tomorrow_events(target, timezone)
    message = summarize_events(settings, target, events)
    for chat_id in chat_ids:
        send_telegram_message(settings.telegram_bot_token, chat_id, message)
    mark_notification_sent(target)
    return {"status": "sent", "date": target.isoformat(), "events": len(events), "chats": len(chat_ids)}


def tomorrow_events(target: date, timezone: ZoneInfo) -> list[dict]:
    start = datetime.combine(target, time.min, tzinfo=timezone)
    end = start + timedelta(days=1)
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT e.id, e.title, e.body, e.location, e.starts_at, e.ends_at, e.recurrence_rule,
                   c.name AS calendar_name, u.display_name AS owner_name
            FROM events e
            JOIN calendars c ON c.id = e.calendar_id
            LEFT JOIN users u ON u.id = e.created_by
            WHERE e.recurrence_rule IS NOT NULL
               OR (e.starts_at < %s AND COALESCE(e.ends_at, e.starts_at) >= %s)
            ORDER BY e.starts_at ASC, e.title ASC
            """,
            (end, start),
        ).fetchall()
    return [normalize_event(row, target, timezone) for row in rows if occurs_on(row, target, timezone)]


def normalize_event(row: dict, target: date, timezone: ZoneInfo) -> dict:
    starts_at = row["starts_at"].astimezone(timezone)
    ends_at = row["ends_at"].astimezone(timezone) if row["ends_at"] else None
    all_day = starts_at.time() == time.min and (ends_at is None or ends_at.time() >= time(23, 59))
    return {
        "title": row["title"],
        "body": row["body"],
        "location": row["location"],
        "calendar_name": row["calendar_name"],
        "owner_name": row["owner_name"] or "모두",
        "starts_at": starts_at,
        "ends_at": ends_at,
        "all_day": all_day,
        "multi_day": (ends_at.date() if ends_at else starts_at.date()) != starts_at.date(),
        "target_date": target,
    }


def occurs_on(row: dict, target: date, timezone: ZoneInfo) -> bool:
    starts_at = row["starts_at"].astimezone(timezone)
    ends_at = row["ends_at"].astimezone(timezone) if row["ends_at"] else None
    start_date = starts_at.date()
    end_date = ends_at.date() if ends_at else None
    rule = row["recurrence_rule"]
    if not rule:
        return start_date <= target <= (end_date or start_date)
    if target < start_date:
        return False
    if isinstance(rule, str):
        rule = json.loads(rule)
    interval = max(int(rule.get("interval", 1)), 1)
    frequency = rule.get("frequency")
    if frequency == "daily":
        return (target - start_date).days % interval == 0
    if frequency == "weekly":
        weeks = (target - start_date).days // 7
        weekday = 0 if target.weekday() == 6 else target.weekday() + 1
        weekdays = rule.get("weekdays") or [0 if start_date.weekday() == 6 else start_date.weekday() + 1]
        return weeks % interval == 0 and weekday in weekdays
    if frequency == "monthly":
        months = (target.year - start_date.year) * 12 + target.month - start_date.month
        if months % interval != 0:
            return False
        if rule.get("mode") == "nthWeekday":
            weekday = 0 if target.weekday() == 6 else target.weekday() + 1
            week_of_month = ((target.day - 1) // 7) + 1
            return weekday == int(rule.get("weekday", 0 if start_date.weekday() == 6 else start_date.weekday() + 1)) and week_of_month == int(rule.get("weekOfMonth", ((start_date.day - 1) // 7) + 1))
        return target.day == int(rule.get("monthDay", start_date.day))
    if frequency == "yearly":
        years = target.year - start_date.year
        if years % interval != 0:
            return False
        if rule.get("lunar"):
            return False
        return target.month == start_date.month and target.day == start_date.day
    return False


def summarize_events(settings: Settings, target: date, events: list[dict]) -> str:
    fallback = build_html_summary(target, events)
    if not settings.notification_use_llm or not settings.openai_api_key or not events:
        return fallback
    try:
        client = OpenAI(api_key=settings.openai_api_key)
        response = client.chat.completions.create(
            model=settings.openai_summary_model,
            temperature=0.2,
            messages=[
                {
                    "role": "system",
                    "content": "가족 일정 알림을 한국어로 짧고 정확하게 요약한다. Telegram HTML 형식으로 작성하고, 허용 태그는 <b>, <i>, <u>, <s>, <code>, <pre>만 사용한다. 없는 내용은 만들지 않는다.",
                },
                {
                    "role": "user",
                    "content": f"날짜: {target.isoformat()}\n일정:\n{format_events_for_prompt(events)}\n\n텔레그램으로 보낼 HTML 요약을 작성해줘.",
                },
            ],
        )
        return response.choices[0].message.content or fallback
    except Exception as error:
        print(f"llm summary failed: {error}")
        return fallback


def build_fallback_summary(target: date, events: list[dict]) -> str:
    if not events:
        return f"{target.month}/{target.day} 내일 일정은 없습니다."
    lines = [f"{target.month}/{target.day} 내일 일정 {len(events)}개"]
    lines.extend(format_event_line(event) for event in events)
    return "\n".join(lines)


def build_html_summary(target: date, events: list[dict]) -> str:
    weekday = "월화수목금토일"[target.weekday()]
    if not events:
        return f"<b>{target.month}월 {target.day}일 {weekday}요일 내일 일정</b>\n\n등록된 일정이 없습니다."

    lines = [
        f"<b>{target.month}월 {target.day}일 {weekday}요일 내일 일정</b>",
        "",
        f"총 <b>{len(events)}개</b> 일정이 있습니다.",
        "",
    ]
    for index, event in enumerate(events):
        if index > 0:
            lines.append("")
        lines.extend(format_html_event(event))
    warnings = event_warnings(events)
    if warnings:
        lines.extend(["", "<b>확인 필요</b>"])
        lines.extend(f"- {html.escape(warning)}" for warning in warnings)
    return "\n".join(lines)


def format_html_event(event: dict) -> list[str]:
    lines = [
        f"<b>{html.escape(event_time_text(event))}</b>",
        f"<b>{html.escape(event['title'])}</b>",
        html.escape(f"{event['calendar_name']} · {event['owner_name']}"),
    ]
    if event["location"]:
        lines.append(f"장소: {html.escape(event['location'])}")
    if event["body"]:
        lines.append(f"메모: {html.escape(event['body'])}")
    return lines


def format_events_for_prompt(events: list[dict]) -> str:
    return "\n".join(format_event_line(event) for event in events)


def format_event_line(event: dict) -> str:
    time_text = event_time_text(event)
    parts = [f"- {time_text}", event["title"], f"({event['calendar_name']}, {event['owner_name']})"]
    if event["location"]:
        parts.append(f"장소: {event['location']}")
    if event["body"]:
        parts.append(event["body"])
    return " ".join(parts)


def event_time_text(event: dict) -> str:
    if event["all_day"]:
        return "종일"
    time_text = event["starts_at"].strftime("%H:%M")
    if event["ends_at"] and event["ends_at"].time() != event["starts_at"].time():
        time_text += f"~{event['ends_at'].strftime('%H:%M')}"
    return time_text


def event_warnings(events: list[dict]) -> list[str]:
    warnings = []
    for event in events:
        text = f"{event['title']} {event['body']}"
        registered_hour = event["starts_at"].hour
        if registered_hour >= 22 and any(marker in text for marker in ("2시", "두시", "오후 2시")):
            warnings.append(f"'{event['title']}' 일정은 등록 시간은 {event['starts_at'].strftime('%H:%M')}인데 제목/메모에는 2시가 있습니다.")
    return warnings


def send_telegram_message(token: str, chat_id: str, text: str) -> None:
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    payload = json.dumps({"chat_id": chat_id, "text": text, "parse_mode": "HTML"}).encode("utf-8")
    request = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            response.read()
    except urllib.error.HTTPError as error:
        raise RuntimeError(error.read().decode("utf-8", errors="replace")) from error


def notification_already_sent(target: date) -> bool:
    with get_conn() as conn:
        row = conn.execute(
            "SELECT 1 FROM notification_runs WHERE run_date = %s AND channel = 'telegram'",
            (target,),
        ).fetchone()
    return row is not None


def mark_notification_sent(target: date) -> None:
    with get_conn() as conn:
        conn.execute(
            """
            INSERT INTO notification_runs (run_date, channel)
            VALUES (%s, 'telegram')
            ON CONFLICT (run_date, channel) DO NOTHING
            """,
            (target,),
        )
        conn.commit()


def parse_hhmm(value: str) -> tuple[int, int]:
    hour_text, minute_text = value.split(":", 1)
    hour = int(hour_text)
    minute = int(minute_text)
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        raise ValueError("notification_time must be HH:MM")
    return hour, minute

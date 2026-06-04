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
        send_telegram_message(settings.telegram_bot_token, chat_id, message, settings.app_open_url)
    mark_notification_sent(target)
    return {"status": "sent", "date": target.isoformat(), "events": len(events), "chats": len(chat_ids)}


def run_tomorrow_telegram_automation(settings: Settings, target_date: date | None = None) -> dict:
    timezone = ZoneInfo(settings.notification_timezone)
    target = target_date or (datetime.now(timezone).date() + timedelta(days=1))
    events = tomorrow_events(target, timezone)
    message = build_table_summary(target, events)
    chat_ids = parse_chat_ids(settings.telegram_chat_ids)

    missing = []
    if not settings.telegram_bot_token:
        missing.append("TELEGRAM_BOT_TOKEN")
    if not chat_ids:
        missing.append("TELEGRAM_CHAT_IDS")
    if missing:
        return {
            "status": "skipped",
            "reason": "missing_config",
            "date": target.isoformat(),
            "events": len(events),
            "message": message,
            "missing": missing,
        }

    if notification_already_sent(target):
        return {
            "status": "skipped",
            "reason": "already_sent",
            "date": target.isoformat(),
            "events": len(events),
            "message": message,
        }

    for chat_id in chat_ids:
        send_telegram_message(settings.telegram_bot_token, chat_id, message, settings.app_open_url)
    mark_notification_sent(target)
    return {
        "status": "sent",
        "date": target.isoformat(),
        "events": len(events),
        "message": message,
        "chats": len(chat_ids),
    }


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
    if is_utc_all_day(row):
        starts_at = datetime.combine(row["starts_at"].date(), time.min, tzinfo=timezone)
        ends_at = datetime.combine(row["starts_at"].date(), time(23, 59), tzinfo=timezone)
        all_day = True
    else:
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
    if is_utc_all_day(row):
        starts_at = datetime.combine(row["starts_at"].date(), time.min, tzinfo=timezone)
        ends_at = datetime.combine(row["starts_at"].date(), time(23, 59), tzinfo=timezone)
    else:
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


def is_utc_all_day(row: dict) -> bool:
    starts_at = row["starts_at"]
    ends_at = row["ends_at"]
    return (
        ends_at is not None
        and starts_at.hour == 0
        and starts_at.minute == 0
        and starts_at.second == 0
        and starts_at.date() == ends_at.date()
        and ends_at.hour == 23
        and ends_at.minute == 59
    )


def summarize_events(settings: Settings, target: date, events: list[dict]) -> str:
    fallback = build_table_summary(target, events)
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


def build_table_summary(target: date, events: list[dict]) -> str:
    weekday = "월화수목금토일"[target.weekday()]
    header = f"<b>{target.month}월 {target.day}일 {weekday}요일 내일 일정</b>"
    if not events:
        return f"{header}\n\n등록된 일정이 없습니다."

    lines = [
        header,
        f"총 <b>{len(events)}개</b> 일정이 있습니다.",
        "",
        "<pre>",
    ]
    for index, event in enumerate(events):
        if index > 0:
            lines.append("")
        lines.extend(table_event_block(event))
    lines.append("</pre>")

    warnings = event_warnings(events)
    if warnings:
        lines.extend(["", "<b>확인 필요</b>"])
        lines.extend(f"- {html.escape(warning)}" for warning in warnings)
    return "\n".join(lines)


def table_event_block(event: dict) -> list[str]:
    meta = f"{event['calendar_name']} · {event['owner_name']}"
    first_line = f"{clip_text(event_time_text(event), 12):<12} {clip_text(event['title'], 18)}"
    lines = [
        html.escape(pad_pre_width(first_line)),
        html.escape(f"{'':<8} {clip_text(meta, 22)}"),
    ]
    if event["location"]:
        lines.append(html.escape(f"{'':<8} 장소: {clip_text(event['location'], 22)}"))
    if event["body"]:
        lines.append(html.escape(f"{'':<8} 메모: {clip_text(event['body'], 22)}"))
    return lines


def pad_pre_width(value: str, min_chars: int = 38) -> str:
    return value + ("\u2800" * max(0, min_chars - len(value)))


def clip_text(value: str, limit: int) -> str:
    value = " ".join(str(value).split())
    if len(value) <= limit:
        return value
    return value[: max(1, limit - 1)] + "…"


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


def build_notification_card(target: date, events: list[dict]) -> bytes:
    from io import BytesIO

    from PIL import Image, ImageDraw, ImageFont

    width = 1080
    margin = 56
    card_gap = 22
    weekday = "월화수목금토일"[target.weekday()]
    fonts = notification_fonts(ImageFont)
    rows = []
    for event in events:
        title_lines = wrap_text(event["title"], fonts["title"], width - 300)
        detail_parts = [f"{event['calendar_name']} · {event['owner_name']}"]
        if event["location"]:
            detail_parts.append(f"장소: {event['location']}")
        if event["body"]:
            detail_parts.append(f"메모: {event['body']}")
        detail_lines = []
        for detail in detail_parts:
            detail_lines.extend(wrap_text(detail, fonts["detail"], width - 300))
        height = 110 + (len(title_lines) - 1) * 34 + len(detail_lines) * 29
        rows.append((event, title_lines, detail_lines, max(150, height)))

    warnings = event_warnings(events)
    warning_lines = []
    for warning in warnings:
        warning_lines.extend(wrap_text(warning, fonts["detail"], width - margin * 2 - 54))

    height = 210 + sum(row[3] + card_gap for row in rows)
    if not rows:
        height += 180
    if warning_lines:
        height += 72 + len(warning_lines) * 32
    height += 50

    image = Image.new("RGB", (width, height), "#F5F8F7")
    draw = ImageDraw.Draw(image)

    draw.text((margin, 48), "내일 일정", fill="#0F766E", font=fonts["eyebrow"])
    draw.text((margin, 84), f"{target.month}월 {target.day}일 {weekday}요일", fill="#0F172A", font=fonts["header"])
    count_text = "등록된 일정이 없습니다." if not events else f"총 {len(events)}개 일정이 있습니다."
    draw.text((margin, 145), count_text, fill="#64748B", font=fonts["body"])

    y = 210
    if not rows:
        draw_round_rect(draw, (margin, y, width - margin, y + 150), 28, "#FFFFFF", "#DDE7E3")
        draw.text((margin + 34, y + 50), "내일은 비어 있습니다.", fill="#334155", font=fonts["title"])
        draw.text((margin + 34, y + 92), "가볍게 넘어가도 되는 날입니다.", fill="#64748B", font=fonts["detail"])
        y += 174

    for event, title_lines, detail_lines, row_height in rows:
        x1, y1, x2, y2 = margin, y, width - margin, y + row_height
        owner_color = owner_accent(event["owner_name"])
        draw_round_rect(draw, (x1, y1, x2, y2), 28, "#FFFFFF", "#DDE7E3")
        draw.rounded_rectangle((x1, y1, x1 + 14, y2), radius=7, fill=owner_color)
        draw.rounded_rectangle((x1 + 34, y1 + 30, x1 + 158, y1 + 72), radius=21, fill="#ECFDF5")
        draw.text((x1 + 53, y1 + 38), event_time_text(event), fill="#047857", font=fonts["time"])

        text_x = x1 + 190
        text_y = y1 + 26
        for line in title_lines:
            draw.text((text_x, text_y), line, fill="#111827", font=fonts["title"])
            text_y += 36
        text_y += 6
        for line in detail_lines:
            draw.text((text_x, text_y), line, fill="#64748B", font=fonts["detail"])
            text_y += 30
        y += row_height + card_gap

    if warning_lines:
        box_height = 68 + len(warning_lines) * 32
        draw_round_rect(draw, (margin, y, width - margin, y + box_height), 24, "#FFF7ED", "#FDBA74")
        draw.text((margin + 28, y + 24), "확인 필요", fill="#9A3412", font=fonts["warning"])
        line_y = y + 68
        for line in warning_lines:
            draw.text((margin + 28, line_y), line, fill="#9A3412", font=fonts["detail"])
            line_y += 32

    output = BytesIO()
    image.save(output, format="PNG", optimize=True)
    return output.getvalue()


def notification_fonts(image_font):
    regular_path = find_font_path(False)
    bold_path = find_font_path(True) or regular_path
    return {
        "eyebrow": image_font.truetype(bold_path, 30),
        "header": image_font.truetype(bold_path, 54),
        "body": image_font.truetype(regular_path, 31),
        "time": image_font.truetype(bold_path, 24),
        "title": image_font.truetype(bold_path, 34),
        "detail": image_font.truetype(regular_path, 26),
        "warning": image_font.truetype(bold_path, 30),
    }


def find_font_path(bold: bool) -> str:
    from pathlib import Path

    candidates = [
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc" if bold else "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/opentype/noto/NotoSansCJK-Bold.ttc" if bold else "/usr/share/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc" if bold else "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "C:/Windows/Fonts/malgunbd.ttf" if bold else "C:/Windows/Fonts/malgun.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return candidate
    raise FileNotFoundError("No Korean font found")


def wrap_text(text: str, font, max_width: int) -> list[str]:
    if not text:
        return []
    lines = []
    current = ""
    for char in text:
        candidate = current + char
        if font.getlength(candidate) <= max_width or not current:
            current = candidate
        else:
            lines.append(current)
            current = char
    if current:
        lines.append(current)
    return lines


def draw_round_rect(draw, box, radius: int, fill: str, outline: str) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=2)


def owner_accent(owner_name: str) -> str:
    palette = ["#2563EB", "#DB2777", "#7C3AED", "#EA580C", "#0891B2", "#16A34A"]
    return palette[sum(ord(char) for char in owner_name) % len(palette)]


def plain_caption(target: date, events: list[dict]) -> str:
    weekday = "월화수목금토일"[target.weekday()]
    return f"{target.month}월 {target.day}일 {weekday}요일 내일 일정 · {len(events)}개"


def send_telegram_photo(token: str, chat_id: str, image: bytes, caption: str) -> None:
    boundary = f"----calendar-card-{datetime.now().timestamp()}".replace(".", "")
    fields = {
        "chat_id": chat_id,
        "caption": caption,
    }
    body = bytearray()
    for name, value in fields.items():
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8"))
        body.extend(str(value).encode("utf-8"))
        body.extend(b"\r\n")
    body.extend(f"--{boundary}\r\n".encode("utf-8"))
    body.extend(b'Content-Disposition: form-data; name="photo"; filename="tomorrow.png"\r\n')
    body.extend(b"Content-Type: image/png\r\n\r\n")
    body.extend(image)
    body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode("utf-8"))

    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendPhoto",
        data=bytes(body),
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            response.read()
    except urllib.error.HTTPError as error:
        raise RuntimeError(error.read().decode("utf-8", errors="replace")) from error


def send_telegram_message(token: str, chat_id: str, text: str, app_open_url: str | None = None) -> None:
    url = f"https://api.telegram.org/bot{token}/sendMessage"
    data = {"chat_id": chat_id, "text": text, "parse_mode": "HTML"}
    if app_open_url:
        data["reply_markup"] = {
            "inline_keyboard": [[{"text": "앱 열기", "url": app_open_url}]],
        }
    payload = json.dumps(data).encode("utf-8")
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

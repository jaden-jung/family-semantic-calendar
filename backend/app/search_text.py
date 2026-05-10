from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Any


WEEKDAYS_KO = ["월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"]


def format_dt(value: datetime) -> tuple[str, str, str]:
    weekday = WEEKDAYS_KO[value.weekday()]
    date_text = f"{value.year}년 {value.month}월 {value.day}일 {weekday}"
    month_text = f"{value.year}년 {value.month}월"
    time_text = value.strftime("%H:%M")
    return date_text, month_text, time_text


def recurrence_text(rule: dict[str, Any] | None) -> str:
    if not rule:
        return "없음"
    frequency = rule.get("frequency")
    interval = rule.get("interval", 1)
    if frequency == "daily":
        return f"{interval}일마다"
    if frequency == "weekly":
        weekdays = ", ".join(WEEKDAYS_KO[index % 7] for index in rule.get("weekdays", []))
        return f"{interval}주마다 {weekdays}".strip()
    if frequency == "monthly":
        if rule.get("weekOfMonth"):
            weekdays = ", ".join(WEEKDAYS_KO[index % 7] for index in rule.get("weekdays", []))
            return f"매월 {rule.get('weekOfMonth')}번째 주 {weekdays}".strip()
        return f"매월 {rule.get('monthDay')}일"
    if frequency == "yearly":
        return "매년 음력 일자" if rule.get("lunar") else "매년"
    return str(rule)


def build_event_embedding_text(
    *,
    title: str,
    body: str,
    location: str,
    starts_at: datetime,
    ends_at: datetime | None = None,
    recurrence_rule: dict[str, Any] | None = None,
    source: str = "manual",
    merchant: str | None = None,
    amount: Decimal | None = None,
    category: str | None = None,
    raw_text: str | None = None,
) -> str:
    date_text, month_text, time_text = format_dt(starts_at)
    lines = [
        f"제목: {title}",
        f"내용: {body}" if body else "",
        f"장소: {location}" if location else "",
        f"일정일: {date_text}",
        f"월: {month_text}",
        f"시간: {time_text}",
        f"유형: {'카드 결제' if source == 'sms_payment' else '일반 일정'}",
        f"반복: {recurrence_text(recurrence_rule)}",
    ]
    if ends_at:
        lines.append(f"종료시간: {ends_at.strftime('%H:%M')}")
    if merchant:
        lines.append(f"가맹점: {merchant}")
    if amount is not None:
        lines.append(f"금액: {amount}원")
    if category:
        lines.append(f"카테고리: {category}")
    if raw_text:
        lines.append(f"원문: {raw_text}")
    return "\n".join(line for line in lines if line)

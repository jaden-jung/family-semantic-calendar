from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field


class UserCreate(BaseModel):
    display_name: str = Field(min_length=1, max_length=80)
    password: str = Field(min_length=1, max_length=200)


class UserSignIn(BaseModel):
    display_name: str = Field(min_length=1, max_length=80)
    password: str = Field(min_length=1, max_length=200)


class UserOut(BaseModel):
    id: UUID
    display_name: str


class CalendarCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    owner_user_id: UUID
    calendar_type: str = Field(default="schedule", pattern="^(schedule|ledger)$")


class CalendarJoin(BaseModel):
    invite_code: str
    user_id: UUID


class CalendarOut(BaseModel):
    id: UUID
    name: str
    calendar_type: str = "schedule"
    invite_code: str
    role: str | None = None


class EventCreate(BaseModel):
    calendar_id: UUID
    created_by: UUID
    title: str = Field(min_length=1, max_length=200)
    body: str = ""
    location: str = ""
    starts_at: datetime
    ends_at: datetime | None = None
    recurrence_rule: dict[str, Any] | None = None
    merchant: str | None = None
    amount: Decimal | None = None
    category: str | None = None
    payment_method: str | None = None


class EventUpdate(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    body: str = ""
    location: str = ""
    starts_at: datetime
    ends_at: datetime | None = None
    created_by: UUID
    recurrence_rule: dict[str, Any] | None = None
    merchant: str | None = None
    amount: Decimal | None = None
    category: str | None = None
    payment_method: str | None = None


class PaymentSmsCreate(BaseModel):
    calendar_id: UUID
    created_by: UUID
    raw_text: str = Field(min_length=1)
    received_at: datetime


class EventOut(BaseModel):
    id: UUID
    calendar_id: UUID
    created_by: UUID | None
    title: str
    body: str
    location: str
    starts_at: datetime
    ends_at: datetime | None
    recurrence_rule: dict[str, Any] | None = None
    source: str
    merchant: str | None
    amount: Decimal | None
    category: str | None
    payment_method: str | None = None


class SmsPatternCreate(BaseModel):
    calendar_id: UUID
    sender_phone: str = Field(min_length=1, max_length=40)
    sample_message: str = ""
    amount_marker: str = ""
    merchant_marker: str = ""
    datetime_marker: str = ""


class SmsPatternOut(BaseModel):
    id: UUID
    calendar_id: UUID
    sender_phone: str
    sample_message: str
    amount_marker: str
    merchant_marker: str
    datetime_marker: str


class SearchQuery(BaseModel):
    calendar_id: UUID
    query: str = Field(min_length=1)
    limit: int = Field(default=10, ge=1, le=50)

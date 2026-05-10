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


class CalendarJoin(BaseModel):
    invite_code: str
    user_id: UUID


class CalendarOut(BaseModel):
    id: UUID
    name: str
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


class EventUpdate(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    body: str = ""
    location: str = ""
    starts_at: datetime
    ends_at: datetime | None = None
    created_by: UUID
    recurrence_rule: dict[str, Any] | None = None


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


class SearchQuery(BaseModel):
    calendar_id: UUID
    query: str = Field(min_length=1)
    limit: int = Field(default=10, ge=1, le=50)

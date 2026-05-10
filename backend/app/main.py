from __future__ import annotations

from contextlib import asynccontextmanager
import json

from fastapi import Depends, FastAPI, Header, HTTPException
from typing_extensions import Annotated

from app.config import Settings, get_settings
from app.db import close_pool, get_conn, init_db, open_pool
from app.embeddings import EmbeddingProvider, get_embedding_provider
from app.payments import parse_card_sms
from app.search_text import build_event_embedding_text, build_query_embedding_text
from app.schemas import (
    CalendarCreate,
    CalendarJoin,
    CalendarOut,
    EventCreate,
    EventOut,
    EventUpdate,
    PaymentSmsCreate,
    SearchQuery,
    UserCreate,
    UserOut,
    UserSignIn,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    open_pool()
    init_db()
    yield
    close_pool()


app = FastAPI(title="Family Semantic Calendar API", lifespan=lifespan)


def embedding_provider(settings: Annotated[Settings, Depends(get_settings)]) -> EmbeddingProvider:
    return get_embedding_provider(settings)


def vector_literal(values: list[float]) -> str:
    return "[" + ",".join(f"{value:.8f}" for value in values) + "]"


def event_embedding_text_from_payload(payload: EventCreate | EventUpdate) -> str:
    return build_event_embedding_text(
        title=payload.title,
        body=payload.body,
        location=payload.location,
        starts_at=payload.starts_at,
        ends_at=payload.ends_at,
        recurrence_rule=payload.recurrence_rule,
    )


def assert_member(calendar_id, user_id) -> None:
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT 1 FROM calendar_members
            WHERE calendar_id = %s AND user_id = %s
            """,
            (calendar_id, user_id),
        ).fetchone()
    if not row:
        raise HTTPException(status_code=403, detail="User is not a member of this calendar")


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/users", response_model=UserOut)
def create_user(payload: UserCreate):
    with get_conn() as conn:
        existing = conn.execute(
            "SELECT id, display_name, password_hash FROM users WHERE lower(display_name) = lower(%s) ORDER BY created_at DESC LIMIT 1",
            (payload.display_name,),
        ).fetchone()
        if existing:
            if existing["password_hash"] is None:
                row = conn.execute(
                    """
                    UPDATE users
                    SET password_hash = crypt(%s, gen_salt('bf'))
                    WHERE id = %s
                    RETURNING id, display_name
                    """,
                    (payload.password, existing["id"]),
                ).fetchone()
                conn.commit()
                return row
            raise HTTPException(status_code=409, detail="Display name already exists")
        row = conn.execute(
            """
            INSERT INTO users (display_name, password_hash)
            VALUES (%s, crypt(%s, gen_salt('bf')))
            RETURNING id, display_name
            """,
            (payload.display_name, payload.password),
        ).fetchone()
        conn.commit()
        return row


@app.post("/auth/sign-in", response_model=UserOut)
def sign_in(payload: UserSignIn):
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT id, display_name
            FROM users
            WHERE lower(display_name) = lower(%s)
              AND password_hash = crypt(%s, password_hash)
            ORDER BY created_at DESC
            LIMIT 1
            """,
            (payload.display_name, payload.password),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=401, detail="Invalid display name or password")
        return row


@app.post("/calendars", response_model=CalendarOut)
def create_calendar(payload: CalendarCreate):
    with get_conn() as conn:
        user = conn.execute("SELECT 1 FROM users WHERE id = %s", (payload.owner_user_id,)).fetchone()
        if not user:
            raise HTTPException(status_code=404, detail="Owner user not found")
        calendar = conn.execute(
            """
            INSERT INTO calendars (name, owner_user_id)
            VALUES (%s, %s)
            RETURNING id, name, invite_code
            """,
            (payload.name, payload.owner_user_id),
        ).fetchone()
        conn.execute(
            """
            INSERT INTO calendar_members (calendar_id, user_id, role)
            VALUES (%s, %s, 'owner')
            """,
            (calendar["id"], payload.owner_user_id),
        )
        conn.commit()
        return {**calendar, "role": "owner"}


@app.post("/calendars/join", response_model=CalendarOut)
def join_calendar(payload: CalendarJoin):
    with get_conn() as conn:
        calendar = conn.execute(
            "SELECT id, name, invite_code FROM calendars WHERE invite_code = %s",
            (payload.invite_code,),
        ).fetchone()
        if not calendar:
            raise HTTPException(status_code=404, detail="Invite code not found")
        conn.execute(
            """
            INSERT INTO calendar_members (calendar_id, user_id, role)
            VALUES (%s, %s, 'member')
            ON CONFLICT (calendar_id, user_id) DO NOTHING
            """,
            (calendar["id"], payload.user_id),
        )
        conn.commit()
        return {**calendar, "role": "member"}


@app.get("/calendars", response_model=list[CalendarOut])
def list_calendars(x_user_id: Annotated[str, Header(alias="X-User-Id")]):
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT c.id, c.name, c.invite_code, cm.role
            FROM calendars c
            JOIN calendar_members cm ON cm.calendar_id = c.id
            WHERE cm.user_id = %s
            ORDER BY c.created_at DESC
            """,
            (x_user_id,),
        ).fetchall()


@app.get("/calendars/{calendar_id}/members", response_model=list[UserOut])
def list_calendar_members(calendar_id: str, x_user_id: Annotated[str, Header(alias="X-User-Id")]):
    assert_member(calendar_id, x_user_id)
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT u.id, u.display_name
            FROM users u
            JOIN calendar_members cm ON cm.user_id = u.id
            WHERE cm.calendar_id = %s
            ORDER BY cm.created_at ASC
            """,
            (calendar_id,),
        ).fetchall()


@app.post("/events", response_model=EventOut)
def create_event(payload: EventCreate, provider: Annotated[EmbeddingProvider, Depends(embedding_provider)]):
    assert_member(payload.calendar_id, payload.created_by)
    searchable_text = event_embedding_text_from_payload(payload)
    embedding = vector_literal(provider.embed(searchable_text))
    with get_conn() as conn:
        row = conn.execute(
            """
            INSERT INTO events (
                calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, embedding
            )
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s::jsonb, %s::vector)
            RETURNING id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source, merchant, amount, category
            """,
            (
                payload.calendar_id,
                payload.created_by,
                payload.title,
                payload.body,
                payload.location,
                payload.starts_at,
                payload.ends_at,
                json.dumps(payload.recurrence_rule) if payload.recurrence_rule else None,
                embedding,
            ),
        ).fetchone()
        conn.commit()
        return row


@app.put("/events/{event_id}", response_model=EventOut)
def update_event(
    event_id: str,
    payload: EventUpdate,
    x_user_id: Annotated[str, Header(alias="X-User-Id")],
    provider: Annotated[EmbeddingProvider, Depends(embedding_provider)],
):
    with get_conn() as conn:
        existing = conn.execute(
            "SELECT calendar_id FROM events WHERE id = %s",
            (event_id,),
        ).fetchone()
    if not existing:
        raise HTTPException(status_code=404, detail="Event not found")
    assert_member(existing["calendar_id"], x_user_id)
    assert_member(existing["calendar_id"], payload.created_by)

    searchable_text = event_embedding_text_from_payload(payload)
    embedding = vector_literal(provider.embed(searchable_text))
    with get_conn() as conn:
        row = conn.execute(
            """
            UPDATE events
            SET created_by = %s,
                title = %s,
                body = %s,
                location = %s,
                starts_at = %s,
                ends_at = %s,
                recurrence_rule = %s::jsonb,
                embedding = %s::vector,
                updated_at = now()
            WHERE id = %s
            RETURNING id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source, merchant, amount, category
            """,
            (
                payload.created_by,
                payload.title,
                payload.body,
                payload.location,
                payload.starts_at,
                payload.ends_at,
                json.dumps(payload.recurrence_rule) if payload.recurrence_rule else None,
                embedding,
                event_id,
            ),
        ).fetchone()
        conn.commit()
        return row


@app.delete("/events/{event_id}")
def delete_event(event_id: str, x_user_id: Annotated[str, Header(alias="X-User-Id")]):
    with get_conn() as conn:
        existing = conn.execute(
            "SELECT calendar_id FROM events WHERE id = %s",
            (event_id,),
        ).fetchone()
        if not existing:
            raise HTTPException(status_code=404, detail="Event not found")
        assert_member(existing["calendar_id"], x_user_id)
        conn.execute("DELETE FROM events WHERE id = %s", (event_id,))
        conn.commit()
        return {"status": "deleted"}


@app.get("/events", response_model=list[EventOut])
def list_events(calendar_id: str, x_user_id: Annotated[str, Header(alias="X-User-Id")]):
    assert_member(calendar_id, x_user_id)
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source, merchant, amount, category
            FROM events
            WHERE calendar_id = %s
            ORDER BY starts_at DESC
            LIMIT 200
            """,
            (calendar_id,),
        ).fetchall()


@app.post("/events/search", response_model=list[EventOut])
def search_events(
    payload: SearchQuery,
    provider: Annotated[EmbeddingProvider, Depends(embedding_provider)],
    x_user_id: Annotated[str, Header(alias="X-User-Id")],
):
    assert_member(payload.calendar_id, x_user_id)
    query_embedding = vector_literal(provider.embed(build_query_embedding_text(payload.query)))
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source, merchant, amount, category
            FROM events
            WHERE calendar_id = %s
            ORDER BY embedding <=> %s::vector
            LIMIT %s
            """,
            (payload.calendar_id, query_embedding, payload.limit),
        ).fetchall()


@app.post("/sms/card-payments", response_model=EventOut)
def ingest_card_payment_sms(
    payload: PaymentSmsCreate,
    provider: Annotated[EmbeddingProvider, Depends(embedding_provider)],
):
    assert_member(payload.calendar_id, payload.created_by)
    parsed = parse_card_sms(payload.raw_text, payload.received_at)
    searchable_text = build_event_embedding_text(
        title=parsed["title"],
        body=parsed["body"],
        location="",
        starts_at=parsed["starts_at"],
        source="sms_payment",
        merchant=parsed["merchant"],
        amount=parsed["amount"],
        category=parsed["category"],
        raw_text=payload.raw_text,
    )
    embedding = vector_literal(provider.embed(searchable_text))
    with get_conn() as conn:
        row = conn.execute(
            """
            INSERT INTO events (
                calendar_id, created_by, title, body, starts_at, source,
                merchant, amount, category, raw_text, embedding
            )
            VALUES (%s, %s, %s, %s, %s, 'sms_payment', %s, %s, %s, %s, %s::vector)
            RETURNING id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source, merchant, amount, category
            """,
            (
                payload.calendar_id,
                payload.created_by,
                parsed["title"],
                parsed["body"],
                parsed["starts_at"],
                parsed["merchant"],
                parsed["amount"],
                parsed["category"],
                payload.raw_text,
                embedding,
            ),
        ).fetchone()
        conn.commit()
        return row


@app.get("/reports/spending")
def spending_report(calendar_id: str, x_user_id: Annotated[str, Header(alias="X-User-Id")]):
    assert_member(calendar_id, x_user_id)
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT
                date_trunc('month', starts_at)::date AS month,
                category,
                sum(amount) AS total_amount,
                count(*) AS transaction_count
            FROM events
            WHERE calendar_id = %s
              AND source = 'sms_payment'
              AND amount IS NOT NULL
            GROUP BY month, category
            ORDER BY month DESC, total_amount DESC
            """,
            (calendar_id,),
        ).fetchall()


@app.post("/admin/reembed")
def reembed_all_events(provider: Annotated[EmbeddingProvider, Depends(embedding_provider)]):
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT id, title, body, location, starts_at, ends_at, recurrence_rule, source,
                   merchant, amount, category, raw_text
            FROM events
            ORDER BY created_at ASC
            """
        ).fetchall()
        for row in rows:
            text = build_event_embedding_text(
                title=row["title"],
                body=row["body"],
                location=row["location"],
                starts_at=row["starts_at"],
                ends_at=row["ends_at"],
                recurrence_rule=row["recurrence_rule"],
                source=row["source"],
                merchant=row["merchant"],
                amount=row["amount"],
                category=row["category"],
                raw_text=row["raw_text"],
            )
            conn.execute(
                "UPDATE events SET embedding = %s::vector, updated_at = now() WHERE id = %s",
                (vector_literal(provider.embed(text)), row["id"]),
            )
        conn.commit()
        return {"status": "ok", "updated": len(rows)}

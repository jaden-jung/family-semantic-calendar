from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
import hashlib
import json
import secrets

from fastapi import Depends, FastAPI, Header, HTTPException
from typing_extensions import Annotated

from app.config import Settings, get_settings
from app.db import close_pool, get_conn, init_db, open_pool
from app.embeddings import EmbeddingProvider, get_embedding_provider
from app.search_text import build_event_embedding_text, build_query_embedding_text
from app.schemas import (
    AuthOut,
    CalendarCreate,
    CalendarJoin,
    CalendarOut,
    EventCreate,
    EventOut,
    SearchEventOut,
    EventUpdate,
    PasswordChange,
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
SESSION_TTL_DAYS = 90


def embedding_provider(settings: Annotated[Settings, Depends(get_settings)]) -> EmbeddingProvider:
    return get_embedding_provider(settings)


def vector_literal(values: list[float]) -> str:
    return "[" + ",".join(f"{value:.8f}" for value in values) + "]"


def normalize_display_name(value: str) -> str:
    return " ".join(value.split()).strip()


def compact_display_name(value: str) -> str:
    return "".join(value.split()).casefold()


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def create_session(conn, user_id) -> tuple[str, datetime]:
    token = secrets.token_urlsafe(32)
    expires_at = datetime.now(UTC) + timedelta(days=SESSION_TTL_DAYS)
    conn.execute(
        """
        INSERT INTO sessions (user_id, token_hash, expires_at)
        VALUES (%s, %s, %s)
        """,
        (user_id, token_hash(token), expires_at),
    )
    return token, expires_at


def auth_response(conn, user) -> dict:
    token, expires_at = create_session(conn, user["id"])
    return {
        "user": {"id": user["id"], "display_name": user["display_name"]},
        "access_token": token,
        "expires_at": expires_at,
    }


def current_session(authorization: Annotated[str | None, Header(alias="Authorization")] = None):
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Authentication required")
    token = authorization.split(" ", 1)[1].strip()
    if not token:
        raise HTTPException(status_code=401, detail="Authentication required")
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT s.id AS session_id, u.id, u.display_name
            FROM sessions s
            JOIN users u ON u.id = s.user_id
            WHERE s.token_hash = %s
              AND s.revoked_at IS NULL
              AND s.expires_at > now()
            """,
            (token_hash(token),),
        ).fetchone()
    if not row:
        raise HTTPException(status_code=401, detail="Invalid or expired session")
    return row


def current_user(session: Annotated[dict, Depends(current_session)]):
    return session


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


@app.post("/users", response_model=AuthOut)
def create_user(payload: UserCreate):
    display_name = normalize_display_name(payload.display_name)
    if not display_name:
        raise HTTPException(status_code=422, detail="Display name is required")
    password = payload.password.strip()
    if not password:
        raise HTTPException(status_code=422, detail="Password is required")
    with get_conn() as conn:
        existing_users = conn.execute(
            "SELECT id, display_name, password_hash FROM users ORDER BY created_at DESC",
        ).fetchall()
        existing = next((user for user in existing_users if compact_display_name(user["display_name"]) == compact_display_name(display_name)), None)
        if existing:
            if existing["password_hash"] is None:
                row = conn.execute(
                    """
                    UPDATE users
                    SET password_hash = crypt(%s, gen_salt('bf'))
                    WHERE id = %s
                    RETURNING id, display_name
                    """,
                    (password, existing["id"]),
                ).fetchone()
                response = auth_response(conn, row)
                conn.commit()
                return response
            raise HTTPException(status_code=409, detail="Display name already exists")
        row = conn.execute(
            """
            INSERT INTO users (display_name, password_hash)
            VALUES (%s, crypt(%s, gen_salt('bf')))
            RETURNING id, display_name
            """,
            (display_name, password),
        ).fetchone()
        response = auth_response(conn, row)
        conn.commit()
        return response


@app.post("/auth/sign-in", response_model=AuthOut)
def sign_in(payload: UserSignIn):
    display_name = normalize_display_name(payload.display_name)
    password = payload.password.strip()
    if not password:
        raise HTTPException(status_code=422, detail="Password is required")
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
            (display_name, password),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=401, detail="Invalid display name or password")
        response = auth_response(conn, row)
        conn.commit()
        return response


@app.post("/auth/logout")
def logout(session: Annotated[dict, Depends(current_session)]):
    with get_conn() as conn:
        conn.execute(
            "UPDATE sessions SET revoked_at = now() WHERE id = %s",
            (session["session_id"],),
        )
        conn.commit()
        return {"status": "ok"}


@app.post("/auth/password")
def change_password(payload: PasswordChange, session: Annotated[dict, Depends(current_session)]):
    new_password = payload.new_password.strip()
    if not new_password:
        raise HTTPException(status_code=422, detail="New password is required")
    with get_conn() as conn:
        row = conn.execute(
            """
            SELECT id
            FROM users
            WHERE id = %s
              AND password_hash = crypt(%s, password_hash)
            """,
            (session["id"], payload.current_password),
        ).fetchone()
        if not row:
            raise HTTPException(status_code=400, detail="Invalid current password")
        conn.execute(
            """
            UPDATE users
            SET password_hash = crypt(%s, gen_salt('bf'))
            WHERE id = %s
            """,
            (new_password, session["id"]),
        )
        conn.execute(
            """
            UPDATE sessions
            SET revoked_at = now()
            WHERE user_id = %s
              AND id <> %s
              AND revoked_at IS NULL
            """,
            (session["id"], session["session_id"]),
        )
        conn.commit()
        return {"status": "ok"}


@app.get("/auth/users", response_model=list[UserOut])
def list_sign_in_users():
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT id, display_name
            FROM users
            ORDER BY created_at ASC
            """
        ).fetchall()


@app.get("/users", response_model=list[UserOut])
def list_users(user: Annotated[dict, Depends(current_user)]):
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT id, display_name
            FROM users
            ORDER BY created_at ASC
            """
        ).fetchall()


@app.post("/calendars", response_model=CalendarOut)
def create_calendar(payload: CalendarCreate, user: Annotated[dict, Depends(current_user)]):
    with get_conn() as conn:
        calendar = conn.execute(
            """
            INSERT INTO calendars (name, owner_user_id)
            VALUES (%s, %s)
            RETURNING id, name, invite_code
            """,
            (payload.name, user["id"]),
        ).fetchone()
        conn.execute(
            """
            INSERT INTO calendar_members (calendar_id, user_id, role)
            VALUES (%s, %s, 'owner')
            """,
            (calendar["id"], user["id"]),
        )
        conn.commit()
        return {**calendar, "role": "owner"}


@app.post("/calendars/join", response_model=CalendarOut)
def join_calendar(payload: CalendarJoin, user: Annotated[dict, Depends(current_user)]):
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
            (calendar["id"], user["id"]),
        )
        conn.commit()
        return {**calendar, "role": "member"}


@app.get("/calendars", response_model=list[CalendarOut])
def list_calendars(user: Annotated[dict, Depends(current_user)]):
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT c.id, c.name, c.invite_code, cm.role
            FROM calendars c
            JOIN calendar_members cm ON cm.calendar_id = c.id
            WHERE cm.user_id = %s
            ORDER BY c.created_at DESC
            """,
            (user["id"],),
        ).fetchall()


@app.get("/calendars/{calendar_id}/members", response_model=list[UserOut])
def list_calendar_members(calendar_id: str, user: Annotated[dict, Depends(current_user)]):
    assert_member(calendar_id, user["id"])
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
def create_event(
    payload: EventCreate,
    provider: Annotated[EmbeddingProvider, Depends(embedding_provider)],
    user: Annotated[dict, Depends(current_user)],
):
    assert_member(payload.calendar_id, user["id"])
    if payload.created_by is not None:
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
            RETURNING id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source
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
    provider: Annotated[EmbeddingProvider, Depends(embedding_provider)],
    user: Annotated[dict, Depends(current_user)],
):
    with get_conn() as conn:
        existing = conn.execute(
            "SELECT calendar_id FROM events WHERE id = %s",
            (event_id,),
        ).fetchone()
    if not existing:
        raise HTTPException(status_code=404, detail="Event not found")
    assert_member(existing["calendar_id"], user["id"])
    if payload.created_by is not None:
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
            RETURNING id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source
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
def delete_event(event_id: str, user: Annotated[dict, Depends(current_user)]):
    with get_conn() as conn:
        existing = conn.execute(
            "SELECT calendar_id FROM events WHERE id = %s",
            (event_id,),
        ).fetchone()
        if not existing:
            raise HTTPException(status_code=404, detail="Event not found")
        assert_member(existing["calendar_id"], user["id"])
        conn.execute("DELETE FROM events WHERE id = %s", (event_id,))
        conn.commit()
        return {"status": "deleted"}


@app.get("/events", response_model=list[EventOut])
def list_events(
    calendar_id: str,
    user: Annotated[dict, Depends(current_user)],
    starts_before: datetime | None = None,
    ends_after: datetime | None = None,
):
    assert_member(calendar_id, user["id"])
    with get_conn() as conn:
        return conn.execute(
            """
            SELECT id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source
            FROM events
            WHERE calendar_id = %s
              AND (
                  recurrence_rule IS NOT NULL
                  OR (
                      (%s::timestamptz IS NULL OR starts_at < %s::timestamptz)
                      AND (%s::timestamptz IS NULL OR COALESCE(ends_at, starts_at) >= %s::timestamptz)
                  )
              )
            ORDER BY starts_at DESC
            LIMIT 5000
            """,
            (calendar_id, starts_before, starts_before, ends_after, ends_after),
        ).fetchall()


@app.post("/events/search", response_model=list[SearchEventOut])
def search_events(
    payload: SearchQuery,
    provider: Annotated[EmbeddingProvider, Depends(embedding_provider)],
    user: Annotated[dict, Depends(current_user)],
):
    calendar_ids = payload.calendar_ids or ([payload.calendar_id] if payload.calendar_id else [])
    if not calendar_ids:
        raise HTTPException(status_code=400, detail="calendar_id is required")
    for calendar_id in calendar_ids:
        assert_member(calendar_id, user["id"])
    query_embedding = vector_literal(provider.embed(build_query_embedding_text(payload.query)))
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source,
                   embedding <=> %s::vector AS distance
            FROM events
            WHERE calendar_id = ANY(%s::uuid[])
              AND (embedding <=> %s::vector) <= %s
            ORDER BY embedding <=> %s::vector
            LIMIT %s
            """,
            (query_embedding, [str(calendar_id) for calendar_id in calendar_ids], query_embedding, payload.max_distance, query_embedding, payload.limit),
        ).fetchall()
        if not rows:
            rows = conn.execute(
                """
                SELECT id, calendar_id, created_by, title, body, location, starts_at, ends_at, recurrence_rule, source,
                       embedding <=> %s::vector AS distance
                FROM events
                WHERE calendar_id = ANY(%s::uuid[])
                ORDER BY embedding <=> %s::vector
                LIMIT 1
                """,
                (query_embedding, [str(calendar_id) for calendar_id in calendar_ids], query_embedding),
            ).fetchall()
        return [dict(row) | {"similarity": max(0.0, 1.0 - float(row["distance"]))} for row in rows]


@app.post("/admin/reembed")
def reembed_all_events(
    provider: Annotated[EmbeddingProvider, Depends(embedding_provider)],
    user: Annotated[dict, Depends(current_user)],
):
    with get_conn() as conn:
        rows = conn.execute(
            """
            SELECT id, title, body, location, starts_at, ends_at, recurrence_rule, source, raw_text
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
                raw_text=row["raw_text"],
            )
            conn.execute(
                "UPDATE events SET embedding = %s::vector, updated_at = now() WHERE id = %s",
                (vector_literal(provider.embed(text)), row["id"]),
            )
        conn.commit()
        return {"status": "ok", "updated": len(rows)}

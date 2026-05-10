from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.config import get_settings


pool = ConnectionPool(
    conninfo=get_settings().database_url,
    kwargs={"row_factory": dict_row},
    min_size=1,
    max_size=10,
    open=False,
)


def open_pool() -> None:
    pool.open(wait=True)


def close_pool() -> None:
    pool.close()


@contextmanager
def get_conn() -> Iterator:
    with pool.connection() as conn:
        yield conn


def init_db() -> None:
    dimensions = int(get_settings().embedding_dimensions)
    with get_conn() as conn:
        conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        conn.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                display_name text NOT NULL,
                password_hash text,
                created_at timestamptz NOT NULL DEFAULT now()
            )
            """
        )
        conn.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash text")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS calendars (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                name text NOT NULL,
                calendar_type text NOT NULL DEFAULT 'schedule' CHECK (calendar_type IN ('schedule', 'ledger')),
                owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                invite_code text NOT NULL UNIQUE DEFAULT encode(gen_random_bytes(6), 'hex'),
                created_at timestamptz NOT NULL DEFAULT now()
            )
            """
        )
        conn.execute("ALTER TABLE calendars ADD COLUMN IF NOT EXISTS calendar_type text NOT NULL DEFAULT 'schedule'")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS calendar_members (
                calendar_id uuid NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
                user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                role text NOT NULL CHECK (role IN ('owner', 'member')),
                created_at timestamptz NOT NULL DEFAULT now(),
                PRIMARY KEY (calendar_id, user_id)
            )
            """
        )
        conn.execute(
            f"""
            CREATE TABLE IF NOT EXISTS events (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                calendar_id uuid NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
                created_by uuid REFERENCES users(id) ON DELETE SET NULL,
                title text NOT NULL,
                body text NOT NULL DEFAULT '',
                location text NOT NULL DEFAULT '',
                starts_at timestamptz NOT NULL,
                ends_at timestamptz,
                recurrence_rule jsonb,
                source text NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'sms_payment')),
                merchant text,
                amount numeric(14, 2),
                category text,
                payment_method text,
                raw_text text,
                embedding vector({dimensions}),
                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now()
            )
            """
        )
        conn.execute("ALTER TABLE events ADD COLUMN IF NOT EXISTS location text NOT NULL DEFAULT ''")
        conn.execute("ALTER TABLE events ADD COLUMN IF NOT EXISTS recurrence_rule jsonb")
        conn.execute("ALTER TABLE events ADD COLUMN IF NOT EXISTS payment_method text")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS sms_sender_patterns (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                calendar_id uuid NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
                sender_phone text NOT NULL,
                sample_message text NOT NULL DEFAULT '',
                amount_marker text NOT NULL DEFAULT '',
                merchant_marker text NOT NULL DEFAULT '',
                datetime_marker text NOT NULL DEFAULT '',
                created_at timestamptz NOT NULL DEFAULT now()
            )
            """
        )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS events_calendar_starts_at_idx
            ON events (calendar_id, starts_at)
            """
        )
        conn.execute(
            """
            CREATE INDEX IF NOT EXISTS events_embedding_hnsw_idx
            ON events USING hnsw (embedding vector_cosine_ops)
            """
        )
        conn.commit()

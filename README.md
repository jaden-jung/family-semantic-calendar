# Family Semantic Calendar

Android-first shared calendar with semantic search, local PostgreSQL/pgvector storage, and card-payment SMS ingestion.

## Structure

- `backend/`: FastAPI API server
- `mobile/`: Expo React Native Android app skeleton
- `docker-compose.yml`: local PostgreSQL with pgvector

## Quick Start

1. Copy backend env:

   ```powershell
   Copy-Item backend\.env.example backend\.env
   ```

2. Start PostgreSQL:

   ```powershell
   docker compose up -d db
   ```

3. Run backend:

   ```powershell
   cd backend
   py -m venv .venv
   .\.venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
   ```

4. Run mobile app:

   ```powershell
   cd mobile
   Copy-Item .env.example .env
   npm install
   npm run android
   ```

For an Android emulator, use `EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8000`.
For a physical phone, use your PC LAN IP or a Tailscale address.
When testing from outside the local network, use the PC's Tailscale address.

## Embeddings

The default `EMBEDDING_PROVIDER=mock` is only a deterministic development placeholder.
For real Korean semantic search, set:

```powershell
EMBEDDING_PROVIDER=openai
OPENAI_API_KEY=your_api_key
```

The provider is isolated in `backend/app/embeddings.py`, so a local model such as `bge-m3` can replace OpenAI later without rewriting API routes.

## Telegram Tomorrow Summary

The backend can send a Telegram summary of tomorrow's events every day.
Configure `backend/.env`:

```powershell
TELEGRAM_BOT_TOKEN=your_bot_token
TELEGRAM_CHAT_IDS=your_chat_id
NOTIFICATION_ENABLED=true
NOTIFICATION_TIME=23:00
NOTIFICATION_TIMEZONE=Asia/Seoul
NOTIFICATION_USE_LLM=true
OPENAI_SUMMARY_MODEL=gpt-4o-mini
```

Restart the backend after changing `.env`. Use `POST /admin/notifications/tomorrow` to send a test message immediately, or `GET /admin/notifications/tomorrow/preview` to preview the generated message.

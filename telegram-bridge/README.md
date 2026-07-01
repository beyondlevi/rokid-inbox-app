# Rokid Inbox — Telegram bridge (GramJS)

A tiny self-hosted HTTP service that talks to Telegram over **MTProto (your user
account)** using [GramJS](https://github.com/gram-js/gramjs), so the Rokid Inbox
phone app can use Telegram with plain HTTP — exactly like it uses your Evolution
API server for WhatsApp. No native library on the phone.

Run it on the **same machine as your Evolution API** to keep costs down.

## Endpoints

All are `POST` (except `/health`) and require an `apikey` header:

- `GET  /health` → `{ ok, authorized }`
- `POST /chats` `{ limit }` → chat list
- `POST /messages` `{ chatId, limit }` → messages (newest first)
- `POST /sendText` `{ chatId, text, replyToId? }`
- `POST /sendVoice` `{ chatId, audioBase64, durationSec, replyToId? }`
- `POST /sendReaction` `{ chatId, messageId, emoji }`
- `POST /markRead` `{ chatId }`
- `POST /media` `{ chatId, messageId }` → `{ base64 }` (image/audio download)

## Setup

1. Get `API_ID` / `API_HASH` at <https://my.telegram.org> → API development tools.
2. `cp .env.example .env` and fill `API_ID`, `API_HASH`, and a strong `BRIDGE_API_KEY`.
3. Install deps and log in once (interactive: phone → code → optional 2FA):
   ```bash
   npm install
   npm run login
   ```
   Copy the printed `TELEGRAM_SESSION=...` into your `.env`.
4. Start it:
   ```bash
   npm start           # or: docker compose up -d --build
   ```
5. In the Rokid Inbox phone app → **[ INBOXES / SETTINGS ] → + Add Telegram**,
   enter the bridge URL (e.g. `https://seu-dominio:8787`) and the `BRIDGE_API_KEY`.

## Running next to Evolution (Docker)

Copy the `telegram-bridge` service from `docker-compose.yml` into your Evolution
`docker-compose.yml` (same Docker network), or run this folder's compose. Proxy
port `8787` behind your reverse proxy the same way Evolution is exposed, and keep
`BRIDGE_API_KEY` secret.

Notes:
- One account per bridge instance. Run multiple instances (different ports) for
  multiple Telegram accounts.
- The session lives only on your server; nothing Telegram-related runs on the phone.

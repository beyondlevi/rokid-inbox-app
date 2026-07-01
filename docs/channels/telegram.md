# Telegram

Telegram uses **your own account over MTProto** through a small self-hosted
[GramJS](https://github.com/gram-js/gramjs) bridge (in [`telegram-bridge/`](../../telegram-bridge/)).
The phone app only speaks HTTP to the bridge (with an API key) — no native
library on the phone. Host the bridge wherever you like; running it next to your
Evolution API server keeps costs down.

Supports: list chats, read messages, send text, send voice, react, mark as read,
view photos.

## 1. Get Telegram API credentials

1. Go to <https://my.telegram.org> → **API development tools**.
2. Create an app and copy the **API_ID** and **API_HASH**.

## 2. Configure & run the bridge

On your server:

```bash
cd telegram-bridge
cp .env.example .env
# edit .env: set API_ID, API_HASH, and a strong BRIDGE_API_KEY
npm install
npm run login      # interactive: phone number -> login code -> 2FA password
npm start          # or: docker compose up -d --build
```

`npm run login` logs into your account once and stores the session (in a Docker
volume when using compose). Full details and the Docker/nginx setup are in
[`telegram-bridge/README.md`](../../telegram-bridge/README.md).

Verify it's authorized:

```bash
curl -H "apikey: <BRIDGE_API_KEY>" https://<your-bridge-host>/health
# -> {"ok":true,"authorized":true}
```

## 3. Add it in the app

On the phone: **[ INBOXES / SETTINGS ] → + Add Telegram (bridge GramJS)** and enter:

- **Bridge URL** — where the bridge is reachable (e.g. `https://lwpp.seudominio.com/tg`).
- **API Key** — the `BRIDGE_API_KEY` from the bridge `.env`.

The app checks `/health` and only saves once the account is logged in.

## Notes

- One Telegram account per bridge instance. For multiple accounts, run more
  instances on different ports/paths.
- The bridge endpoints: `/health`, `/chats`, `/messages`, `/sendText`,
  `/sendVoice`, `/sendReaction`, `/markRead`, `/media`.

## Troubleshooting

- **`authorized:false`** — run `npm run login` again and restart the bridge.
- **401 in the app** — the API Key doesn't match `BRIDGE_API_KEY`.
- **`FLOOD_WAIT` on login** — Telegram rate limit; wait and retry.

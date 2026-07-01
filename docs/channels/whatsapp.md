# WhatsApp

WhatsApp uses your own **[Evolution API](https://github.com/EvolutionAPI/evolution-api)**
server (an unofficial WhatsApp HTTP gateway). Rokid Inbox never talks to WhatsApp
directly — it calls your Evolution instance, so nothing is hardcoded and your
session stays on your server.

Supports: list chats, read messages, send text, send voice, react, mark as read,
view photos.

## 1. Run Evolution API

Follow the [Evolution API docs](https://doc.evolution-api.com/) to self-host it
(Docker is easiest). You need it reachable over HTTPS, e.g. `https://evo.seudominio.com`.

## 2. Create an instance and connect WhatsApp

1. Open the Evolution **Manager** (web UI) of your server.
2. Create an **instance** (give it a name, e.g. `pessoal`).
3. Connect it: scan the QR code with WhatsApp → **Linked devices** on your phone.
4. Wait until the instance status is **open/connected**.

## 3. Get the credentials

From the Manager, for that instance, note:

- **Server URL** — your Evolution base URL (e.g. `https://evo.seudominio.com`).
- **Instance name** — exactly as created (e.g. `pessoal`; spaces are fine, e.g. `whatsapp business`).
- **API Key** — the instance API key (Settings → API Key), or the global
  `AUTHENTICATION_API_KEY` if you use a global key.

## 4. Add it in the app

On the phone: **[ INBOXES / SETTINGS ] → + Add WhatsApp (Evolution API)** and fill
the three fields above. The app runs a quick connectivity check before saving.

## Notes

- Multiple WhatsApp accounts: add several boxes (they show as `[W]`, `[W1]`, `[W2]`).
- The app calls these Evolution endpoints: `chat/findChats`, `chat/findContacts`,
  `chat/findMessages`, `message/sendText`, `message/sendWhatsAppAudio`,
  `chat/markMessageAsRead`, `message/sendReaction`, `chat/getBase64FromMediaMessage`.
- Instance names with spaces work (the app URL-encodes them as `%20`).

## Troubleshooting

- **404 on connect** — wrong server URL or instance name (check exact spelling/casing).
- **401 / unauthorized** — wrong API Key.
- **Empty chats** — the instance isn't connected (re-scan the QR in the Manager).

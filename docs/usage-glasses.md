# Using the glasses

The glasses show a heads-up display (HUD): amber monospace text on black (black =
transparent on the see-through lens). Everything is driven from the phone, so
make sure the phone app is running and at least one inbox is connected first.

## Controls

The HUD is fully driven by three actions (mapped to the glasses touchpad / ring,
or hardware keys):

- **Swipe up / down** — move the selection (or scroll, in a long message).
- **Tap** (single) — open / confirm the highlighted item.
- **Double-tap** (or long-press / Back) — go back one level.

## Screens

- **Inbox** — a pinned `» Menu / Buscar` row on top, then your chats (each with
  the channel logo, name, group/channel tag, and unread count). Tap a chat to open it.
- **Menu** — `Buscar` (voice search), `Inbox geral`, `Não lidas`, and one filter
  per connected inbox (with its logo).
- **Conversation** — messages oldest→top, the selected one highlighted and shown
  in full (long ones are capped in the list). Swipe to move message-by-message;
  reaching the top loads older. Tap a message to open it.
- **Message detail** — the full message with its own scroll; for a `[photo]` it
  loads and shows the image. Tap again for actions, double-tap to return.
- **Actions** — `Responder` (reply), `Reagir` (emoji), and `Tocar áudio` for
  voice/audio messages.
- **Reply** — `Voz` (record a voice note) or `Mensagens rápidas` (canned replies).

## Replying by voice

1. Open a chat → tap a message → **Responder → Voz**.
2. Tap to start recording, speak, tap to stop.
3. The phone transcribes (OpenAI Whisper). You get a preview with two options:
   - **Enviar texto transcrito** — sends the transcription as text.
   - **Enviar áudio original** — sends the recording as a voice message.
4. Replies made from a selected message are sent **quoting** that message.

> Transcription needs an OpenAI key (Settings on the phone). Without it, a voice
> reply is sent directly as audio.

## Reactions, audio & photos

- **Reagir** — pick an emoji (👍 ❤️ 😂 😮 😢 🙏) to react to the selected message.
- **Tocar áudio** — plays a voice/audio message (audio comes out on the phone,
  or the glasses speaker if they're your Bluetooth audio output).
- **Photos** — open a `[photo]` message to view the image (downscaled by the phone).

## Voice search

Menu → **Buscar** → speak a contact/chat name. The phone transcribes and returns
matching chats across all connected inboxes.

## Quick messages

Menu of a chat → **Responder → Mensagens rápidas**. Manage the list on the phone
(Settings → Quick messages).

# Gmail

Gmail is a **read-only** inbox: each thread is a chat, each e-mail a message. You
provide your own OAuth credentials (Client ID + Secret + a Refresh Token) so the
app can read your mail directly from the Gmail API — nothing is hardcoded and
there is no backend.

Supports: list threads, read messages, mark as read. (No sending, no media.)

Scope required on the refresh token: `https://www.googleapis.com/auth/gmail.modify`
(needed to mark threads as read).

## 1. Google Cloud — Client ID + Secret

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create/select a project.
2. Enable the [Gmail API](https://console.cloud.google.com/apis/library/gmail.googleapis.com)
   (APIs & Services → Library → "Gmail API" → Enable).
3. **OAuth consent screen**: User type **External**; add your Google account under **Test users**.
4. **Credentials → Create Credentials → OAuth client ID**, type **Web application**.
   Under *Authorized redirect URIs* add exactly:
   `https://developers.google.com/oauthplayground`
5. Copy the **Client ID** and **Client Secret**.

## 2. OAuth Playground — Refresh Token

1. Open the [OAuth 2.0 Playground](https://developers.google.com/oauthplayground/).
2. Gear icon (⚙️, top-right) → check **"Use your own OAuth credentials"** and paste
   your Client ID + Client Secret.
3. Step 1: paste the scope `https://www.googleapis.com/auth/gmail.modify` →
   **Authorize APIs** → sign in (on the "unverified app" screen use *Advanced → Continue*).
4. Step 2: **Exchange authorization code for tokens** → copy the **Refresh token**.

> Tip: to keep the refresh token from expiring in ~7 days, set the consent
> screen **Publishing status** to **In production**.

## 3. Add it in the app

On the phone: **[ INBOXES / SETTINGS ] → + Add Gmail (read-only)** and enter the
**Client ID**, **Client Secret**, and **Refresh Token**. The app validates by
fetching your profile.

## Troubleshooting

- **`invalid_grant`** — refresh token expired/revoked; generate a new one (and
  consider "In production" publishing status).
- **403** — the Gmail API isn't enabled, or the scope is missing.

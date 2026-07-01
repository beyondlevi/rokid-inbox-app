# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Added
- AI descriptions: from the glasses, open a photo or a file (pdf, xlsx, docx,
  csv, ...) and pick **Descrever (IA)** to get a detailed text description on the
  HUD. Images use OpenAI vision; files are uploaded and read by the OpenAI agent.
  The existing OpenAI key now powers both Whisper transcription and AI descriptions.

## [1.0.0] - 2026-07-01

### Added
- Unified inbox for Rokid AR glasses across WhatsApp (Evolution API), Telegram
  (self-hosted GramJS bridge over MTProto), Gmail (OAuth, read-only) and GitHub
  PRs (read-only), with per-box and unread filters and multi-account labels.
- Two-app architecture: an Android phone host (channel integrations, unified
  inbox aggregator, OpenAI Whisper transcription, on-device encrypted
  credentials, Bluetooth transport server) and a glasses HUD client (navigation,
  conversation reader, mic capture) over a CXR/BLE/SPP hybrid transport with a
  versioned handshake.
- Hands-free replies: record a voice note on the glasses, transcribe it on the
  phone, then send the transcribed text or the original audio. Quoted replies,
  emoji reactions, audio playback, inline photos, and voice search of chats.

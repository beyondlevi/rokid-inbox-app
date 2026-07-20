# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Added
- AI descriptions: from the glasses, open a photo or a file (pdf, xlsx, docx,
  csv, ...) and pick **Descrever (IA)** to get a detailed text description on the
  HUD. Images use OpenAI vision; files are uploaded and read by the OpenAI agent.
  The existing OpenAI key now powers both Whisper transcription and AI descriptions.

### Changed
- Glasses HUD now uses the full see-through canvas: the chat list and the
  conversation reader render into a full-height scroll that keeps the selection
  visible, instead of drawing a small windowed subset that left the lower part
  of the display empty.

### Fixed
- Glasses R08 ring navigation direction: on the target hardware the ring
  delivers navigation as a synthesized temple fling whose axis is inverted
  relative to list order, so swipe-down moved the selection up and vice versa.
  All ring input (key, generic scroll and fling) is now funneled through a
  single normalized, debounced entry point, matching the reference behavior.
- Glasses tap reliability: use `onSingleTapConfirmed` instead of
  `onSingleTapUp` and drop long-press, so a double-tap-back no longer fires a
  stray select first.
- Glasses focus/scroll washes on the transparent display: disable the default
  focus highlight app-wide and on the HUD root, and use a passive scroll that
  refuses touch, the overscroll edge effect and accessibility scroll actions
  (the bridge could otherwise page the container behind the selection model).

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

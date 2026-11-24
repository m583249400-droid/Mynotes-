# MyNotes: how this repository is put together

- `index.html` is the whole app (writing + Live), locked with the app password. `index-prev.html` is the version before the last update, kept for a quick way back.
- `vendor/` holds the open libraries the app loads (notation engine, PDF viewer, QR code, SQLite reader).
- `player/` is the page the musicians open in their browser. It is served by the tablet app.
- `android/` is the tablet app: a window around the app plus a small server (`LiveServer.java`) that stores the sheet music and passes messages to the musicians.
- `.github/workflows/apk.yml` builds `MyNotes.apk` on every update and publishes it under Releases.
- `dev/live-src.enc` is the source of the Live module and the build and test tools, locked with the same app password
  (16 bytes salt, 12 bytes iv, then AES-256-GCM of a .tgz; key = PBKDF2-SHA256, 600000 rounds).

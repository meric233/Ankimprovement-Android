# Local two-way sync: desktop <-> Android emulator

This sets up **two-way review/progress sync** between the desktop app (this
fork) and AnkiDroid running on the emulator, using Anki's **built-in
self-hosted sync server** — no AnkiWeb, no cloud, no account. This is enough to
satisfy the project's sync requirements (one user, two devices); a public
multi-user server is not required.

> This doc reflects the actual working setup: server on port **27701**,
> credentials **`test` / `test`**, collection seeded from the desktop, and
> AnkiDroid configured to **not** fetch media (to save space for demos).

## What syncs (and what doesn't)

- **Syncs (data):** cards, notes, decks, the full review log, scheduling/FSRS
  state, tags, config, and (optionally) media. Both apps end up with an
  identical collection.
- **Does NOT sync (code):** app features/screens. The desktop readiness page is
  Python/Qt; it will not appear on Android via sync. Mobile needs its own Kotlin
  screen that calls the shared Rust engine. Sync only moves the *data* those
  screens read.
- **Collection vs. media are two separate transfers.** The card text and its
  image *references* (`<img src=...>`) live in the collection DB (~89 MB here).
  The actual image/audio *files* are a separate, much larger media sync
  (33,509 files / ~3.3 GB here). You can have all the cards without the images.

## Mental model

Three separate copies of your collection are kept in agreement:

- Desktop's copy (your normal Anki data folder,
  `~/Library/Application Support/Anki2/User 1/`).
- AnkiDroid's copy (inside the emulator, at `/storage/emulated/0/AnkiDroid/`).
- The **server's** canonical copy in `~/.syncserver` (kept separate on purpose).

Each object has an Update Sequence Number (USN); syncs transfer only the delta
since the last sync. First sync from an empty server is a one-time full upload;
afterwards it's incremental. Media syncs as a separate second phase.

## Ports and networking

- **Use a dedicated sync port: `27701`.** (The default is 8080, but `./run`
  gives the desktop GUI's Qt webview remote-debugging port 8080, so the sync
  server and the running desktop GUI collide on 8080. Using 27701 sidesteps
  this entirely — you can then run the desktop GUI normally.)
- Desktop reaches the server at `http://127.0.0.1:27701/`.
- The emulator reaches your Mac via the special alias **`10.0.2.2`**, so
  AnkiDroid must use `http://10.0.2.2:27701/` (NOT `localhost`/`127.0.0.1`).

## 1. Start the sync server

From the desktop repo root (`Ankimprovement/`). `SYNC_USER1` sets the only
account (username:password):

```bash
cd ~/Documents/MericXing/MIT/Intern/AlphaAI/Anki/Ankimprovement
SYNC_USER1=test:test SYNC_PORT=27701 ./run --syncserver
```

Leave this running. It stores data under `~/.syncserver` (override with
`SYNC_BASE=/some/path`). It is **not** a system service — it does not auto-start
and does not survive closing its terminal or rebooting. Idle cost is tiny
(~15 MB RAM, ~0% CPU); it only does work during a sync.

Confirm it's up:

```bash
# 405/400 (not 000/refused) means the sync server is answering:
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:27701/sync/hostKey
```

## 2. Point the desktop app at the server and seed it

The desktop is the source of truth (it has the deck + progress), so it seeds the
empty server. Two ways:

### Option A — via the GUI

```bash
cd ~/Documents/MericXing/MIT/Intern/AlphaAI/Anki/Ankimprovement
./run
```

1. Import your deck if needed (File -> Import -> `AnKing V11 updated.apkg`).
2. **Preferences -> Syncing -> set the self-hosted sync server URL to
   `http://127.0.0.1:27701/`.** (This step is essential — see the
   "email or password incorrect" gotcha below.)
3. Log in with `test` / `test`, then Sync.
4. On the empty server, choose **Upload** to seed it (collection + media).

### Option B — headless seeding script (no GUI clicks)

`Ankimprovement/seed_sync.py` logs in, full-uploads the collection, then
uploads all media, blocking until the media transfer completes. Run it with the
**desktop GUI closed** (the collection must not be locked):

```bash
cd ~/Documents/MericXing/MIT/Intern/AlphaAI/Anki/Ankimprovement
PYTHONPATH="$PWD/out/pylib:$PWD/out/qt" out/pyenv/bin/python seed_sync.py
```

Notes:
- `PYTHONPATH` must include `out/pylib` and `out/qt` (the generated protobuf
  modules live there; `run.py` adds them at runtime, a plain `python` does not).
- Media upload of ~3.3 GB / 33k files takes ~1.5 min over loopback. The script
  polls `media_sync_status()` and only exits when it reaches all files —
  don't close the collection before it finishes or the transfer aborts.

## 3. Configure AnkiDroid (emulator) and pull down

### Boot the emulator (as a persistent process)

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
emulator -avd usmle_step1 -no-boot-anim
```

Gotchas:
- If you launch the emulator with `&` inside a shell that then exits, it gets
  SIGHUP'd and dies. Run it in its own persistent terminal (or `nohup`/`disown`).
- To launch the **AnkiDroid** UI from the command line, start its real launcher
  activity — `monkey` opens LeakCanary in the debug build instead:

  ```bash
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    -n com.ichi2.anki.debug/com.ichi2.anki.IntentHandler
  ```

### Configure the custom sync server

In-app: **Settings -> Sync -> Custom sync server -> enable**, set **Sync URL**
to `http://10.0.2.2:27701/`, then tap the sync icon, log in `test` / `test`,
and choose **Download** to pull the desktop collection.

The relevant AnkiDroid preferences (in
`/data/data/com.ichi2.anki.debug/shared_prefs/com.ichi2.anki.debug_preferences.xml`)
are:

| Pref key           | Meaning                     | Value used |
|--------------------|-----------------------------|------------|
| `syncBaseUrl`      | custom sync server URL      | `http://10.0.2.2:27701/` |
| `syncBaseUrl_switch` | enable custom server (bool) | `true` |
| `syncFetchMedia`   | media policy: `always` / `only_unmetered` / `never` | `never` (see below) |
| `username`,`hkey`  | stored login (set after first successful login) | `test` / (issued) |

For a debug build you can pre-seed these from the host with `run-as` (force-stop
the app first so it doesn't overwrite them):

```bash
adb shell am force-stop com.ichi2.anki.debug
PREFS=/data/data/com.ichi2.anki.debug/shared_prefs/com.ichi2.anki.debug_preferences.xml
adb shell "run-as com.ichi2.anki.debug sh -c 'cat > $PREFS'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="syncBaseUrl">http://10.0.2.2:27701/</string>
    <boolean name="syncBaseUrl_switch" value="true" />
    <string name="syncFetchMedia">never</string>
    <boolean name="IntroductionSlidesShown" value="true" />
</map>
XML
```

(You still log in + sync in-app; the login must match the server's `SYNC_USER1`.)

## 4. Media policy: "failed to load image" and saving space

AnkiDroid's **Settings -> Sync -> Fetch media on sync** controls image/audio
download:

- `always` (default) — download all media on sync (~3.3 GB here).
- `only_unmetered` — only on unmetered networks. **The emulator's network often
  registers as metered, so media won't download with this setting** — use
  `always` if you want media on the emulator.
- `never` — skip media sync entirely (both directions).

If the collection synced but images show **"failed to load image"**, it means
the media files aren't present locally (media sync incomplete/disabled) even
though the card text is. Cards without images render fine, hence images fail
only "sometimes".

### Demo setup used here (lean, no media)

To keep the emulator small for a demo we set `never` and deleted the downloaded
media (reclaimed ~768 MB):

```bash
# 1) set fetch policy to never (see the run-as prefs write above)
# 2) delete the local media files (slow on the emulator's FUSE storage):
adb shell "rm -rf /storage/emulated/0/AnkiDroid/collection.media/*"
```

Because `syncFetchMedia=never` disables media sync in **both** directions,
deleting local media does **not** propagate to the server — desktop/server keep
their full 3.3 GB. To get images back later, set the policy to `always` and
sync; AnkiDroid re-downloads the files from the server.

## 5. Verify two-way sync (project section 7b)

1. Turn off the emulator's network (airplane mode) and review ~10 cards.
2. On desktop review ~10 different cards, then Sync (uploads).
3. Re-enable the emulator's network and Sync.
4. Confirm all 20 reviews are present on both sides, none lost or duplicated.
5. Conflict case: review the *same* card on both while offline, then sync both.

**Conflict rule (document this):** review-log entries are merged additively and
have unique IDs, so no review is lost or double-counted; when the same card is
reviewed on both devices offline, the card's current scheduling state resolves
to whichever device syncs last (last-writer-wins for card state, all reviews
preserved).

## Storage note

Each participant keeps a full copy: desktop's copy + the server's copy in
`~/.syncserver` (~3.3 GB) + the emulator's copy inside its disk image. Reclaim
the server copy by stopping the server and deleting `~/.syncserver`; reclaim the
emulator copy by deleting/recreating the AVD (or, for media only, the `rm`
above).

## Troubleshooting

- **Desktop: "email or password incorrect".** The desktop tried to log in to
  real **AnkiWeb**, where `test`/`test` isn't valid. Fix: Preferences -> Syncing
  -> set the self-hosted URL to `http://127.0.0.1:27701/`, then log in again.
- **Android: "Cannot connect to AnkiWeb".** Wrong host or server down. From the
  emulator use `10.0.2.2` (not `localhost`), and confirm the server terminal is
  still running.
- **"address already in use" starting the server.** Something already holds the
  port. With the desktop GUI running, 8080 is taken by its Qt remote-debugging —
  that's exactly why we use `SYNC_PORT=27701`. Check with
  `lsof -nP -iTCP:27701 -sTCP:LISTEN`.
- **Server on an unexpected port.** `./run --syncserver` without `SYNC_PORT`
  defaults to 8080 and will fail/behave oddly if the GUI holds it. Always pass
  `SYNC_PORT=27701`. Verify with `lsof -nP -p <pid> -a -iTCP`.
- **Sync asks to upload/download instead of merging.** Happens only on the first
  sync or after a schema change; pick the direction of the side whose data you
  want to keep (desktop = source of truth here).
- **`python -m anki.syncserver` / `seed_sync.py` import errors** (e.g.
  `cannot import name ankiweb_pb2`): missing `PYTHONPATH="$PWD/out/pylib:$PWD/out/qt"`.
- Anki messages say "AnkiWeb" even with a custom server configured — expected
  wording, not an error.
```

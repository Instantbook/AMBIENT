# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AMBIENT is a single-file PWA "cockpit" dashboard for a worn glasses display — a web-native home screen for
a device whose stock UI is a streaming storefront. No build system, no package.json, no bundler, no test
suite: hand-written HTML/CSS/JS served as static files from GitHub Pages, plus one Cloudflare Worker for
anything needing a secret or a server. Sibling projects `Instantbook/LEXIS` and `Instantbook/NOUS` use the
same deployment pattern.

## Repo and deploy

This directory is a git clone of **`Instantbook/AMBIENT`** (public, branch `main`), and GitHub Pages
deploys **from `main` at root** to <https://instantbook.github.io/AMBIENT/>. So the deploy is just
`git push` — every commit to `main` republishes. There is no build step and no staging environment;
`main` is production.

Tracked files live at the repo root: `index.html` (the entire app), `companion.html` (phone remote),
`sw.js`, `manifest.json`, `icon.png`, `ambient-worker.js` (Cloudflare Worker reference source — deployed
separately, not by Pages), `SETUP.md`, and archived `ambient-v*.zip` build packages.

### The Android wrapper (`app/`)

`app/` is a ~29 KB WebView host that loads the *hosted* page — a shell, not a fork, so `git push` is still
the entire deploy process and the APK rarely needs reinstalling. It exists because the device's stock
browser cannot drive this app at all (see "The cursor problem" below).

Build with `bash app/build.sh` — it drives `aapt2`/`javac`/`d8`/`zipalign`/`apksigner` directly using
build-tools 35 and the JDK bundled with Android Studio (`Android Studio1/jbr` — the first Android Studio
install's JBR is broken, missing `jvm.cfg` and `javac`). No gradle, no daemon, nothing to download. Build
output goes to a temp dir outside Dropbox, because Dropbox holds handles that make `rm -rf build` fail
with "Device or resource busy". Then `adb install -r <apk>`.

`app/debug.keystore` is gitignored and generated on first build. An earlier commit leaked one into this
public repo, so that key was rotated; if you regenerate it again, the app must be **uninstalled** before
reinstalling (signature mismatch), which also wipes its localStorage.

Untracked local-only files, ignored via `.git/info/exclude` (which is local to `.git/` and never pushed):
`info.md` (a pasted transcript of the claude.ai session that designed v11 — historical context, not
documentation), `extracted/` (a scratch unzip of `ambient-v11.zip`), and this `CLAUDE.md`.

### The Cloudflare Worker

Deployed and live at **`https://ambient-worker.dbazos1.workers.dev`** (account `dbazos1`).
Config is `wrangler.toml`; redeploy with `npx wrangler deploy` from the repo root. It is a *separate*
Worker from the NOUS one (`curly-term-5539`, a generic fetch proxy with a shared-secret `k=` param — it
has nothing reusable for AMBIENT).

| Endpoint | State | Needs |
|---|---|---|
| `/feeds` | ✅ 12 headlines, BBC + ΤΟ ΒΗΜΑ | `FEEDS` var |
| `/radio` | ✅ scrapes greek-radio.gr for stream URLs | — |
| `/ws` | ✅ Durable Objects available on this plan | `ROOMS` binding |

`/claude` was removed: the remote's voice button already reaches Google's assistant and speaks its answer
into the headset, so a second AI in a tile earned nothing. `ANTHROPIC_API_KEY` is still stored as a secret
on this Worker and is now inert — delete it whenever.

- **Secrets are per-Worker, not per-account.** The key initially went onto the NOUS Worker and `/claude`
  kept returning `no key configured`.
- **`wrangler secret put` reads stdin**, so it cannot be run through Claude Code's `!` prefix — it gets EOF
  and silently stores an *empty* secret that still shows up in `secret list`. Use a real terminal or the
  dashboard. (`wrangler login` works through `!` because it hands off to a browser.)
- **Dashboard plaintext Variables get wiped by `wrangler deploy`** — `[vars]` in `wrangler.toml` replaces
  them. Secrets survive.
- `ALLOW_ORIGIN` is the real access control; the room code is pairing convenience, not authentication.

### Known drift

`SETUP.md` in the repo is a **v6-era document** and is now many versions behind — it predates the bezel
layout, the wrapper app, the config file, MUSIC, and the current control scheme. Treat this file as the
documentation and `SETUP.md` as an artifact until someone rewrites it.

## Two gotchas that have already caused a production outage

**1. `sw.js`'s `SHELL` array is atomic.** The install handler calls `cache.addAll(SHELL)`, and `addAll`
rejects entirely if *any* one URL fails — which rejects `waitUntil`, so the service worker never installs
and silently re-fails on every load, leaving zero shell caching. This actually happened: `manifest.json`
was listed in `SHELL` but never uploaded to the repo, 404'd, and killed the SW completely (fixed in
`90ed780`). **Any file added to `SHELL` must exist at the deployed path**, and after touching `SHELL` it's
worth confirming the SW actually reaches `activated` rather than assuming.

**2. Bump both version constants together.** `APP_VERSION` in `index.html` and `CACHE` in `sw.js` must
move as a pair on *every* change to a cached file, or the service worker keeps serving stale files after a
deploy. The SYSTEM card renders `APP_VERSION`, which is the quickest way to confirm what a device is
actually running.

**3. `cache.addAll()` fetches through the HTTP cache — this caused the long-standing "deploys don't reach
the device" bug.** GitHub Pages serves the shell with `Cache-Control: max-age=600`. A newly installed
service worker would fetch `index.html`, get the *previous* deploy back out of the HTTP cache, and store
that under the new cache name: the version constant moved and the content did not. The symptom was a
SYSTEM tile stuck on the old version through a restart *and* a reinstall, because each reload re-primed
the same stale ten-minute window — which is why `pm clear` looked like the only cure.

Fixed by fetching the shell as `new Request(u,{cache:"reload"})`, which forces the network and refreshes
the HTTP cache on the way past. **Keep that flag.** Registration is otherwise correct and was never the
problem: `updateViaCache:"none"`, `reg.update()` on load and every 10 minutes, `skipWaiting` +
`clients.claim`, and a reload on `controllerchange` deferred while audio plays.

`adb shell pm clear <package>` still forces a version on immediately, but it wipes localStorage — room
code, scenes, starred stations — so it should now be a last resort rather than routine.

## Target device (verified over ADB, not assumed)

The RayNeo Pocket TV is a rebadged **Homatics / SEI Robotics `SEI700GHMG`** ("XR Theater G"), **Android 12
(SDK 31)**, Amlogic. The glasses are a dumb 1080p display; the box has no sensors, cameras, or head
tracking, so nothing AR-shaped is possible by design.

- **ADB**: `adb.exe` at `%LOCALAPPDATA%\Android\platform-tools\adb.exe`. USB debugging is enabled and this
  machine is authorized. Works over the charging/data USB-C port (the other port is DisplayPort-out to the
  glasses), simultaneously with MTP.
- **Screenshots work**: `adb shell screencap -p /sdcard/x.png` then `adb pull` — so device state is
  directly observable, and layout changes can be verified visually instead of described. Do **not** pipe
  `adb exec-out screencap -p` through a PowerShell `>` redirect; it corrupts the binary with a BOM. Write
  to the device and pull, or redirect via the Bash tool.
- **microSD**: real path `/storage/3238-3332/` (477 GB). `/AMBIENT/` exists there with `music/ movies/
  photos/ podcasts/ soundscapes/ radio/ feeds/ scenes/ docs/ maps/`. Internal storage is
  `/storage/emulated/0/` (51 GB). Both are also reachable over MTP as `Internal shared storage` and `disk`.
- **Browser**: `com.tcl.browser` (Play-updated, current); `com.nes.browser` is a vendor system stub that
  ignores VIEW intents entirely. WebView is **Chromium 120**, which covers every feature the app uses (CSS
  grid, `clamp()`, custom properties, WebAudio `AnalyserNode`, `speechSynthesis`, service workers,
  `AbortSignal.timeout`) — no polyfills needed.
- **Sideloading**: `adb install foo.apk` works directly and bypasses the "unknown sources" toggle.
- **Verified working on hardware**: every card. Radio and local music both play with a real FFT driving the
  visualiser, headlines and Greek stations come through the deployed Worker, and the companion relay pairs.

### The cursor problem (why `app/` exists)

**`com.tcl.browser` drives pages with a virtual mouse cursor and never forwards arrow keys to the page.**
Every key-driven control in AMBIENT was therefore unreachable in it. Its package exposes only
`BrowsePageActivity`, `HomePageActivity` and player activities — there is no settings activity, so the
cursor cannot be turned off. Adding `tabindex="0"` to every tile did *not* flip it into spatial-navigation
mode either. Do not spend time re-testing this; the wrapper app is the answer, and in it a WebView
delivers `DPAD_*` to the page as ordinary Arrow/Enter events, which is what the shell already listens for.

Pointer support in the app (hover highlights, click opens, `onPick(ctx,i)` on card rows) is retained as a
fallback for any pointer-driven host. Clicks with `detail===0` are ignored — those are synthesized by a
browser from a keypress on a focused element, and `route()` has already handled that key.

### The display trap

Physical resolution is **1920×1080 but density is 320**, so Android halves it: a `width=device-width` page
gets a CSS viewport of **960 × 540**, not 1920 × 1080. A layout with a fixed number of rail slots therefore
clipped roughly half the left/right tiles at that height.

Fixed by `frameGeom()`, which derives the tile size *and* the per-edge slot count from the live viewport
rather than assuming either. Two constraints it encodes and that any redesign must keep:

- **118px is a hard floor, not a preference.** Below it a tile's own contents clip, which trades a visible
  layout bug for an invisible one.
- **Slot counts fall out of the space that is left**, so a short viewport gets fewer tiles per rail instead
  of the same number squeezed under `overflow:hidden`.

Verify against a real screenshot before redesigning — TV browsers sometimes force their own desktop
viewport, which changes the math entirely.

## Architecture

### Card contract (the extension point)

Almost everything is a "card" object registered via `registerCards([...])` near the bottom of `index.html`:

- Required: `id`, `pane`, `render(ctx, data)` → HTML string (or DOM Node).
- Optional: `init(ctx)`, `onKey(ctx, key, held)` (`"up"|"down"|"left"|"right"|"enter"` — return `true` to
  consume; `held` is the auto-repeat flag), `onBack(ctx)` (return `true` if the card climbed its own
  hierarchy, `false` to let the shell leave the card), `onToggle(ctx)` (double-press on the tile),
  `onPick(ctx, i)` (pointer click on row `i`), `onText`, `onInject`, `onScene`, `onTheme`.
- Optional `renderCompact(ctx, data)` → small HTML for the card's rail/overview tile; cards without it get
  a placeholder dash tile.
- Optional `renderFooter(ctx)` → HTML pinned below the scrolling body (`#stage-foot`). The stage is a flex
  column — header and footer fixed, only the body scrolls. MUSIC's scrub bar lives here because appending
  it to `render()` put it under thirteen track rows and off the bottom of the screen.
- `sources: [{key, origin, path}]` declares what the card fetches (see Data layer).
- `pinned: true` cards sit in a fixed frame slot (currently only `clock`), never rotate into the stage, and
  are immune to scene deactivation. `immersive: true` (only `vis`) drops the frame and every label in
  fullscreen, so black blends into the glasses' unlit surround.
- Malformed cards are dropped at registration, never fatal to the rest of the app.

Registered cards: `cardClock` (pinned), `cardWeather`, `cardMarkets`, `cardHeadlines`, `cardRadio`,
`cardMusic`, `cardVis`, `cardLaunch`, `cardScenes`, `cardSystem`.

### Shell / layout

One dominant centre "main window" surrounded by a bezel of fixed tiles. **The bezel is a launcher, not a
carousel** — every card holds a permanent slot and the highlight moves, so the frame can be learned
spatially. State is two independent values: `stageId` (what the main window shows) and `selIdx` (where the
highlight is), so the highlight can roam without disturbing what's loaded.

Four modes — `bezel` (default), `stage`, `full`, `overview`. See **Controls** below for what the keys do in
each; that section is the authority, since the bindings changed once already.

`frameGeom()` derives tile size and per-edge slot counts from the viewport (see "The display trap"), and
`layoutSlots()` spaces cards evenly around the ring rather than packing them into the first slots —
packed, 10 cards in 18 slots filled the top row and right rail and left the entire left rail and most of
the bottom as fillers, which the highlight skips, so half the bezel was unreachable. `frameCards()` is the
single source of slot order, used for both layout and ◄► traversal, so screen position and key order
cannot drift apart.

All cards refresh on a shared 5s scheduler (`refreshCard`, gated per-card by `refresh` seconds plus
jitter) regardless of which is staged. Note `refreshCard` stamps `card._last` *before* awaiting, so a
failed fetch still consumes the slot — with `weather.refresh = 900` a transient boot-time failure looks
identical to a permanent one for 15 minutes. Physical keydown, pointer events, and phone-companion
messages all funnel through the same `route(k)` / `openCard()` pair.

### Data layer

`ctx.fetch(key)` resolves the card's declared source by `origin`: `"direct"` (fetch as-is, CORS must be
clean — Open-Meteo, open.er-api, CoinGecko, radio-browser.info), `"worker"` (prefixed with the configured
Cloudflare Worker base URL), or `"local"` (`http://localhost:8765`, **stubbed but unimplemented** — the
planned microSD bridge). Every fetch writes through a per-source SWR cache in localStorage
(`cacheGet`/`cachePut`); offline or on failure the last-known-good value is served and an `OFFLINE`
indicator appears. This caching is independent of the service worker.

Note: `adb reverse tcp:8765 tcp:8765` maps the device's `localhost:8765` to this machine — exactly the
transport the `origin:"local"` bridge was stubbed for.

### Audio bus

One `<audio>` element app-wide (`audioBus`), shared by RADIO and MUSIC. CORS-clean sources (`cors:true` —
the SomaFM/Radio Paradise presets and every local track) route through an `AudioContext`/`AnalyserNode` for
real FFT; everything else plays natively, because routing a non-CORS stream through WebAudio would silently
**mute** it, and `getLevels()` returns synthetic motion instead.

Three things here were bugs and are load-bearing now:

- **`latencyHint:"playback"`.** The default `"interactive"` asks for a tiny buffer, and on this hardware
  that produced audible stutter that sounded exactly like a slow network. It is the whole fix.
- **The analyser's top bins are dead.** They carry almost no energy at any real sample rate, so a band
  layout that spans the full array leaves the right of the visualiser permanently high. Bands stop at 78%
  and deflect against a *local* moving average, not the global mean.
- **"Something is playing" is not "my source is playing."** One bus serving two cards means each tile must
  check that the current URL is one of *its own* before claiming it, or RADIO announces MUSIC's track.

`duration()` returns 0 for a live stream (whose real value is `Infinity`), which is also the test
`seekable()` uses to decide whether ◄► scrub or do something else.

### Themes, scenes, companion

`THEMES` swaps palettes via CSS custom properties. `SCENES` composes palette + active cards + staged card
+ per-card `config` delivered through `onScene`; user scenes merge from the `ambient.scenes` localStorage
key.

The glasses generate a 5-char room code and connect to the Worker's `/ws?room=CODE&role=display`;
`companion.html` on the phone connects as `role=remote`. The Worker gives each room its own Durable Object
(`class Room`) that blindly broadcasts to the other sockets. Phone message shapes: `{t:"key",k}` (drives
the same `route()`), `{t:"text",v}` (→ staged card's `onText`), `{t:"action",id}` (`scene.*`, `theme.*`,
`stage.<cardId>`), `{t:"inject",kind,v,target?}` (→ named card, or any card whose `accepts` lists that
kind; `url`→`cardLaunch`). **The room code is pairing convenience, not
authentication** — the Worker's `ALLOW_ORIGIN` var is the actual lock and must be set to the Pages origin.

The relay is also the reliable way to drive the device from this machine: `{t:"key",k}` lands in `route()`
exactly as a real keypress does, where `adb shell input tap` misses the right-hand tiles.

The same Worker serves `/feeds` and `/radio` — server-side fetch and parse, keeping the client CORS-clean.
The `wrangler.toml` template is in the comment block at the bottom of `ambient-worker.js`.

## Installing the app (two grants, or it degrades quietly)

**After every `adb install`, run both of these.** Neither failure is loud, which
is exactly why they need writing down:

```
adb install -r /tmp/ambient-apk-build/ambient.apk
adb shell appops set io.github.instantbook.ambient MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant io.github.instantbook.ambient android.permission.READ_EXTERNAL_STORAGE
```

- **Without the first**, settings fall back from `/storage/emulated/0/AMBIENT/config.json`
  to the app's own external directory — which works, but *is* wiped with the app,
  so every customisation silently becomes disposable again.
- **Without the second**, MUSIC finds nothing at all. It reads the library through
  MediaStore, which needs it.

Rebuild first with `bash app/build.sh`; the APK lands in a temp dir outside Dropbox.
A signing-key change requires `adb uninstall` first, and that wipes app data (though
no longer the settings file).

## Controls

The scheme, and the reasoning, since it went through several wrong versions:

| | ▲▼ | ◄► | OK | BACK |
|---|---|---|---|---|
| **Bezel** | move between tiles (spatial) | move between tiles | open card · again = fullscreen | overview |
| **A card** | choose a row | **back to the tiles** | act on the row | **up one level** |
| **RADIO** | choose | ► stars (card consumes it) | play/stop | back to tiles |
| **MUSIC** | choose | back to tiles | play/resume · **twice = pause** | track → album → tiles |
| **Fullscreen** | as the card | **seek ∓10s, ∓3s per repeat held** | card action | leave fullscreen |
| **Overview** | move | move | open | bezel |

Principles worth keeping:

- **Two independent ways out of every card, always.** BACK climbs (`onBack()` returns
  `true` while it still has somewhere to go) *and* ◄► leave. This is not redundancy for
  its own sake — see the trap below.
- **◄► inside a card must not be spent on anything else.** They were briefly taken for
  seeking, and because they were also how you left a card, MUSIC became inescapable the
  moment it owned the audio: the exit a thumb reaches for silently became a
  fast-forward. BACK still worked, which was no comfort. **Do not reassign these keys in
  the windowed view again.**
- **Scrubbing lives in fullscreen**, where there are no tiles to walk back to and BACK is
  the only exit anyway, so the pair is genuinely free. Cards gate it on `mode==="full"`.
- **A single OK never pauses.** It starts or resumes; pausing is a deliberate
  double-press. Pausing by accident while walking a track list was easy, and undoing it
  took another press that reads as "stop".
- **◄► do what the medium allows.** A live stream reports `Infinity` for duration and so
  gets no timeline; inventing one would be a lie.
- **Fullscreen does not cycle cards with ◄►.** Nobody flips between fullscreen weather
  and fullscreen markets.
- **Hold = `e.repeat`.** Android sends auto-repeat keydowns, so press-and-hold needs
  no timers and stops the instant the key is released.
- **The staged card gets first refusal on every key except BACK.** Handling ◄► in the
  shell first was a real bug: RADIO's ► could never reach the card.
- **Double-press a tile** runs `onToggle()` — play/pause for RADIO and MUSIC, fullscreen
  for the visualiser. Only cards that define it pay the 260ms disambiguation delay.
- **BACK is the page's, and exit is a long press.** The wrapper used to quit on two BACK
  presses inside 2s. That was safe while BACK only meant "leave", but once it meant "up
  one level", climbing out of MUSIC (track → album → tiles) was two presses well inside
  the window and quit the app. `onKeyDown` now claims the key with `startTracking()` and
  defers to `onKeyUp`, with `onKeyLongPress` doing the exit — a hold cannot be produced
  by navigating. Don't reintroduce a timing-based exit gesture.

## Settings live in a file

`/storage/emulated/0/AMBIENT/config.json`, pretty-printed and MTP-visible so it can be
edited from a computer. localStorage stays the fast working store; the file is the
durable copy — it survives `pm clear` and uninstall, which localStorage does not.

- Mirrored keys are listed in `CFG_KEYS`; adding a setting is one entry.
- **`loadConfig()` must run before anything reads localStorage.** Module-level
  initialisers (`themeName`, the companion room code) take their defaults the moment
  the script parses, so loading any later restores nothing — that bug looked exactly
  like the file not being read.
- The write location is *probed*, not predicted: `canWrite()` on a volume root said the
  microSD was writable when it was not. `writeConfig` walks candidates and keeps
  whichever accepts the file.

## Local media

**An ordinary app cannot read a removable volume at all** — not even with All-files
access granted. Direct filesystem scanning found a card full of music completely empty
while a track copied to internal storage appeared instantly. Do not retry that approach.

MUSIC therefore reads **MediaStore**, which indexes every volume, needs only
`READ_EXTERNAL_STORAGE`, and returns real tags (title/artist/album) instead of
filenames. Playback streams by `content://` id through an https URL the app intercepts
itself (`https://ambient.local/media?id=…`), because an https page cannot load `file://`
and a plain-http helper would be blocked as mixed content — the same wall the Greek
radio stations hit. The response carries CORS headers, so **local tracks drive the real
FFT visualiser**. Range requests return a genuine 206, so seeking works.

Browsing is artist → album → track; an artist with one album skips the album level in
both directions.

## Where things stand

### Provisioning after a wipe

Mostly automatic now. The Worker URL is compiled into the APK from `app/worker.url`
(gitignored, so it is never published) and injected on load, and everything else is
restored from `config.json`. A wipe costs nothing but the two permission grants above.

Screenshots: `adb shell screencap -p /sdcard/x.png` then `adb pull` — never pipe
`adb exec-out screencap -p` through a PowerShell `>` redirect, it corrupts the PNG with
a BOM. `adb shell input tap` is unreliable on the right-hand tiles; drive the device
with arrow keys, or over the companion relay (below), which is deterministic.

**Check before driving the device.** If the user is using it, scripted keypresses and
`am force-stop` land on top of whatever they are doing — and their keypresses will look
like your results.

### The assistant button

The remote's voice button sends `KEY_ASSISTANT` → `KEYCODE_ASSIST`, which **the system intercepts** — it
never reaches the app, and only `com.google.android.katniss` handles `ACTION_ASSIST`. Answers from that
button come from Google, not Claude. `KEYCODE_VOICE_ASSIST` and `KEYCODE_SEARCH` are *not* intercepted.

Voice-to-Claude already works without any of that: the leanback IME has built-in dictation, so
**CLAUDE tile → OK → ▼ → hold the mic → speak**. Note that even if AMBIENT became the assist app, speech
recognition would still be Google's (`voice_recognition_service`) — only the answering model would change.

To point the button at AMBIENT it would need an `ACTION_ASSIST` filter plus `RECORD_AUDIO`/`SpeechRecognizer`,
and these settings changed (which replaces Google Assistant device-wide). Restore values:

```
settings put secure assistant                 com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService
settings put secure voice_interaction_service com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService
```

## Visual language

The frame stays monotone HUD; color is reserved for carrying data meaning — `tempColor()` (cold-blue →
hot-red), `wxIcon()` (condition → icon+color), `srcColor()` (stable hash → per-source identity color), and
up/down deltas on markets. Keep new cards on that split: quiet frame, colorful data. Don't introduce new
chrome colors.

### Tiles are instruments, not shrunken cards

`renderCompact()` must say what the card is **holding**, never show a slice of its content. Tiles are
118px squares: a headline, a reply, or a wrapped price gets guillotined mid-word and is useless as a
glance. Build every tile from `face(value, label, {color, dots})` — one short value, one label, optional
identity dots — which clamps every text run so nothing is ever cut mid-word. Word-shaped values step down
a size automatically (`.tv.sm`) rather than ellipsising. Counts, states and identity dots are the
vocabulary: `12 / from 2 sources`, `11 / stations`, `READY / room 5ND2Z`. The full content belongs on the
stage. `tileTitle` lets a card keep a rich stage header while the tile shows something that fits.

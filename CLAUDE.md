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

Untracked local-only files, ignored via `.git/info/exclude` (which is local to `.git/` and never pushed):
`info.md` (a pasted transcript of the claude.ai session that designed v11 — historical context, not
documentation), `extracted/` (a scratch unzip of `ambient-v11.zip`), and this `CLAUDE.md`.

### Known drift

`SETUP.md` in the repo is a **v6-era document**. It predates the v7 visual language, the v9 bezel layout,
pinned cards, v10, and the entire v11 LAUNCH card — and it omits the version-bump rule below. The current
version lives inside `ambient-v11.zip` (and `extracted/SETUP.md`). Read that one; treat the committed
`SETUP.md` as stale until it's synced.

## Two gotchas that have already caused a production outage

**1. `sw.js`'s `SHELL` array is atomic.** The install handler calls `cache.addAll(SHELL)`, and `addAll`
rejects entirely if *any* one URL fails — which rejects `waitUntil`, so the service worker never installs
and silently re-fails on every load, leaving zero shell caching. This actually happened: `manifest.json`
was listed in `SHELL` but never uploaded to the repo, 404'd, and killed the SW completely (fixed in
`90ed780`). **Any file added to `SHELL` must exist at the deployed path**, and after touching `SHELL` it's
worth confirming the SW actually reaches `activated` rather than assuming.

**2. Bump both version constants together.** `APP_VERSION` in `index.html` and `CACHE` in `sw.js`
(currently `"v11"` / `"ambient-v11"`) must move as a pair, or the service worker keeps serving stale files
after a deploy.

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
- **Browser**: `com.tcl.browser` (Play-updated, current) is the real one; `com.nes.browser` is a vendor
  system stub. **No default browser is set**, so a URL VIEW intent hits the chooser dialog — relevant to
  the LAUNCH card's behavior. WebView is **Chromium 120**, which covers every feature the app uses (CSS
  grid, `clamp()`, custom properties, WebAudio `AnalyserNode`, `speechSynthesis`, service workers,
  `AbortSignal.timeout`) — no polyfills needed.
- **Sideloading**: `adb install foo.apk` works directly and bypasses the "unknown sources" toggle.

### The display trap

Physical resolution is **1920×1080 but density is 320**, so Android halves it: a `width=device-width` page
gets a CSS viewport of **960 × 540**, not 1920 × 1080. This breaks the current bezel layout — at 540px
tall, `--tile: clamp(118px, 15.2vh, 176px)` pins to its 118px floor, leaving the stage row only ~252px
while each side rail tries to stack 4 × 118px = 472px of tiles under `overflow:hidden`. **Roughly half the
left/right tiles get clipped.** Verify against a real screenshot before redesigning — TV browsers sometimes
force their own desktop viewport, which would change the math.

## Architecture

### Card contract (the extension point)

Almost everything is a "card" object registered via `registerCards([...])` near the bottom of `index.html`:

- Required: `id`, `pane`, `render(ctx, data)` → HTML string (or DOM Node).
- Optional: `init(ctx)`, `onKey(ctx, key)` (`"up"|"down"|"left"|"right"|"enter"|"back"` — return `true` to
  consume), `onText(ctx, str)`, `onInject(ctx, {kind, v})`, `onScene(ctx, config)`, `onTheme(ctx, name)`.
- Optional `renderCompact(ctx, data)` → small HTML for the card's rail/overview tile; cards without it get
  a placeholder dash tile.
- `sources: [{key, origin, path}]` declares what the card fetches (see Data layer).
- `pinned: true` cards sit in a fixed frame slot (currently only `clock`), never rotate into the stage, and
  are immune to scene deactivation.
- Malformed cards are dropped at registration, never fatal to the rest of the app.

Registered cards: `cardClock` (pinned), `cardWeather`, `cardMarkets`, `cardHeadlines`, `cardClaude`,
`cardRadio`, `cardVis`, `cardLaunch`, `cardScenes`, `cardSystem`.

### Shell / layout

One dominant center "stage" card surrounded by a bezel of `FRAME_SLOTS = 20` tiles — pinned cards anchor
the frame, then the rotating ring of active cards fills clockwise, then dashed fillers pad the remainder.
Two modes: `"stage"` (◄► rotates the staged card, ▲▼/OK go to its `onKey`, BACK zooms out) and
`"overview"` (grid of all cards; OK zooms one in, including re-activating a scene-deactivated card).
All cards refresh on a shared 5s scheduler (`refreshCard`, gated per-card by `refresh` seconds plus
jitter) regardless of which is staged. Physical keydown and phone-companion messages both funnel through
the single `route(k)` function.

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

One `<audio>` element app-wide (`audioBus`). CORS-clean streams (`cors:true` — the Radio Paradise / SomaFM
presets) route through an `AudioContext`/`AnalyserNode` for real FFT; everything else (live-fetched Greek
stations) plays natively, because routing a non-CORS stream through WebAudio would silently **mute** it,
and `getLevels()` returns synthetic motion instead. The visualizer just calls `ctx.audio.getLevels()` and
labels itself `live fft` vs `synthetic` from the return value.

### Themes, scenes, companion

`THEMES` swaps palettes via CSS custom properties. `SCENES` composes palette + active cards + staged card
+ per-card `config` delivered through `onScene`; user scenes merge from the `ambient.scenes` localStorage
key.

The glasses generate a 5-char room code and connect to the Worker's `/ws?room=CODE&role=display`;
`companion.html` on the phone connects as `role=remote`. The Worker gives each room its own Durable Object
(`class Room`) that blindly broadcasts to the other sockets. Phone message shapes: `{t:"key",k}` (drives
the same `route()`), `{t:"text",v}` (→ staged card's `onText`), `{t:"action",id}` (`scene.*`, `theme.*`,
`stage.<cardId>`), `{t:"inject",kind,v,target?}` (→ named card, or any card whose `accepts` lists that
kind; `chat`→`cardClaude`, `url`→`cardLaunch`). **The room code is pairing convenience, not
authentication** — the Worker's `ALLOW_ORIGIN` var is the actual lock and must be set to the Pages origin.

The same Worker proxies `/claude` (holds `ANTHROPIC_API_KEY` as a Worker secret; never client-side) and
`/feeds` (server-side RSS fetch + regex parse, keeping the client CORS-clean). The `wrangler.toml`
template is in the comment block at the bottom of `ambient-worker.js`.

## Visual language

The frame stays monotone HUD; color is reserved for carrying data meaning — `tempColor()` (cold-blue →
hot-red), `wxIcon()` (condition → icon+color), `srcColor()` (stable hash → per-source identity color), and
up/down deltas on markets. Keep new cards on that split: quiet frame, colorful data. Don't introduce new
chrome colors.

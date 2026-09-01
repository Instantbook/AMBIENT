# AMBIENT v5 — cockpit + audio + scenes + companion + Claude (steps 1–4, 6–9)

Single-file PWA, same deployment family as LEXIS/ΝΟΥΣ.

## Deploy (GitHub Pages ritual)
1. Repo under the lowercase `instantbook` org (e.g. `instantbook/ambient`), public.
2. Drop in: `index.html`, `companion.html`, `sw.js`, `manifest.json`, `icon.png`.
3. Settings → Pages → deploy from main branch root.
4. Open the Pages URL on the Pocket TV browser; add to home screen if offered.
5. Version bumps: change BOTH `APP_VERSION` in index.html AND `CACHE` in sw.js (now v5/ambient-v5).

## What's in this build
- Cockpit layout for the worn big screen: everything visible at once —
  peripheral rail tiles (compact live views) around one dominant center stage
- Overview zoom: BACK shows a grid of all cards; OK zooms the selection to stage
- Input: ◄► rotate which card holds the stage · ▲▼/OK act inside the stage card
  (headlines scroll, system theme-cycle) · BACK overview
- Card contract addition: optional renderCompact(ctx,data) → rail/overview tile;
  cards without it show a placeholder tile and still work
- Shell + card registry (broken card = skipped, never fatal)
- Audio bus (spec §8): ONE pipeline app-wide. CORS-clean stations get a real
  WebAudio AnalyserNode; non-CORS streams play natively (routing them through
  WebAudio would mute them) and the analyser abstraction returns synthetic
  motion — the visualizer works either way and labels which mode it's in
- RADIO card (listen): built-in CORS-clean presets (Radio Paradise, SomaFM
  Groove Salad / Deep Space One) + top Greek stations fetched live from
  radio-browser.info when online. ▲▼ choose, OK play/stop-toggle. Now-playing
  shows in the status strip and on the radio + visualizer tiles
- VISUALIZER card (listen): canvas over the shared analyser; style registry
  (bars / pulse / scope) — OK cycles, choice persists. Idle = near-still
  ambient motion, so it doubles as a screensaver card
- Data layer: per-source SWR cache in localStorage, last-known-good offline
- Theme bus: day-cyan / dim-amber / sea / high-contrast (cycle: system pane → OK)
- Cards: clock (Edmonton·Porto Cheli, second-sweep arcs) · weather (Open-Meteo,
  direct, keyless) · markets (CAD→EUR/USD via open.er-api + BTC/ETH via
  CoinGecko, direct) · headlines (via your Worker) · system

## Wire your Cloudflare Worker
system pane → relay field → your worker URL. Expected endpoint:

GET /feeds  →  [ { "title": "...", "source": "...", "link": "..." }, ... ]

(Worker fetches/parses your RSS list server-side; the client stays CORS-clean.)

## Test on the actual unit (build step 2 checklist)
- D-pad arrows drive panes/cards in the TV browser
- BT/USB keyboard: arrows + Enter + Esc work; text field opens TV keyboard
- Weather + markets populate over hotspot; kill Wi-Fi → OFFLINE + cached values

- SCENES card (system): saved compositions — palette + which panels are active
  + which card takes the stage. Built-ins: ALL PANELS, MORNING, FLIGHT
  (dim-amber, radio/markets off, visualizer staged), EVENING (sea palette,
  radio staged). OK applies; last scene persists across boots. In the
  overview, deactivated panels appear dimmed — OK on one re-adds it ad hoc.
  User scenes: JSON array in localStorage key "ambient.scenes" now,
  /AMBIENT/scenes/ once the bridge lands.

- PHONE COMPANION (spec §10): the glasses show a 5-char room code (CLAUDE card
  + status strip once linked). On the phone, open companion.html from the same
  Pages URL, enter the room code + Worker URL once — it reconnects automatically
  after that. The phone becomes: D-pad, keyboard-to-glasses, ask-Claude box
  (use the phone keyboard's mic for speak-to-type), scene/palette buttons, and
  jump-to-panel shortcuts. All input flows through the same router as the D-pad.
- CLAUDE card (assist): chat transcript rendered big on the glasses; replies
  spoken aloud via speechSynthesis (OK toggles voice, ▲▼ scrolls). Input from
  the paired phone or the on-screen TV keyboard. Requests go to YOUR Worker's
  /claude endpoint — your Anthropic API key lives there as a secret and never
  exists client-side. Last 20 messages persist locally.

## Worker deployment (required for feeds, companion, Claude)
`ambient-worker.js` is the reference implementation: /ws relay (Durable
Object per room), /claude proxy, /feeds RSS relay. wrangler.toml template is
at the bottom of the file. Deploy: paste into your worker, add the DO binding
+ migration, set FEEDS/MODEL/ALLOW_ORIGIN vars, then
`wrangler secret put ANTHROPIC_API_KEY`. Lock ALLOW_ORIGIN to your Pages
origin — the room code is convenience pairing, not authentication, so don't
leave the relay open to arbitrary origins.

## On-device audio checklist
- OK on Radio Paradise / Groove Salad → sound + "live fft" tag on visualizer
- OK on a Greek station → sound + "synthetic" tag (expected: no CORS headers)
- Station audio keeps playing while you rotate the stage to other cards
- If a fetched Greek stream is dead (they churn), pick another — the list is
  votes-ranked from radio-browser.info and refreshes hourly

## Next steps per spec
5. local bridge + microSD (radio list + scenes become /AMBIENT/ editable)
10. travel cards (journey, currency, phrasebook, docs, offline maps)

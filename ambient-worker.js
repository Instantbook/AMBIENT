/* ============================================================
   AMBIENT reference Worker — deploy on YOUR Cloudflare account
   Endpoints:
     GET  /ws?room=CODE&role=display|remote  → WebSocket relay
     GET  /feeds                              → [{title,source,link}]
     GET  /radio[?path=genre/ambient]         → [{label,url,city,meta}]
   Vars (wrangler.toml [vars]):
     FEEDS  — comma-separated RSS URLs
     ALLOW_ORIGIN — your Pages origin, e.g.
                    "https://instantbook.github.io"

   No secrets. The /claude proxy was removed along with the CLAUDE card:
   the device's own Google Assistant covers that need, and leaving an
   unauthenticated endpoint that spends API credit in place for a feature
   nothing calls is not worth it. ALLOW_ORIGIN sets a CORS header, which
   browsers honour and curl ignores — it was never protection.

   Only /ws needs the ROOMS Durable Object. Deploy without it and /feeds
   and /radio still work; only the phone companion stays dark.
   ============================================================ */

// Several publishers reject requests without one, and a 403 body is HTML
// that then regex-parses to zero rows — a silent empty result.
const UA = "Mozilla/5.0 (compatible; AMBIENT/1.0; " +
  "+https://instantbook.github.io/AMBIENT/)";

/* One element's text out of an <item> block, CDATA unwrapped. Namespaced
   names (content:encoded, dc:date) need the colon escaped, and the tag must
   not match a longer one that merely starts the same way - <link> and
   <linkTarget>, <title> and <titleAlt> - hence the [\s>] boundary. */
const pick = (block, tag) => {
  const t = tag.replace(/[:.]/g, "\\$&");
  const m = new RegExp("<" + t + "(?:\\s[^>]*)?>([\\s\\S]*?)<\\/" + t + ">")
    .exec(block);
  if (!m) return "";
  return m[1].replace(/^\s*<!\[CDATA\[/, "").replace(/\]\]>\s*$/, "").trim();
};

const CORS = env => ({
  "Access-Control-Allow-Origin": env.ALLOW_ORIGIN || "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
});

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS")
      return new Response(null, { headers: CORS(env) });

    /* ---- WebSocket relay: hand the room to its Durable Object ---- */
    if (url.pathname === "/ws") {
      const room = (url.searchParams.get("room") || "").toUpperCase();
      if (!/^[A-Z2-9]{4,8}$/.test(room))
        return new Response("bad room", { status: 400 });
      const id = env.ROOMS.idFromName(room);
      return env.ROOMS.get(id).fetch(req);
    }

    /* ---- radio: curated Greek stations from greek-radio.gr ----
       Replaces radio-browser.info, whose votes-ranked list was 7/8 plain
       http and therefore blocked as mixed content on an https page. Every
       stream here is https AND sends CORS headers, so these also drive the
       real FFT visualiser instead of the synthetic fallback.
       ?path= browses the site's own taxonomy: genre/ambient, crete/chania,
       central-macedonia/thessaloniki. Default is the curated front page. */
    if (url.pathname === "/radio") {
      const raw = url.searchParams.get("path") || "";
      // no scheme, no host, no traversal - this only ever walks that site
      const path = /^[a-z0-9][a-z0-9/-]{0,60}$/.test(raw) && !raw.includes("..")
        ? raw : "";
      const src = "https://greek-radio.gr/" + path;
      try {
        const r = await fetch(src, {
          cf: { cacheTtl: 900 },
          headers: { "User-Agent": UA, "Accept": "text/html" },
        });
        if (!r.ok) return json({ error: "upstream " + r.status }, 502, env);
        const html = await r.text();
        const out = [], seen = new Set();
        const tagRx = /<[^>]+data-stream-url="[^"]*"[^>]*>/g;
        let m;
        while ((m = tagRx.exec(html))) {
          const tag = m[0];
          const at = n => {
            const g = new RegExp('data-' + n + '="([^"]*)"').exec(tag);
            return g ? decode(g[1]) : "";
          };
          const stream = at("stream-url");
          if (!stream || seen.has(stream)) continue;
          // Plain http is blocked as mixed content on an https page, so the
          // station would appear, be selectable, and silently never play.
          // Two or three per listing are like this. Dropping them here is
          // the difference between a short list that works and a longer one
          // that lies.
          if (!/^https:/i.test(stream)) continue;
          seen.add(stream);
          // "Αθήνα · 102.2 FM" -> city is the grouping key the card sorts on
          const meta = at("meta");
          out.push({
            label: at("title") || "STATION",
            url: stream,
            city: (meta.split("·")[0] || "").trim(),
            meta,
            logo: at("logo"),
            link: at("href"),
          });
        }
        return json(out, 200, env);
      } catch (e) {
        return json({ error: "fetch failed" }, 502, env);
      }
    }

    /* ---- tale: one turn of a text adventure ----
       The whole story never goes to the model. Each turn sends a compact
       state object - a running summary, location, inventory, chapter - and
       the choice just taken, so a request costs the same on turn 3 as on
       turn 300. Growing a transcript instead would get slower and dearer
       every turn and eventually hit the context window. */
    if (url.pathname === "/tale") {
      if (req.method !== "POST")
        return json({ error: "POST only" }, 405, env);
      if (!env.ANTHROPIC_API_KEY)
        return json({ error: "no API key on this worker" }, 503, env);
      let body;
      try { body = await req.json(); }
      catch (e) { return json({ error: "bad json" }, 400, env); }

      const cat = String(body.category || "").slice(0, 60);
      const choice = String(body.choice || "").slice(0, 200);
      const st = body.state && typeof body.state === "object"
        ? body.state : null;

      /* Caps are enforced HERE, not just requested in the prompt: a state
         object is round-tripped through the client, so it is only as
         bounded as the server makes it. */
      /* The last few exchanges, verbatim. The summary alone is lossy by
         design - it is rewritten every turn - so without this the model
         has no fine-grained memory of what just happened and continuity
         drifts over a long game. Three is enough to keep a scene coherent
         and still bounded: ~200 tokens whatever the turn number. */
      const recent = (Array.isArray(body.recent) ? body.recent : [])
        .slice(-3)
        .map(x => ({
          choice: String((x && x.choice) || "").slice(0, 120),
          prose: String((x && x.prose) || "").slice(0, 300),
        }));

      const clean = st ? {
        title: String(st.title || "").slice(0, 80),
        location: String(st.location || "").slice(0, 80),
        summary: String(st.summary || "").slice(0, 1200),
        inventory: (Array.isArray(st.inventory) ? st.inventory : [])
          .slice(0, 12).map(x => String(x).slice(0, 40)),
        chapter: Math.max(1, Math.min(99, parseInt(st.chapter, 10) || 1)),
      } : null;

      const SYS =
        "You are the narrator of an ORIGINAL interactive text adventure. " +
        "Invent your own world, characters and names - never use settings, " +
        "characters or plots from existing books, films or games.\n\n" +
        "Every turn you return:\n" +
        "- prose: 80-130 words, second person, present tense. Concrete and " +
        "sensory. Advance the situation; do not recap what the player just " +
        "did. End at a moment that demands a decision.\n" +
        "- choices: exactly 3 or 4 options, each under 60 characters, " +
        "phrased as actions the player takes. Make them genuinely " +
        "different in KIND - not three ways to do the same thing - and " +
        "never label them with letters or numbers.\n" +
        "- state: the updated world. summary is a running account of what " +
        "has happened, under 900 characters, rewritten each turn rather " +
        "than appended to. You will be given the last three exchanges " +
        "verbatim next turn, so the summary carries everything OLDER " +
        "than those - anything it drops is gone for good. Keep " +
        "inventory to what the player actually holds.\n\n" +
        "Let consequences stick. A choice that should end badly may end " +
        "badly. Set ending true only when the story genuinely concludes - " +
        "aim for somewhere between 12 and 25 turns, not sooner. Even on " +
        "a final turn still return three choices; they are not shown " +
        "once the story has ended, so do not labour over them.";

      const prompt = clean
        ? "Continue the adventure.\n\nSTATE (summary covers everything " +
          "before the recent exchanges below):\n" +
          JSON.stringify(clean, null, 1) +
          (recent.length
            ? "\n\nRECENT, oldest first - these are verbatim, and the " +
              "summary already accounts for everything earlier:\n" +
              recent.map(x => "> chose: " + x.choice + "\n" + x.prose)
                .join("\n\n")
            : "") +
          "\n\nThe player chose: " + (choice || "(nothing - begin the turn)")
        : "Begin a new adventure. Category: " + (cat || "any") +
          ".\n\nOpen in the middle of something already happening - no " +
          "preamble, no character creation, no explanation of the rules. " +
          "Give it a short evocative title.";

      const SCHEMA = {
        type: "object",
        properties: {
          prose: { type: "string" },
          /* minItems:1 is the strongest guarantee available - structured
             outputs reject any other value ("For 'array' type, 'minItems'
             values other than 0 or 1 are not supported"), so 3-or-4 cannot
             be enforced here and stays a prompt preference. One is the
             constraint that matters anyway: a turn with NO choices is a
             dead end with nothing to press, which is what Sonnet returned
             on its first turn. Opus honoured the wording every time, which
             hid that the schema was enforcing nothing at all. */
          choices: {
            type: "array", items: { type: "string" }, minItems: 1,
          },
          state: {
            type: "object",
            properties: {
              title: { type: "string" },
              location: { type: "string" },
              summary: { type: "string" },
              inventory: { type: "array", items: { type: "string" } },
              chapter: { type: "integer" },
              ending: { type: "boolean" },
            },
            required: ["title", "location", "summary", "inventory",
                       "chapter", "ending"],
            additionalProperties: false,
          },
        },
        required: ["prose", "choices", "state"],
        additionalProperties: false,
      };

      try {
        const r = await fetch("https://api.anthropic.com/v1/messages", {
          method: "POST",
          headers: {
            "content-type": "application/json",
            "x-api-key": env.ANTHROPIC_API_KEY,
            "anthropic-version": "2023-06-01",
          },
          body: JSON.stringify({
            model: "claude-sonnet-5",
            max_tokens: 8000,
            /* Sonnet at medium effort. A turn of prose is not
               intelligence-bound, and on a worn display every second is
               one spent watching a blank panel - so the cheaper, faster
               model with room to raise effort later beats the dearer one
               held at medium. Refusal fallbacks came off with Opus: they
               are an Opus/Fable feature, and stop_reason is still checked
               below, so a declined turn reports itself rather than
               silently returning nothing. */
            output_config: {
              effort: "medium",
              format: { type: "json_schema", schema: SCHEMA },
            },
            system: SYS,
            messages: [{ role: "user", content: prompt }],
          }),
        });
        if (!r.ok) {
          const t = await r.text();
          return json({ error: "api " + r.status,
                        detail: t.slice(0, 300) }, 502, env);
        }
        const d = await r.json();
        if (d.stop_reason === "refusal")
          return json({ error: "the story was declined" }, 502, env);
        const blk = (d.content || []).filter(b => b.type === "text")[0];
        if (!blk) return json({ error: "empty response" }, 502, env);
        let out;
        try { out = JSON.parse(blk.text); }
        catch (e) { return json({ error: "unparseable turn" }, 502, env); }
        out.usage = d.usage
          ? { in: d.usage.input_tokens, out: d.usage.output_tokens }
          : null;
        return json(out, 200, env);
      } catch (e) {
        return json({ error: "fetch failed" }, 502, env);
      }
    }

    /* ---- podcast: one show's episodes ----
       Podcast feeds are not CORS-clean (checked: 200 with no
       Access-Control-Allow-Origin on both libsyn and feedburner), so the
       page cannot read them itself. Discovery does not need us - the iTunes
       search API sends Access-Control-Allow-Origin: * and needs no key - so
       only this half comes through the Worker. */
    if (url.pathname === "/podcast") {
      /* Taking a URL from the client makes this a fetch proxy, so close the
         obvious abuse: https only, and nothing pointed back inside a
         private network. */
      let target;
      try { target = new URL(url.searchParams.get("feed") || ""); }
      catch (e) { return json({ error: "bad feed url" }, 400, env); }
      if (target.protocol !== "https:")
        return json({ error: "https only" }, 400, env);
      if (/^(localhost|\[?::1\]?|127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)/i
            .test(target.hostname))
        return json({ error: "refused" }, 400, env);
      try {
        const r = await fetch(target.toString(), {
          cf: { cacheTtl: 900 },
          headers: { "User-Agent": UA,
                     "Accept": "application/rss+xml, application/xml, text/xml" },
        });
        if (!r.ok) return json({ error: "feed " + r.status }, 502, env);
        const xml = (await r.text()).slice(0, 4000000);
        let show = decode((xml.match(/<title>([\s\S]*?)<\/title>/) || [])[1] || "");
        if (show.length > 60) show = show.slice(0, 60).trim();
        const art = (xml.match(/<itunes:image[^>]*href="([^"]+)"/i) || [])[1] || "";
        const blocks = xml.match(/<item[\s>][\s\S]*?<\/item>/g) || [];
        const episodes = [];
        for (const b of blocks) {
          /* 300 is generous for a browsable list and keeps a fifteen-year
             archive from arriving as a several-megabyte payload. */
          if (episodes.length >= 300) break;
          /* An item with no enclosure is not an episode - shows post
             text-only announcements into the same feed. */
          const enc = /<enclosure[^>]*>/i.exec(b);
          if (!enc) continue;
          const audio = (/url="([^"]+)"/i.exec(enc[0]) || [])[1] || "";
          if (!audio) continue;
          const when = pick(b, "pubDate") || pick(b, "published") ||
                       pick(b, "dc:date") || "";
          const ts = when ? Date.parse(decode(when)) : NaN;
          episodes.push({
            title: decode(pick(b, "title")) || "episode",
            url: audio,
            type: (/type="([^"]+)"/i.exec(enc[0]) || [])[1] || "audio/mpeg",
            bytes: +((/length="(\d+)"/i.exec(enc[0]) || [])[1] || 0),
            dur: decode(pick(b, "itunes:duration") || "").trim(),
            ts: isFinite(ts) ? ts : null,
          });
        }
        return json({ show, art, episodes }, 200, env);
      } catch (e) {
        return json({ error: "fetch failed" }, 502, env);
      }
    }

    /* ---- feeds: fetch + crude-parse RSS titles server-side ---- */
    if (url.pathname === "/feeds") {
      const feeds = (env.FEEDS || "").split(",")
        .map(s => s.trim()).filter(Boolean);
      /* Kept per feed, not in one pile, so the merge below can guarantee
         every source is represented. */
      const perFeed = feeds.map(() => []);
      await Promise.all(feeds.map(async (f, fi) => {
        const items = perFeed[fi];
        try {
          // Several publishers 403 a request with no User-Agent, and the
          // failure is invisible here: the 403 body is HTML, the item regex
          // matches nothing, and the feed silently contributes zero rows.
          const r = await fetch(f, {
            cf: { cacheTtl: 300 },
            headers: {
              "User-Agent": UA,
              "Accept": "application/rss+xml, application/xml, text/xml",
            },
          });
          if (!r.ok) return;
          const xml = await r.text();
          /* Publishers stuff their whole tagline into the channel title -
             "Al Jazeera – Breaking News, World News and Video from Al
             Jazeera" - and that is the label the card prints beside every
             row and hashes for the source colour. Keep the part before the
             first separator, and cap it. */
          const source = shortSource(
            (xml.match(/<title>(.*?)<\/title>/) || [])[1]
            || new URL(f).hostname);
          /* Grab whole <item> blocks and pick fields out of each one.
             Matching title/link/description in a single expression forced
             them into a fixed document order that real feeds do not agree
             on, and any feed that ordered them differently silently
             contributed nothing. */
          const blocks = xml.match(/<item[\s>][\s\S]*?<\/item>/g) || [];
          let n = 0;
          for (const b of blocks) {
            /* 8 was the whole reason the card looked frozen: five feeds
               could only ever produce forty rows between them, the client
               then showed twelve, and the top eight of a news feed barely
               move inside the 300s upstream cache - so a refresh returned
               a list that was genuinely identical. 40 gives a few hundred
               to page through; the summaries are already capped at 400
               chars, which keeps the payload around 150KB. */
            if (n >= 40) break;
            const title = decode(pick(b, "title"));
            if (!title) continue;
            const link = pick(b, "link") ||
              (/<link[^>]*href="([^"]+)"/.exec(b) || [])[1] || "";
            /* The summary is the whole point of opening a headline. Feeds
               put it in description, or content:encoded, or an Atom
               summary; take whichever exists, strip the markup publishers
               wrap it in, and cap it so one verbose feed cannot dominate
               the payload. */
            let sum = pick(b, "description") ||
              pick(b, "content:encoded") || pick(b, "summary") || "";
            sum = decode(sum).replace(/<[^>]*>/g, " ")
              .replace(/\s+/g, " ").trim();
            if (sum.length > 400) {
              /* Trim on a space, then drop a trailing lone surrogate: slice()
                 counts UTF-16 units, so cutting mid-pair leaves half a
                 character that renders as a black diamond. */
              sum = sum.slice(0, 400).replace(/\s+\S*$/, "")
                .replace(/[\uD800-\uDBFF]$/, "") + "…";
            }
            /* If the summary just repeats the title it is noise, not
               content - several feeds do exactly that. */
            if (sum && sum.toLowerCase().startsWith(
              title.toLowerCase().slice(0, 40))) sum = "";
            const when = pick(b, "pubDate") || pick(b, "published") ||
              pick(b, "updated") || pick(b, "dc:date") || "";
            const ts = when ? Date.parse(decode(when)) : NaN;
            items.push({
              title, source: decode(source), link: link.trim(),
              summary: sum,
              ts: isFinite(ts) ? ts : null,
            });
            n++;
          }
        } catch (e) { /* dead feed: skip */ }
      }));
      /* Newest first WITHIN a source, then one from each source in turn.
         Sorting the whole pile by date instead let a prolific publisher own
         every visible row - the Guardian posts often enough to fill all
         twelve on its own - which is how a varied set of feeds still ends
         up looking like a single source that never changes. Round-robin
         puts the newest story from each publisher in the first rows and
         keeps recency order inside each one. */
      perFeed.forEach(a => a.sort((x, y) => (y.ts || 0) - (x.ts || 0)));
      const items = [];
      for (let i = 0; ; i++) {
        let added = false;
        for (const a of perFeed) {
          if (a[i]) { items.push(a[i]); added = true; }
        }
        if (!added) break;
      }
      return json(items, 200, env);
    }

    return new Response("ambient worker", { headers: CORS(env) });
  },
};

function json(obj, status, env) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json", ...CORS(env) },
  });
}
/* A publisher's channel title down to something that fits beside a headline.
   Cut at the first separator, then hard-cap - the label is also what
   srcColor() hashes, so it has to be stable across refreshes, which rules
   out anything that depends on the item or the time. */
function shortSource(raw) {
  /* A colon usually has no space before it ("NPR Topics: News"), a dash
     always does ("France 24 - International ...") - requiring whitespace on
     both sides let the colon form through unchanged. */
  let s = decode(raw).split(/\s*[–—|]\s+|\s*:\s+|\s+-\s+/)[0].trim();
  if (s.length > 26) s = s.slice(0, 26).replace(/\s+\S*$/, "").trim();
  return s || "news";
}

/* A malformed entity must not take the whole feed down: String.fromCodePoint
   throws on anything outside the Unicode range, and one bad byte in one
   headline would otherwise reject the parse and drop that publisher
   entirely. Leave the text as-is instead. */
function safeChar(n) {
  try {
    if (!isFinite(n) || n < 0 || n > 0x10FFFF) return "";
    return String.fromCodePoint(n);
  } catch (e) { return ""; }
}

function decode(s) {
  // The channel <title> is matched without the CDATA handling the per-item
  // regex has, so BBC arrived as the literal "<![CDATA[BBC News]]>" - which
  // then showed as the source label and fed srcColor()'s hash.
  return String(s || "")
    .replace(/^\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*$/, "$1")
    // Numeric entities, decimal and hex. Publishers emit these constantly
    // for dashes and curly quotes - ΤΟ ΒΗΜΑ headlines arrived carrying a
    // literal "&#8211;" - and there are far too many to name one by one.
    .replace(/&#(\d+);/g, (_, n) => safeChar(parseInt(n, 10)))
    .replace(/&#[xX]([0-9a-fA-F]+);/g, (_, n) => safeChar(parseInt(n, 16)))
    .replace(/&nbsp;/g, " ").replace(/&hellip;/g, "…")
    .replace(/&lsquo;|&rsquo;/g, "'").replace(/&ldquo;|&rdquo;/g, '"')
    .replace(/&ndash;/g, "–").replace(/&mdash;/g, "—")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&#39;|&apos;/g, "'").replace(/&quot;/g, '"')
    // &amp; last, so "&amp;lt;" does not become a real "<"
    .replace(/&amp;/g, "&")
    .replace(/\s+/g, " ").trim();
}

/* ---- Room: one Durable Object per room code ----
   Broadcasts each message to every OTHER socket in the room, so
   remote → display and display → remote both work. Sockets are
   tagged with their role for future targeted routing.          */
export class Room {
  constructor(state) { this.state = state; }

  /** How many of each role are actually in the room right now. The phone
   *  used to show "linked" on ws.onopen alone, which only proves it reached
   *  the Worker - it said nothing about whether the glasses were there, so
   *  a dead display looked identical to a working one. */
  /** `gone` is the socket that is closing. It has to be excluded explicitly:
   *  getWebSockets() still lists it while webSocketClose is running, so
   *  announcing without this told the phone display:1 at the exact moment
   *  the glasses left - the one announcement that most needed to be right. */
  peers(gone) {
    let display = 0, remote = 0;
    for (const ws of this.state.getWebSockets()) {
      if (gone && ws === gone) continue;
      const tags = this.state.getTags(ws) || [];
      if (tags.indexOf("display") >= 0) display++; else remote++;
    }
    return { display, remote };
  }

  announce(gone) {
    const msg = JSON.stringify(
      Object.assign({ t: "peers" }, this.peers(gone)));
    for (const ws of this.state.getWebSockets()) {
      if (gone && ws === gone) continue;
      try { ws.send(msg); } catch (e) {}
    }
  }

  async fetch(req) {
    if (req.headers.get("Upgrade") !== "websocket")
      return new Response("expected websocket", { status: 426 });
    const role = new URL(req.url).searchParams.get("role") || "remote";
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server, [role]);
    this.announce();
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > 8192) return;
    // Heartbeat. An idle WebSocket can die at any hop in between without a
    // close frame ever arriving, so the client's onclose never fires and it
    // never retries - it just sits there believing it is connected. A ping
    // that stops being answered is the only reliable evidence. The pong
    // carries the peer counts, so both ends also learn who is present.
    if (msg.indexOf('"ping"') >= 0) {
      try {
        const m = JSON.parse(msg);
        if (m && m.t === "ping") {
          ws.send(JSON.stringify(Object.assign({ t: "pong" }, this.peers())));
          return;                       // never broadcast a heartbeat
        }
      } catch (e) { /* fall through and treat it as a normal message */ }
    }
    for (const other of this.state.getWebSockets())
      if (other !== ws)
        try { other.send(msg); } catch (e) {}
  }

  webSocketClose(ws) {
    try { ws.close(); } catch (e) {}
    this.announce(ws);
  }

  webSocketError(ws) {
    try { ws.close(); } catch (e) {}
    this.announce(ws);
  }
}

/* Deployment config now lives in wrangler.toml at the repo root. */


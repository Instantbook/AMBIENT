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

    /* ---- feeds: fetch + crude-parse RSS titles server-side ---- */
    if (url.pathname === "/feeds") {
      const feeds = (env.FEEDS || "").split(",")
        .map(s => s.trim()).filter(Boolean);
      const items = [];
      await Promise.all(feeds.map(async f => {
        try {
          // Several publishers 403 a request with no User-Agent, and the
          // failure is invisible here: the 403 body is HTML, the item regex
          // matches nothing, and the feed silently contributes zero rows.
          const r = await fetch(f, {
            cf: { cacheTtl: 300 },
            headers: {
              "User-Agent":
                "Mozilla/5.0 (compatible; AMBIENT/1.0; " +
                "+https://instantbook.github.io/AMBIENT/)",
              "Accept": "application/rss+xml, application/xml, text/xml",
            },
          });
          if (!r.ok) return;
          const xml = await r.text();
          const source = (xml.match(/<title>(.*?)<\/title>/) || [])[1]
            || new URL(f).hostname;
          const rx = /<item>[\s\S]*?<title>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?<\/title>[\s\S]*?<link>(.*?)<\/link>[\s\S]*?<\/item>/g;
          let m, n = 0;
          while ((m = rx.exec(xml)) && n < 6) {
            items.push({ title: decode(m[1]), source: decode(source),
              link: m[2].trim() });
            n++;
          }
        } catch (e) { /* dead feed: skip */ }
      }));
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
function decode(s) {
  // The channel <title> is matched without the CDATA handling the per-item
  // regex has, so BBC arrived as the literal "<![CDATA[BBC News]]>" - which
  // then showed as the source label and fed srcColor()'s hash.
  return String(s || "")
    .replace(/^\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*$/, "$1")
    .replace(/&amp;/g, "&").replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">").replace(/&#39;|&apos;/g, "'")
    .replace(/&quot;/g, '"').trim();
}

/* ---- Room: one Durable Object per room code ----
   Broadcasts each message to every OTHER socket in the room, so
   remote → display and display → remote both work. Sockets are
   tagged with their role for future targeted routing.          */
export class Room {
  constructor(state) { this.state = state; }
  async fetch(req) {
    if (req.headers.get("Upgrade") !== "websocket")
      return new Response("expected websocket", { status: 426 });
    const role = new URL(req.url).searchParams.get("role") || "remote";
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server, [role]);
    return new Response(null, { status: 101, webSocket: client });
  }
  webSocketMessage(ws, msg) {
    if (typeof msg !== "string" || msg.length > 8192) return;
    for (const other of this.state.getWebSockets())
      if (other !== ws)
        try { other.send(msg); } catch (e) {}
  }
  webSocketClose(ws) { try { ws.close(); } catch (e) {} }
}

/* Deployment config now lives in wrangler.toml at the repo root. */


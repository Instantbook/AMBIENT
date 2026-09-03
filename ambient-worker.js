/* ============================================================
   AMBIENT reference Worker — deploy on YOUR Cloudflare account
   Endpoints:
     GET  /ws?room=CODE&role=display|remote  → WebSocket relay
     POST /claude {messages:[{role,content}]} → Anthropic proxy
     GET  /feeds                              → [{title,source,link}]
   Secrets (wrangler secret put):
     ANTHROPIC_API_KEY   — your Anthropic key (never in the client)
   Vars (wrangler.toml [vars]):
     FEEDS  — comma-separated RSS URLs
     MODEL  — optional, default "claude-opus-5"
     ALLOW_ORIGIN — your Pages origin, e.g.
                    "https://instantbook.github.io"

   Only /ws needs the ROOMS Durable Object. Deploy without it and
   /feeds and /claude still work — headlines and the CLAUDE card come
   up, only the phone companion stays dark.
   ============================================================ */

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

    /* ---- Claude proxy: key lives HERE, server-side only ---- */
    if (url.pathname === "/claude" && req.method === "POST") {
      if (!env.ANTHROPIC_API_KEY)
        return json({ error: "no key configured" }, 500, env);
      let body;
      try { body = await req.json(); } catch (e) {
        return json({ error: "bad json" }, 400, env);
      }
      const messages = (body.messages || [])
        .filter(m => m && (m.role === "user" || m.role === "assistant")
          && typeof m.content === "string")
        .slice(-12);
      if (!messages.length)
        return json({ error: "no messages" }, 400, env);
      const r = await fetch("https://api.anthropic.com/v1/messages", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-api-key": env.ANTHROPIC_API_KEY,
          "anthropic-version": "2023-06-01",
        },
        body: JSON.stringify({
          model: env.MODEL || "claude-opus-5",
          // Deliberately small: the reply is spoken aloud and read on a HUD,
          // so a long answer is a worse answer here, not a truncated one.
          max_tokens: 600,
          // Thinking is on by default on Opus 5. Low effort keeps the glasses
          // responsive for short conversational turns; raise it if you start
          // asking the card real questions.
          output_config: { effort: "low" },
          system: "You are Claude on a wearable glasses display. " +
            "Replies are read on a HUD and spoken aloud: be concise, " +
            "plain prose, no markdown, no lists.",
          messages,
        }),
      });
      if (!r.ok)
        return json({ error: "anthropic " + r.status }, 502, env);
      const d = await r.json();
      const reply = (d.content || [])
        .filter(b => b.type === "text").map(b => b.text).join("\n");
      return json({ reply }, 200, env);
    }

    /* ---- feeds: fetch + crude-parse RSS titles server-side ---- */
    if (url.pathname === "/feeds") {
      const feeds = (env.FEEDS || "").split(",")
        .map(s => s.trim()).filter(Boolean);
      const items = [];
      await Promise.all(feeds.map(async f => {
        try {
          const r = await fetch(f, { cf: { cacheTtl: 300 } });
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
  return String(s || "").replace(/&amp;/g, "&").replace(/&lt;/g, "<")
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


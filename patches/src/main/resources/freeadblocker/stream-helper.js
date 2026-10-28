function startOptiSubFrameWebPage(_url) {
  "use strict";

  const PLAYER_PACKAGE = "app.gyrolet.mpvrx";
  const MAX_PLAYLISTS = 16;
  const playlists = new Map();
  const masterAnalyses = new Map();

  function animePlaybackMetadata() {
    // Hoster pages such as VidMoly often include the anime and episode in their HTML title,
    // even though the actual HLS URL is only named master.m3u8 or index.m3u8.
    const releaseTitle = document.title?.trim();
    const releaseMatch = releaseTitle?.match(/^(.+?)\s+S(\d{1,2})\s*E(\d{1,3})\b/i);
    if (releaseMatch) {
      const canonicalTitle = releaseMatch[1].replace(/[\s._-]+$/, "").trim();
      const season = Number(releaseMatch[2]);
      const episode = Number(releaseMatch[3]);
      if (canonicalTitle && !/^(vidmoly|voe|doodstream|filemoon)$/i.test(canonicalTitle) && season > 0 && episode > 0) {
        return {
          canonicalTitle,
          season,
          episode,
          playbackTitle: `${canonicalTitle} - S${String(season).padStart(2, "0")}E${String(episode).padStart(2, "0")}`
        };
      }
    }

    const candidates = [document.referrer, _url, location.href];
    for (const candidate of candidates) {
      if (!candidate) continue;
      let page;
      try { page = new URL(candidate, location.href); } catch { continue; }
      const hostname = page.hostname.toLowerCase();
      if (hostname !== "aniworld.to" && !hostname.endsWith(".aniworld.to")) continue;

      const match = page.pathname.match(/\/anime\/stream\/([^/]+)\/staffel-(\d+)\/episode-(\d+)(?:\/|$)/i);
      if (!match) continue;

      const onAniWorldPage = page.origin === location.origin;
      const pageTitle = onAniWorldPage
        ? document.querySelector(".series-title h1")?.textContent?.trim()
          || document.querySelector('meta[property="og:site_name"]')?.content?.trim()
        : "";
      let animeTitle = pageTitle;
      if (!animeTitle) {
        try { animeTitle = decodeURIComponent(match[1]); } catch { animeTitle = match[1]; }
        animeTitle = animeTitle.replace(/[-_]+/g, " ").replace(/\s+/g, " ").trim();
        animeTitle = animeTitle.replace(/\b[a-z]/g, letter => letter.toUpperCase());
      }

      const season = Number(match[2]);
      const episode = Number(match[3]);
      if (!animeTitle || !Number.isInteger(season) || !Number.isInteger(episode) || season < 1 || episode < 1) continue;
      return {
        canonicalTitle: animeTitle,
        season,
        episode,
        playbackTitle: `${animeTitle} - S${String(season).padStart(2, "0")}E${String(episode).padStart(2, "0")}`
      };
    }

    return null;
  }

  function parsePlaylist(value) {
    if (typeof value !== "string" || !value) return null;
    try {
      const url = new URL(value, location.href);
      if (!/^https?:$/.test(url.protocol) || !url.pathname.toLowerCase().endsWith(".m3u8")) return null;
      return url.href;
    } catch { return null; }
  }

  function isMaster(value) {
    return /master|urlset/i.test(new URL(value).pathname);
  }

  function saveAttribute(item, target) {
    const split = item.indexOf("=");
    if (split < 0) return;
    target[item.slice(0, split).trim().toUpperCase()] = item.slice(split + 1).trim().replace(/^"|"$/g, "");
  }

  function parseAttributes(text) {
    const result = {};
    let item = "";
    let quoted = false;
    for (const ch of text) {
      if (ch === '"') quoted = !quoted;
      if (ch === "," && !quoted) { saveAttribute(item, result); item = ""; }
      else item += ch;
    }
    saveAttribute(item, result);
    return result;
  }

  function resolveVariant(value, masterValue) {
    const master = new URL(masterValue);
    const variant = new URL(value, master);
    if (variant.origin === master.origin) {
      for (const [key, token] of master.searchParams) {
        if (!variant.searchParams.has(key)) variant.searchParams.append(key, token);
      }
    }
    return parsePlaylist(variant.href);
  }

  function parseMaster(text, masterUrl) {
    const lines = text.split(/\r?\n/);
    const variants = [];
    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i].trim();
      if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
      const attrs = parseAttributes(line.slice("#EXT-X-STREAM-INF:".length));
      let uriLine = "";
      for (let j = i + 1; j < lines.length; j += 1) {
        const candidate = lines[j].trim();
        if (!candidate || candidate.startsWith("#")) continue;
        uriLine = candidate; i = j; break;
      }
      if (!uriLine) continue;
      const uri = resolveVariant(uriLine, masterUrl);
      if (!uri) continue;
      const resolution = (attrs.RESOLUTION || "").match(/^(\d+)x(\d+)$/i);
      variants.push({
        uri,
        width: resolution ? Number(resolution[1]) : 0,
        height: resolution ? Number(resolution[2]) : 0,
        bandwidth: Number(attrs["AVERAGE-BANDWIDTH"] || attrs.BANDWIDTH || 0)
      });
    }
    return variants.sort((a, b) => b.height - a.height || b.width - a.width || b.bandwidth - a.bandwidth);
  }

  async function readMaster(masterUrl) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 6500);
    try {
      const response = await fetch(masterUrl, { credentials: "omit", cache: "no-store", signal: controller.signal });
      if (!response.ok) return [];
      const text = await response.text();
      if (!text.startsWith("#EXTM3U")) return [];
      return parseMaster(text, masterUrl);
    } catch {
      return [];
    } finally {
      clearTimeout(timeout);
    }
  }

  function addPlaylist(value) {
    const url = parsePlaylist(value);
    if (!url) return;
    playlists.set(url, Date.now());
    while (playlists.size > MAX_PLAYLISTS) playlists.delete(playlists.keys().next().value);
    if (isMaster(url) && !masterAnalyses.has(url)) {
      masterAnalyses.set(url, { state: "loading", variants: [] });
      readMaster(url).then(variants => {
        masterAnalyses.set(url, { state: variants.length ? "ready" : "unavailable", variants });
        render();
      });
    }
  }

  function ordered() {
    return [...playlists.keys()].sort((a, b) => {
      const aMaster = isMaster(a) ? 0 : 1;
      const bMaster = isMaster(b) ? 0 : 1;
      return aMaster - bMaster || playlists.get(b) - playlists.get(a);
    });
  }

  function qualityLabel(variant) {
    const quality = variant.height ? `${variant.height}p` : variant.quality || "Quality unknown";
    const bitrate = variant.bandwidth ? `${(variant.bandwidth / 1_000_000).toFixed(1)} Mbps` : "";
    return bitrate ? `${quality} | ${bitrate}` : quality;
  }

  function hintedQuality(value) {
    const match = value.match(/(?:^|[^0-9])([1-9][0-9]{2,3})p(?:[^a-z0-9]|$)/i);
    return match ? `${match[1]}p (from URL)` : "Quality unknown";
  }

  function getChoices() {
    const urls = ordered();
    const masters = urls.filter(isMaster);
    for (const master of masters) {
      const analysis = masterAnalyses.get(master);
      if (analysis?.state === "ready") return { state: "ready", choices: analysis.variants };
      if (analysis?.state === "loading") return { state: "loading", choices: [] };
    }
    if (!urls.length) return { state: "empty", choices: [] };
    const fallback = masters[0] || urls[0];
    const choices = urls.map(uri => ({ uri, height: 0, bandwidth: 0, quality: hintedQuality(uri) }));
    return { state: "auto", choices, selected: choices.find(choice => choice.uri === fallback) };
  }

  function scan() {
    for (const entry of performance.getEntriesByType("resource")) addPlaylist(entry.name);
    for (const video of document.querySelectorAll("video")) {
      addPlaylist(video.currentSrc); addPlaylist(video.src);
      for (const source of video.querySelectorAll("source[src]")) addPlaylist(source.src);
    }
    render();
  }

  const host = document.createElement("div");
  host.id = "fab-mpvrx-stream-helper";
  (document.documentElement || document.body).appendChild(host);
  const shadow = host.attachShadow({ mode: "closed" });
  shadow.innerHTML = `
    <style>
      :host { all: initial } * { box-sizing: border-box }
      .controls { position:fixed; z-index:2147483647; right:max(14px,env(safe-area-inset-right)); top:max(14px,env(safe-area-inset-top)); display:flex; gap:7px; align-items:flex-start; font:600 14px/1.2 system-ui,sans-serif }
      button { border:0; border-radius:999px; min-height:44px; padding:11px 15px; color:white; background:#512da8; box-shadow:0 3px 16px #0008; font:inherit; touch-action:manipulation }
      .more { padding-inline:13px; background:#292536 }
      .panel { position:fixed; z-index:2147483647; right:max(14px,env(safe-area-inset-right)); bottom:max(14px,env(safe-area-inset-bottom)); width:min(360px,calc(100vw - 28px)); max-height:min(62vh,460px); overflow:auto; padding:14px; border:1px solid #ffffff30; border-radius:16px; color:#f8f7ff; background:#181622f5; box-shadow:0 8px 28px #000a; font:14px/1.4 system-ui,sans-serif }
      [hidden] { display:none !important } .head { display:flex; justify-content:space-between; align-items:center; gap:12px } h2 { margin:0; font-size:16px }
      .close { color:#ddd; background:transparent; box-shadow:none; font-size:22px; padding:4px 10px; min-height:36px }
      .hint { color:#c5bfd7; font-size:12px } .row { padding:10px 0; border-top:1px solid #ffffff20 }
      .label { overflow-wrap:anywhere; color:#eee9ff; font-size:12px } .actions { display:flex; gap:8px; margin-top:8px }
      .actions button { flex:1; min-height:38px; border-radius:9px; padding:8px; font-size:12px } .copy { color:#211b31; background:#e7e0f7 }
      button:disabled { opacity:.75 }
    </style>
    <div class="controls" hidden><button class="more" type="button" aria-label="Choose quality">⋯</button><button class="open" type="button"></button></div>
    <section class="panel" role="dialog" aria-label="Available stream qualities" hidden>
      <div class="head"><h2>Available qualities</h2><button class="close" aria-label="Close">×</button></div>
      <p class="hint">The highest listed resolution is selected when the master playlist can be read. Auto means the provider blocked quality inspection.</p><div class="list"></div>
    </section>`;

  const controls = shadow.querySelector(".controls");
  const openButton = shadow.querySelector(".open");
  const panel = shadow.querySelector(".panel");
  const list = shadow.querySelector(".list");

  function label(value) {
    const url = new URL(value);
    const tail = url.pathname.split("/").filter(Boolean).slice(-2).join("/") || "playlist.m3u8";
    return `${url.hostname}/${tail}`;
  }
  function intentFor(value) {
    const url = new URL(value);
    const playbackMetadata = animePlaybackMetadata();
    const extras = playbackMetadata ? [
      `S.title=${encodeURIComponent(playbackMetadata.playbackTitle)}`,
      `S.filename=${encodeURIComponent(playbackMetadata.playbackTitle)}`,
      `S.introdb_title=${encodeURIComponent(playbackMetadata.canonicalTitle)}`,
      `i.introdb_season=${playbackMetadata.season}`,
      `i.introdb_episode=${playbackMetadata.episode}`
    ].map(extra => `;${extra}`).join("") : "";
    return `intent://${url.host}${url.pathname}${url.search}#Intent;scheme=${url.protocol.slice(0, -1)};package=${PLAYER_PACKAGE};type=application/vnd.apple.mpegurl${extras};end`;
  }
  function open(value) { window.location.href = intentFor(value); }
  async function copy(value) {
    try { await navigator.clipboard.writeText(value); }
    catch {
      const input = document.createElement("textarea"); input.value = value; input.style.cssText = "position:fixed;left:-10000px;top:0";
      (document.body || document.documentElement).appendChild(input); input.select(); document.execCommand("copy"); input.remove();
    }
  }
  function render() {
    const { state, choices } = getChoices();
    controls.hidden = state === "empty";
    openButton.disabled = state === "loading";
    openButton.textContent = state === "loading" ? "Reading qualities…" : state === "ready" ? `Open ${qualityLabel(choices[0])} in mpvRx` : "Open in mpvRx · Auto";
    list.replaceChildren();
    for (const [index, choice] of choices.entries()) {
      const row = document.createElement("div"); row.className = "row";
      const name = document.createElement("div"); name.className = "label";
      name.textContent = state === "ready" ? qualityLabel(choice) : `Auto · ${label(choice.uri)}`;
      const actions = document.createElement("div"); actions.className = "actions";
      const launch = document.createElement("button"); launch.textContent = index === 0 ? "Open selected" : "Open quality"; launch.addEventListener("click", () => open(choice.uri));
      const copyButton = document.createElement("button"); copyButton.className = "copy"; copyButton.textContent = "Copy link";
      copyButton.addEventListener("click", async () => { await copy(choice.uri); copyButton.textContent = "Copied"; setTimeout(() => copyButton.textContent = "Copy link", 1200); });
      actions.append(launch, copyButton); row.append(name, actions); list.appendChild(row);
    }
  }

  openButton.addEventListener("click", () => { const result = getChoices(); const choice = result.state === "auto" ? result.selected : result.choices[0]; if (choice) open(choice.uri); });
  shadow.querySelector(".more").addEventListener("click", () => { scan(); panel.hidden = false; });
  shadow.querySelector(".close").addEventListener("click", () => { panel.hidden = true; });
  scan();
  setInterval(scan, 1200);
}

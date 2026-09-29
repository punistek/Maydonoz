(function () {
  const UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36";

  function base() {
    return String(manifest.baseUrl || "https://dizipod.com").replace(/\/+$/, "");
  }

  function abs(raw) {
    if (!raw) return "";
    let s = String(raw).trim().replace(/&amp;/g, "&");
    if (!s) return "";
    if (/^https?:\/\//i.test(s)) return s;
    if (s.startsWith("//")) return "https:" + s;
    if (s.startsWith("/")) return base() + s;
    return base() + "/" + s.replace(/^\/+/, "");
  }

  function cleanHtml(s) {
    return String(s || "")
      .replace(/\\\//g, "/")
      .replace(/\\u0026/g, "&")
      .replace(/\\x26/g, "&")
      .replace(/&amp;/g, "&");
  }

  function stripTags(s) {
    return String(s || "")
      .replace(/<script[\s\S]*?<\/script>/gi, " ")
      .replace(/<style[\s\S]*?<\/style>/gi, " ")
      .replace(/<[^>]+>/g, " ")
      .replace(/&nbsp;/g, " ")
      .replace(/&#039;/g, "'")
      .replace(/&quot;/g, '"')
      .replace(/&amp;/g, "&")
      .replace(/\s+/g, " ")
      .trim();
  }

  function attr(tag, name) {
    const re = new RegExp(name + "\\s*=\\s*[\"']([^\"']*)[\"']", "i");
    const m = String(tag || "").match(re);
    return m ? cleanHtml(m[1]) : "";
  }

  function meta(html, key, value) {
    const tags = String(html || "").match(/<meta\b[^>]*>/gi) || [];
    for (const t of tags) {
      if (attr(t, key).toLowerCase() === String(value).toLowerCase()) return attr(t, "content");
    }
    return "";
  }

  async function getText(url, referer) {
    const headers = {
      "User-Agent": UA,
      "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7"
    };
    if (referer) headers["Referer"] = referer;
    const r = await fetch(url, { headers });
    return await r.text();
  }

  async function postForm(url, data, referer) {
    const body = Object.keys(data).map(k =>
      encodeURIComponent(k) + "=" + encodeURIComponent(data[k])
    ).join("&");
    const r = await fetch(url, {
      method: "POST",
      headers: {
        "User-Agent": UA,
        "Accept": "*/*",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer": referer || base() + "/",
        "X-Requested-With": "XMLHttpRequest"
      },
      body
    });
    return await r.text();
  }

  function typeFor(url) {
    return String(url).includes("/film/") ? "movie" : "series";
  }

  function parseCards(html) {
    const out = [];
    const seen = {};
    const src = cleanHtml(html);
    const aRe = /<a\b[^>]*href=["']([^"']*(?:\/film\/|\/diziler\/)[^"']*)["'][^>]*>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = aRe.exec(src)) !== null) {
      const url = abs(m[1]);
      if (!url || url === base() + "/diziler/" || url === base() + "/filmler/" || seen[url]) continue;
      seen[url] = true;

      const block = m[0];
      const inner = m[2];
      const img = (block.match(/<img\b[^>]*>/i) || [""])[0];
      let title = attr(m[0].match(/^<a\b[^>]*>/i)?.[0] || "", "title");
      if (!title) title = attr(img, "alt");
      if (!title) title = stripTags(inner);
      if (!title || title.length > 180) continue;

      let poster = attr(img, "data-src") || attr(img, "data-lazy-src") ||
                   attr(img, "data-original") || attr(img, "src");
      poster = abs(poster);
      const ym = stripTags(block).match(/\b(19|20)\d{2}\b/);
      const item = {
        title,
        url,
        posterUrl: poster || "https://www.google.com/s2/favicons?domain=dizipod.com&sz=256",
        type: typeFor(url)
      };
      if (ym) item.year = Number(ym[0]);
      out.push(new MultimediaItem(item));
    }
    return out;
  }

  async function getHome(cb) {
    try {
      const cats = [
        ["Diziler", "/diziler/"],
        ["Filmler", "/filmler/"],
        ["Netflix", "/yapim/netflix/"],
        ["Prime Video", "/yapim/prime-video/"],
        ["HBO", "/yapim/hbo/"],
        ["National Geographic", "/yapim/national-geographic/"],
        ["Hulu", "/yapim/hulu/"]
      ];
      const pages = await Promise.all(cats.map(async c => {
        try { return [c[0], parseCards(await getText(base() + c[1], base() + "/"))]; }
        catch (e) { console.log("DIZIPOD HOME " + c[0] + " ERROR " + e); return [c[0], []]; }
      }));
      const data = {};
      for (const p of pages) data[p[0]] = p[1];
      cb({ success: true, data });
    } catch (e) {
      cb({ success: false, errorCode: "HOME_ERROR", message: String(e) });
    }
  }

  async function search(query, pageOrCb, maybeCb) {
    const cb = typeof pageOrCb === "function" ? pageOrCb : maybeCb;
    try {
      const ajax = base() + "/wp/wp-admin/admin-ajax.php";
      const attempts = [
        { action: "dp_live_search", query },
        { action: "dp_live_search", search: query },
        { action: "dp_live_search", term: query }
      ];
      for (const data of attempts) {
        try {
          const items = parseCards(await postForm(ajax, data, base() + "/"));
          if (items.length) return cb({ success: true, data: items });
        } catch (_) {}
      }
      const html = await getText(base() + "/?s=" + encodeURIComponent(query), base() + "/");
      cb({ success: true, data: parseCards(html) });
    } catch (e) {
      cb({ success: false, errorCode: "SEARCH_ERROR", message: String(e) });
    }
  }

  function findTitle(html) {
    const h1 = String(html).match(/<h1\b[^>]*>([\s\S]*?)<\/h1>/i);
    if (h1) return stripTags(h1[1]);
    return meta(html, "property", "og:title").replace(/\s*-\s*Dizipod.*$/i, "").trim();
  }

  function findEpisodes(html) {
    const eps = [];
    const seen = {};
    const src = cleanHtml(html);
    const re = /<a\b[^>]*href=["']([^"']*-(\d+)-sezon-(\d+)-bolum\/?[^"']*)["'][^>]*>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = re.exec(src)) !== null) {
      const url = abs(m[1]);
      if (!url || seen[url]) continue;
      seen[url] = true;
      const season = Number(m[2]);
      const episode = Number(m[3]);
      const label = stripTags(m[4]) || (episode + ". Bölüm");
      eps.push(new Episode({ name: label, url, season, episode }));
    }
    eps.sort((a,b) => (a.season-b.season) || (a.episode-b.episode));
    return eps;
  }

  async function load(url, cb) {
    try {
      const html = await getText(url, base() + "/");
      const title = findTitle(html);
      if (!title) return cb({ success: false, errorCode: "NOT_FOUND", message: "Başlık bulunamadı" });

      const poster = abs(meta(html, "property", "og:image")) ||
        "https://www.google.com/s2/favicons?domain=dizipod.com&sz=256";
      const description = meta(html, "name", "description");
      const ym = stripTags(html).match(/\b(19|20)\d{2}\b/);
      const type = typeFor(url);

      const obj = { title, url, posterUrl: poster, type, description };
      if (ym) obj.year = Number(ym[0]);

      if (type === "series") {
        obj.episodes = findEpisodes(html);
      }
      cb({ success: true, data: new MultimediaItem(obj) });
    } catch (e) {
      cb({ success: false, errorCode: "LOAD_ERROR", message: String(e) });
    }
  }

  function extractPostId(html) {
    let m = String(html).match(/id=["']episode-player-container["'][^>]*data-post-id=["'](\d+)["']/i);
    if (!m) m = String(html).match(/data-post-id=["'](\d+)["']/i);
    if (!m) m = String(html).match(/postid-(\d+)/i);
    if (!m) m = String(html).match(/["']post_id["']\s*[:=]\s*["']?(\d+)/i);
    return m ? m[1] : "";
  }

  function extractEmbeds(text) {
    const out = [];
    const seen = {};
    const src = cleanHtml(text);
    const re = /https?:\/\/player\.dizipod\.com\/embed\/[^"'\\\s<]+/gi;
    let m;
    while ((m = re.exec(src)) !== null) {
      const u = m[0];
      if (!seen[u]) { seen[u] = true; out.push(u); }
    }
    const ifr = /<iframe\b[^>]*(?:src|data-src)=["']([^"']+)["'][^>]*>/gi;
    while ((m = ifr.exec(src)) !== null) {
      const u = abs(m[1]);
      if (u && !seen[u]) { seen[u] = true; out.push(u); }
    }
    return out;
  }

  function extractHls(text) {
    const out = [];
    const seen = {};
    const src = cleanHtml(text);
    const direct = /https?:\/\/[^"'\\\s<>]+(?:\.m3u8|\/gomindex\.m3u8)(?:\?[^"'\\\s<>]*)?/gi;
    let m;
    while ((m = direct.exec(src)) !== null) {
      const u = m[0];
      if (!seen[u]) { seen[u] = true; out.push(u); }
    }
    const mediaBase = /https?:\/\/[^"'\\\s<>]+\/storage\/media\/[^"'\\\s<>]+?\.mp4\/?/gi;
    while ((m = mediaBase.exec(src)) !== null) {
      const u = m[0].replace(/\/+$/, "") + "/gomindex.m3u8";
      if (!seen[u]) { seen[u] = true; out.push(u); }
    }
    return out;
  }

  async function loadStreams(url, cb) {
    try {
      console.log("DIZIPOD STREAM START " + url);
      const detail = await getText(url, base() + "/");
      const postId = extractPostId(detail);
      if (!postId) return cb({ success: true, data: [] });

      const ajax = base() + "/wp/wp-admin/admin-ajax.php?action=get_episode_player&post_id=" + postId;
      const playerHtml = await getText(ajax, url);
      const embeds = extractEmbeds(playerHtml);
      const streams = [];
      const seen = {};

      for (const embed of embeds) {
        try {
          const headers = {
            "User-Agent": UA,
            "Referer": "https://player.dizipod.com/",
            "Origin": "https://player.dizipod.com",
            "Accept": "*/*"
          };
          const r = await fetch(embed, { headers });
          const html = await r.text();
          let candidates = extractHls(html);

          // External JS içinde kaynak varsa onu da tara.
          const scripts = [];
          const sr = /<script\b[^>]*src=["']([^"']+)["'][^>]*>/gi;
          let sm;
          while ((sm = sr.exec(html)) !== null) {
            let s = sm[1];
            if (s.startsWith("//")) s = "https:" + s;
            else if (s.startsWith("/")) s = "https://player.dizipod.com" + s;
            else if (!/^https?:\/\//i.test(s)) s = "https://player.dizipod.com/" + s.replace(/^\/+/, "");
            scripts.push(s);
          }
          for (const s of scripts) {
            try { candidates = candidates.concat(extractHls(await getText(s, embed))); } catch (_) {}
          }

          for (const hls of candidates) {
            if (seen[hls]) continue;
            seen[hls] = true;
            // CloudStream analizinde doğrulanan player bağlamını aynen koru.
            streams.push(new StreamResult({
              url: hls,
              quality: "HLS",
              headers: {
                "User-Agent": UA,
                "Referer": "https://player.dizipod.com/",
                "Origin": "https://player.dizipod.com"
              }
            }));
          }
        } catch (e) {
          console.log("DIZIPOD EMBED ERROR " + e);
        }
      }

      console.log("DIZIPOD STREAM COUNT " + streams.length);
      cb({ success: true, data: streams });
    } catch (e) {
      cb({ success: false, errorCode: "STREAM_ERROR", message: String(e) });
    }
  }

  globalThis.getHome = getHome;
  globalThis.search = search;
  globalThis.load = load;
  globalThis.loadStreams = loadStreams;
})();

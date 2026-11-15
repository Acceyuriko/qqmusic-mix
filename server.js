const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 8788;
const ROOT = __dirname;
const COOKIE = fs.readFileSync(path.join(ROOT, '.cookie'), 'utf8').trim();
const UIN = (COOKIE.match(/(?:^|; )uin=([^;]+)/) || [])[1];
const SKEY = (COOKIE.match(/(?:^|; )qm_keyst=([^;]+)/) || [])[1];
const GTK = hash33(SKEY);
const GUID = '10000';
const PATTERN = ['fav', 'radar', 'fav', 'fav', 'radar'];
const BUFFER_SIZE = 6;

function hash33(t) {
  let e = 0;
  for (let i = 0; i < t.length; i++) e += (e << 5) + t.charCodeAt(i);
  return 2147483647 & e;
}

function norm(s) {
  return (s || '')
    .replace(/[（(【\[].*?[）)】\]]/g, '')
    .replace(/[\s\-_·,.!?，。！？、'"“”‘’:：]/g, '')
    .toLowerCase();
}

async function qq(block) {
  const body = {
    comm: {
      ct: 24, cv: 4747474, platform: 'yqq.json', uin: UIN,
      g_tk: GTK, g_tk_new_20200303: GTK, format: 'json',
      inCharset: 'utf-8', outCharset: 'utf-8', notice: 0, need_new_code: 1
    },
    req_0: block
  };
  const r = await fetch('https://u.y.qq.com/cgi-bin/musicu.fcg', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Cookie: COOKIE,
      Referer: 'https://y.qq.com/',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36'
    },
    body: JSON.stringify(body)
  });
  const j = await r.json();
  if (!j.req_0 || j.req_0.code !== 0) {
    throw new Error('QQ API ' + j.req_0?.code + ': ' + JSON.stringify(j).slice(0, 300));
  }
  return j.req_0.data;
}

function toTrack(t) {
  return {
    mid: t.mid,
    name: t.name || t.title,
    singer: (t.singer || []).map(s => s.name).join('/'),
    cover: t.album && t.album.mid
      ? 'https://y.gtimg.cn/music/photo_new/T002R300x300M000' + t.album.mid + '.jpg'
      : ''
  };
}

let favs = [];
let favMids = new Set();
let favNames = new Set();
let history = [];
let radarPage = 2;
let radarBuf = [];
let buffer = [];
let pos = 0;

async function loadFavs() {
  const num = 500;
  let begin = 0;
  const all = [];
  while (true) {
    const d = await qq({
      module: 'music.srfDissInfo.DissInfo',
      method: 'CgiGetDiss',
      param: { disstid: 0, dirid: 201, tag: true, song_begin: begin, song_num: num, userinfo: true, orderlist: true }
    });
    const list = d.songlist || [];
    all.push(...list);
    if (list.length < num || all.length >= d.total_song_num) break;
    begin += num;
  }
  return all.map(toTrack);
}

function pickFav() {
  const n = history.length;
  const idx = new Map();
  history.forEach((m, i) => idx.set(m, i));
  const weights = favs.map(f => {
    const i = idx.get(f.mid);
    return i === undefined ? n + 1 : i + 1;
  });
  const total = weights.reduce((a, b) => a + b, 0);
  let r = Math.random() * total;
  let last = favs[0];
  for (let i = 0; i < favs.length; i++) {
    r -= weights[i];
    if (r <= 0) return favs[i];
    last = favs[i];
  }
  return last;
}

async function fillRadar() {
  const d = await qq({
    module: 'music.recommend.TrackRelationServer',
    method: 'GetRadarSong',
    param: { Page: radarPage, Num: 20 }
  });
  const arr = d.VecSongs || d.vecSong || [];
  radarBuf = arr.map(x => toTrack(x.Track || x));
  radarPage++;
  if (!d.HasMore) radarPage = 2;
}

async function nextRadar() {
  for (let i = 0; i < 30; i++) {
    if (!radarBuf.length) await fillRadar();
    if (!radarBuf.length) return null;
    const t = radarBuf.shift();
    if (favMids.has(t.mid)) continue;
    if (favNames.has(norm(t.name))) continue;
    return t;
  }
  return null;
}

async function getUrl(mid) {
  const d = await qq({
    module: 'vkey.GetVkeyServer',
    method: 'CgiGetVkey',
    param: { guid: GUID, filename: ['M800' + mid + '.mp3'], songmid: [mid], songtype: [0], uin: UIN, loginflag: 1, platform: '20' }
  });
  const mi = d.midurlinfo && d.midurlinfo[0];
  if (!mi || !mi.purl) return null;
  return d.sip[0] + mi.purl;
}

async function buildOne() {
  const kind = PATTERN[pos++ % PATTERN.length];
  for (let i = 0; i < 6; i++) {
    let t;
    try {
      t = kind === 'fav' ? pickFav() : await nextRadar();
    } catch (e) {
      console.error('取歌失败', e.message);
      continue;
    }
    if (!t) continue;
    let url;
    try {
      url = await getUrl(t.mid);
    } catch (e) {
      continue;
    }
    if (!url) continue;
    if (kind === 'fav') {
      history = history.filter(m => m !== t.mid);
      history.unshift(t.mid);
    }
    return { ...t, url, kind };
  }
  return null;
}

async function fillBuffer() {
  while (buffer.length < BUFFER_SIZE) {
    const item = await buildOne();
    if (!item) break;
    buffer.push(item);
  }
}

function json(res, code, obj) {
  const s = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(s) });
  res.end(s);
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  try {
    if (u.pathname === '/api/next') {
      await fillBuffer();
      const current = buffer.shift() || null;
      await fillBuffer();
      return json(res, 200, { current, upcoming: buffer.slice(0, 5), favCount: favs.length, histCount: history.length });
    }
    if (u.pathname === '/api/health') {
      return json(res, 200, { favs: favs.length, history: history.length, radarPage, buffered: buffer.length });
    }
    let p = u.pathname === '/' ? '/index.html' : u.pathname;
    const file = path.join(ROOT, 'public', p);
    if (!file.startsWith(path.join(ROOT, 'public'))) { res.writeHead(403); return res.end(); }
    if (fs.existsSync(file)) {
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
      return fs.createReadStream(file).pipe(res);
    }
    res.writeHead(404); res.end('not found');
  } catch (e) {
    console.error(e);
    json(res, 500, { error: String(e.message || e) });
  }
});

(async () => {
  favs = await loadFavs();
  favMids = new Set(favs.map(f => f.mid));
  favNames = new Set(favs.map(f => norm(f.name)));
  console.log('收藏载入:', favs.length);
  await fillBuffer();
  console.log('预热完成，缓冲:', buffer.length);
  server.listen(PORT, '0.0.0.0', () => {
    console.log('服务已启动 http://localhost:' + PORT);
  });
})().catch(e => { console.error('启动失败', e); process.exit(1); });

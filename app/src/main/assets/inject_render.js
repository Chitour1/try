/* يُحقن في صفحة التصيير قبل أي رمز فيها: يتحكم في الزمن ويجهّز الالتقاط */
(() => {
  'use strict';
  window.__RENDER_MODE__ = true;
  const realRAF = window.requestAnimationFrame.bind(window);
  let vt = 0; const d0 = Date.now();
  performance.now = () => vt;
  Date.now = () => d0 + vt;
  const cbs = [];
  window.requestAnimationFrame = cb => { cbs.push(cb); return cbs.length; };
  window.cancelAnimationFrame = () => {};

  // لوحات الرسم في الذاكرة العادية كي تظهر عند الالتقاط
  const gc = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function (type, opts) {
    if (type === '2d') opts = Object.assign({}, opts || {}, { willReadFrequently: true });
    return gc.call(this, type, opts);
  };

  // لقطات الفيديو: تُعرض صورة الإطار المطلوب غلافًا للعنصر نفسه
  const pend = new Set();
  const meta = new WeakMap();
  const ctDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'currentTime');
  const duDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'duration');
  function prep(v) {
    if (meta.has(v)) return meta.get(v);
    const s = v.getAttribute('src') || (v.querySelector('source') && v.querySelector('source').getAttribute('src')) || '';
    const abs = new URL(s, location.href);
    const rel = decodeURIComponent(abs.pathname.replace(/^\/pkg\//, ''));
    const m = { rel, t: -1, dur: 0, auto: v.autoplay || v.loop, url: '' };
    try { m.dur = RenderBridge.videoDuration(rel); } catch (_) {}
    meta.set(v, m);
    v.removeAttribute('src');
    v.querySelectorAll('source').forEach(x => x.remove());
    v.removeAttribute('autoplay');
    v.preload = 'none';
    try { v.load(); } catch (_) {}
    return m;
  }
  function show(v, t) {
    const m = prep(v);
    const ms = Math.max(0, Math.round(t * 1000));
    if (m.t === ms) return;
    m.t = ms;
    const url = location.origin + '/vframe/' + ms + '/' + encodeURI(m.rel);
    const p = fetch(url).then(r => r.blob()).then(b => {
      const bu = URL.createObjectURL(b);
      const im = new Image(); im.src = bu;
      return im.decode().catch(() => {}).then(() => {
        if (m.url) URL.revokeObjectURL(m.url);
        m.url = bu; v.poster = bu;
      });
    }).catch(() => {}).finally(() => pend.delete(p));
    pend.add(p);
  }
  Object.defineProperty(HTMLMediaElement.prototype, 'currentTime', {
    configurable: true,
    get() { return this.tagName === 'VIDEO' ? Math.max(0, (meta.get(this) || { t: 0 }).t) / 1000 : ctDesc.get.call(this); },
    set(t) { if (this.tagName === 'VIDEO') show(this, +t || 0); else ctDesc.set.call(this, t); }
  });
  Object.defineProperty(HTMLMediaElement.prototype, 'duration', {
    configurable: true,
    get() { return this.tagName === 'VIDEO' ? prep(this).dur : duDesc.get.call(this); }
  });
  HTMLMediaElement.prototype.play = function () { return Promise.resolve(); };

  window.__tick = t => {
    vt = t;
    const list = cbs.splice(0);
    for (const cb of list) { try { cb(t); } catch (e) { console.error(e); } }
    try { document.getAnimations().forEach(a => { a.pause(); a.currentTime = t; }); } catch (_) {}
    document.querySelectorAll('video').forEach(v => {
      const m = prep(v);
      if (m.auto && m.dur > 0) show(v, (t / 1000) % m.dur);
    });
  };

  window.__settle = async () => {
    const imgs = [...document.images].filter(i => !i.complete);
    await Promise.all([...pend, ...imgs.map(i => new Promise(r => {
      i.addEventListener('load', r, { once: true }); i.addEventListener('error', r, { once: true }); setTimeout(r, 8000);
    }))]);
    if (document.fonts) { try { await document.fonts.ready; } catch (_) {} }
  };

  window.__step = async (ms, token) => {
    try {
      if (typeof window.renderFrame === 'function') { const r = window.renderFrame(ms); if (r && r.then) await r; }
      else window.__tick(ms);
      await window.__settle();
      await new Promise(r => realRAF(() => realRAF(r)));
    } catch (e) { console.error(e); }
    RenderBridge.ready(token);
  };

  // إطار الفيديو داخل الصفحة إن لم تكن الصفحة تملأ الشاشة
  function detectClip() {
    const vw = innerWidth, vh = innerHeight, ar = (window.__W || 1080) / (window.__H || 1920);
    const visible = el => {
      const b = el.getBoundingClientRect();
      let l = b.left, t = b.top, rr = b.right, bb = b.bottom;
      for (let p = el.parentElement; p && p !== document.documentElement; p = p.parentElement) {
        const cs = getComputedStyle(p);
        if (cs.overflowX !== 'visible' || cs.overflowY !== 'visible') {
          const q = p.getBoundingClientRect();
          l = Math.max(l, q.left); t = Math.max(t, q.top); rr = Math.min(rr, q.right); bb = Math.min(bb, q.bottom);
        }
      }
      l = Math.max(l, 0); t = Math.max(t, 0); rr = Math.min(rr, vw); bb = Math.min(bb, vh);
      return { x: l, y: t, w: Math.max(0, rr - l), h: Math.max(0, bb - t) };
    };
    let best = null;
    for (const el of document.querySelectorAll('body *')) {
      const r = visible(el);
      if (r.w < 60 || r.h < 60) continue;
      if (Math.abs(r.w / r.h - ar) / ar > 0.02) continue;
      const a = r.w * r.h;
      if (a >= vw * vh * 0.97 || a < vw * vh * 0.05) continue;
      if (!best || a > best.w * best.h) best = r;
    }
    return best;
  }

  window.addEventListener('load', () => {
    document.querySelectorAll('video').forEach(v => show(v, 0));
    const ready = document.fonts ? document.fonts.ready : Promise.resolve();
    ready.then(() => window.__settle()).then(() => setTimeout(() => {
      const hasFrame = typeof window.renderFrame === 'function';
      RenderBridge.loaded(JSON.stringify({ hasFrame, dur: window.DURATION || 0, clip: hasFrame ? null : detectClip() }));
    }, 500));
  });
})();

/* يُحقن في صفحة التصيير قبل أي رمز فيها: يتحكم في الزمن ويجهّز الالتقاط */
(() => {
  'use strict';
  window.__RENDER_MODE__ = true;
  // أقل عدد ممكن من الطبقات المستقلة: يمنع بقاء مربعات قديمة ويمنع الاهتزاز
  const st = document.createElement('style');
  st.textContent = '*,*::before,*::after{will-change:auto !important}';
  (document.head || document.documentElement).appendChild(st);
  const realRAF = window.requestAnimationFrame.bind(window);
  let vt = 0; const d0 = Date.now();
  performance.now = () => vt;
  Date.now = () => d0 + vt;
  const cbs = [];
  window.requestAnimationFrame = cb => { cbs.push(cb); return cbs.length; };
  window.cancelAnimationFrame = () => {};

  // لقطات الفيديو: نوقف التشغيل الذاتي ونضبط الزمن إطارًا إطارًا
  const auto = new WeakMap();
  const isAuto = v => { if (!auto.has(v)) auto.set(v, v.autoplay || v.loop); return auto.get(v); };
  const realPlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () { if (this.tagName === 'VIDEO') { this.pause(); return Promise.resolve(); } return realPlay.call(this); };

  window.__tick = t => {
    vt = t;
    const list = cbs.splice(0);
    for (const cb of list) { try { cb(t); } catch (e) { console.error(e); } }
    try { document.getAnimations().forEach(a => { a.pause(); a.currentTime = t; }); } catch (_) {}
    document.querySelectorAll('video').forEach(v => {
      if (!isAuto(v)) return;
      v.pause();
      const d = v.duration || 0;
      if (d > 0) { const tt = (t / 1000) % d; if (Math.abs(v.currentTime - tt) > 0.001) v.currentTime = tt; }
    });
  };

  window.__settle = async () => {
    const vids = [...document.querySelectorAll('video')];
    await Promise.all(vids.map(v => (v.seeking || v.readyState < 2) ? new Promise(r => {
      const done = () => r();
      v.addEventListener('seeked', done, { once: true });
      v.addEventListener('loadeddata', done, { once: true });
      setTimeout(done, 5000);
    }) : null));
    const imgs = [...document.images].filter(i => !i.complete);
    await Promise.all(imgs.map(i => new Promise(r => {
      i.addEventListener('load', r, { once: true }); i.addEventListener('error', r, { once: true }); setTimeout(r, 8000);
    })));
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
    const vw = innerWidth, vh = innerHeight, ar = 1080 / 1920;
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
    document.querySelectorAll('video').forEach(v => { isAuto(v); v.pause(); });
    const ready = document.fonts ? document.fonts.ready : Promise.resolve();
    ready.then(() => window.__settle()).then(() => setTimeout(() => {
      const hasFrame = typeof window.renderFrame === 'function';
      RenderBridge.loaded(JSON.stringify({ hasFrame, dur: window.DURATION || 0, clip: hasFrame ? null : detectClip() }));
    }, 500));
  });
})();

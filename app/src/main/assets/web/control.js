// CamLeon shared browser logic, used by the control page (/) and the remote view (/remote):
// hold-to-move gimbal pad and WebRTC live preview. Page-specific UI goes through callbacks.
window.CamLeon = (() => {
  async function api(path, method = 'GET') {
    const r = await fetch(path, { method });
    if (!r.ok) throw new Error(await r.text());
    return r.json().catch(() => ({}));
  }
  const post = path => api(path, 'POST').catch(() => ({}));

  // ---- manual gimbal control: hold-to-move (pointer + keyboard), repeated while held, stop on release.
  // The browser sends requests over parallel connections, so a stop could be processed BEFORE the move
  // issued just before it. Every move/stop therefore carries a client id + sequence number and the server
  // drops anything older than the newest it has seen from this client. Keep-alives are skipped while one
  // is still in flight, so they never pile up behind a slow response.
  // opts: { buttons: iterable of <button data-yaw data-pitch>, centre: element, speed: () => 10..100 }
  function pad(o) {
    let moving = null, moveTimer = null, moveSeq = 0, moveInFlight = false;
    const cid = Math.random().toString(36).slice(2, 8);
    const ctl = path => post(`${path}${path.includes('?') ? '&' : '?'}cid=${cid}&seq=${++moveSeq}`);
    function symbol(y, p) {
      if (!y && !p) return String.fromCodePoint(0x25CF);
      if (y > 0 && p > 0) return String.fromCodePoint(0x2197); if (y < 0 && p > 0) return String.fromCodePoint(0x2196);
      if (y > 0 && p < 0) return String.fromCodePoint(0x2198); if (y < 0 && p < 0) return String.fromCodePoint(0x2199);
      if (y > 0) return String.fromCodePoint(0x25B6); if (y < 0) return String.fromCodePoint(0x25C0);
      return p > 0 ? String.fromCodePoint(0x25B2) : String.fromCodePoint(0x25BC);
    }
    // Pad centre = the motion the gimbal is being told right now (dot when idle). While this page holds a
    // button it shows the local command; otherwise the page mirrors the phone's status (see `moving`).
    function show(y, p) { o.centre.textContent = symbol(y, p); o.centre.classList.toggle('on', !!(y || p)); }
    const sendMove = (y, p) => { const s = o.speed(); return ctl(`/api/gimbal/move?yaw=${y * s}&pitch=${p * s}`); };
    function start(y, p, btn) {
      if (moving) return; moving = [y, p];
      if (btn) btn.classList.add('held');
      show(y, p); sendMove(y, p);
      moveTimer = setInterval(() => {
        if (moveInFlight) return;
        moveInFlight = true; sendMove(y, p).finally(() => { moveInFlight = false; });
      }, 300);
    }
    function stop() {
      if (!moving) return; moving = null; clearInterval(moveTimer); moveTimer = null;
      for (const b of o.buttons) b.classList.remove('held');
      show(0, 0); ctl('/api/gimbal/stop');
    }
    for (const b of o.buttons) {
      const y = +b.dataset.yaw, p = +b.dataset.pitch;
      b.addEventListener('pointerdown', e => { e.preventDefault(); b.setPointerCapture(e.pointerId); start(y, p, b); });
      b.addEventListener('contextmenu', e => e.preventDefault());            // iOS long-press callout
      b.addEventListener('touchstart', e => e.preventDefault(), { passive: false });   // iOS text selection / magnifier
      ['pointerup', 'pointercancel', 'pointerleave'].forEach(ev => b.addEventListener(ev, stop));
    }
    const KEYS = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, 1], ArrowDown: [0, -1] };
    document.addEventListener('keydown', e => { if (KEYS[e.key] && !/INPUT|SELECT|TEXTAREA/.test(document.activeElement.tagName)) { e.preventDefault(); start(...KEYS[e.key]); } });
    document.addEventListener('keyup', e => { if (KEYS[e.key]) stop(); });
    window.addEventListener('blur', stop);
    document.addEventListener('visibilitychange', () => { if (document.hidden) stop(); });
    return { show, stop, get moving() { return moving; } };
  }

  // ---- WebRTC live preview: recvonly, non-trickle (all host candidates gathered, LAN only).
  // Starts automatically and keeps retrying while the app is reachable (e.g. after an app restart), but
  // stops after an ICE "no media path" failure — that is a network property, not transient.
  // opts: { video: <video>, onState(stateName), onStarted(), onStopped(), onFailed(kind, detail) }
  //   stateName: 'connecting' | RTCPeerConnection.connectionState; kind: 'nopath' | 'failed' | 'blocked'
  function preview(o) {
    let pc = null, pcId = null, wanted = true, noPath = false;
    async function start() {
      if (pc) return; wanted = true;
      pc = new RTCPeerConnection({ iceServers: [] });
      pc.addTransceiver('video', { direction: 'recvonly' });
      pc.ontrack = e => {
        o.video.srcObject = e.streams[0] || new MediaStream([e.track]);
        o.video.play().catch(() => o.onFailed('blocked', ''));      // autoplay policy → needs a tap
      };
      pc.onconnectionstatechange = () => {
        if (!pc) return;
        o.onState(pc.connectionState);
        if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') { teardown(); noPath = true; o.onFailed('nopath', ''); }
      };
      o.onState('connecting');
      const offer = await pc.createOffer(); await pc.setLocalDescription(offer);
      await new Promise(res => { if (pc.iceGatheringState === 'complete') res();
        else pc.addEventListener('icegatheringstatechange', () => pc.iceGatheringState === 'complete' && res()); setTimeout(res, 1500); });
      try {
        const r = await fetch('/api/webrtc/offer', { method: 'POST', headers: { 'Content-Type': 'application/sdp' }, body: pc.localDescription.sdp });
        if (!r.ok) throw new Error(await r.text());
        const a = await r.json(); pcId = a.id;
        await pc.setRemoteDescription({ type: 'answer', sdp: a.sdp });
        o.onStarted();
      } catch (e) { teardown(); o.onFailed('failed', e.message); }
    }
    function teardown() {
      if (pcId) { post('/api/webrtc/stop?id=' + pcId); pcId = null; }
      if (pc) { pc.close(); pc = null; }
      o.video.srcObject = null; o.onStopped();
    }
    // Safari pauses the <video> around modal dialogs / app switches and does not resume it by itself →
    // resume whenever it pauses while the connection is up (also when the page becomes visible again).
    const resume = () => { if (pc && o.video.srcObject && o.video.paused) o.video.play().catch(() => {}); };
    o.video.addEventListener('pause', resume);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) resume(); });
    // Leaving the page (link, reload, tab closed): free the phone-side encoder right away — the phone
    // would otherwise only notice via ICE after a grace period. sendBeacon survives unload.
    window.addEventListener('pagehide', () => { if (pcId) { navigator.sendBeacon('/api/webrtc/stop?id=' + pcId); pcId = null; } });
    return {
      start() { noPath = false; start(); },          // user tap: also after a "no media path" failure
      stop() { wanted = false; teardown(); },
      auto() { if (!pc && wanted && !noPath) start(); },
      get active() { return !!pc; },
    };
  }

  return { api, post, pad, preview };
})();

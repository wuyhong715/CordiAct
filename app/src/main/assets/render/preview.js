/*
 * CordiAct render surface — host page script.
 *
 * Responsibilities:
 *   1. own the stage geometry: one frame, or several side by side for a breakpoint compare,
 *   2. mount agent HTML into same-origin iframes together with a virtual-clock shim,
 *   3. answer host commands: setContent / setLayout / patch / play / pause / seek / step /
 *      snapshot / probe.
 *
 * Why iframes instead of writing into this document: the agent's HTML may carry <html>,
 * <body>, <style> and <script>. Only a separate document gives it a clean cascade, and only
 * a separate document keeps its globals away from the bridge object below.
 *
 * Every frame is same-origin, because we have to read its document to snapshot and to drive
 * its clock. That means agent code can reach this page, so everything crossing the bridge is
 * treated as untrusted data by Kotlin: sizes are clamped and snapshot nodes are re-validated.
 */
(function () {
  'use strict';

  /** Injected by WebViewCompat.addWebMessageListener. Absent when the page is opened in a
   *  plain browser for debugging. */
  var HOST = window.CordiActHost || null;
  var stage = document.getElementById('stage');
  var boot = document.getElementById('boot');

  var state = {
    layout: { frames: [{ w: 390, h: 844 }], primary: 0 },
    html: '',
    css: '',
    frozen: false,
    vt: 0
  };

  var MAX_NODES = 400;
  var MAX_FRAMES = 3;

  function post(type, payload) {
    if (!HOST || !HOST.postMessage) return;
    var msg = payload || {};
    msg.type = type;
    try { HOST.postMessage(JSON.stringify(msg)); } catch (e) { /* channel closed */ }
  }

  function fail(where, err) {
    post('error', {
      where: where,
      message: String(err && err.message ? err.message : err),
      stack: err && err.stack ? String(err.stack).slice(0, 4000) : null
    });
  }

  window.addEventListener('error', function (e) { fail('host', e.error || e.message); });
  window.addEventListener('unhandledrejection', function (e) { fail('host', e.reason); });

  // ---- virtual clock -------------------------------------------------------------
  //
  // Installed *inside each frame document before the agent's scripts run*, by prepending
  // SHIM_SOURCE to the document we write. It starts in pass-through mode so live preview
  // behaves normally, and switches to frozen mode the first time the host seeks. Flipping the
  // mode later is safe because the patched requestAnimationFrame was captured by the page at
  // parse time — that is the whole reason the shim is injected first rather than applied on
  // demand.
  var SHIM_SOURCE = '(' + function () {
    var pending = new Map();
    var nextId = 1;
    var frozen = false;
    var vt = 0;
    var realRAF = window.requestAnimationFrame.bind(window);
    var realNow = performance.now.bind(performance);

    window.requestAnimationFrame = function (cb) {
      var id = nextId++;
      pending.set(id, cb);
      if (!frozen) {
        realRAF(function (t) {
          if (pending.has(id)) { pending.delete(id); vt = realNow(); cb(vt); }
        });
      }
      return id;
    };
    window.cancelAnimationFrame = function (id) { pending.delete(id); };

    try { performance.now = function () { return frozen ? vt : realNow(); }; } catch (e) {}

    function drain(t) {
      var batch = Array.from(pending.values());
      pending.clear();
      for (var i = 0; i < batch.length; i++) {
        try { batch[i](t); } catch (err) { console.error('rAF listener failed', err); }
      }
    }

    window.__cordiactClock = {
      isFrozen: function () { return frozen; },
      now: function () { return frozen ? vt : realNow(); },
      seek: function (ms) {
        frozen = true;
        vt = ms;
        drain(vt);
        var animations = (document.getAnimations ? document.getAnimations({ subtree: true }) : []);
        for (var i = 0; i < animations.length; i++) {
          var a = animations[i];
          try {
            if (a.playState !== 'paused') a.pause();
            a.currentTime = vt;
          } catch (e) { /* finished/disposed animations throw; nothing to align */ }
        }
        return vt;
      },
      resume: function () {
        frozen = false;
        vt = realNow();
        return vt;
      }
    };
  }.toString() + ')();';

  // ---- frame bookkeeping ---------------------------------------------------------

  var frames = [];

  function ensureFrames(count) {
    count = Math.max(1, Math.min(MAX_FRAMES, count));
    while (frames.length > count) {
      var dropped = frames.pop();
      if (dropped.el.parentNode) dropped.el.parentNode.removeChild(dropped.el);
    }
    while (frames.length < count) {
      var el = document.createElement('iframe');
      el.className = 'frame';
      el.setAttribute('title', 'preview');
      stage.appendChild(el);
      frames.push({ el: el, mounted: false });
    }
  }

  function primaryIndex() {
    var index = Number(state.layout.primary) || 0;
    return Math.max(0, Math.min(frames.length - 1, index));
  }

  function primary() {
    return frames[primaryIndex()] || null;
  }

  function docFor(html, css) {
    // The shim goes first so the page's own scripts capture the patched rAF; <base> keeps
    // relative URLs pointing at the asset origin instead of the frame's about:blank.
    return '<!doctype html><html><head><meta charset="utf-8">' +
      '<base href="https://appassets.androidplatform.net/assets/render/">' +
      '<script>' + SHIM_SOURCE + '<\/script>' +
      '<style>' + (css || '') + '</style>' +
      '</head><body>' + (html || '') + '</body></html>';
  }

  function mountInto(frame, html, css) {
    var doc = frame.el.contentDocument;
    if (!doc) throw new Error('iframe document unavailable');
    doc.open();
    doc.write(docFor(html, css));
    doc.close();
    if (!frame.mounted) {
      frame.mounted = true;
      try {
        frame.el.contentWindow.addEventListener('error', function (e) {
          fail('frame', e.error || e.message);
        });
        frame.el.contentWindow.addEventListener('unhandledrejection', function (e) {
          fail('frame', e.reason);
        });
      } catch (e) { /* cross-origin should be impossible here */ }
    }
  }

  function mountAll() {
    for (var i = 0; i < frames.length; i++) mountInto(frames[i], state.html, state.css);
    state.vt = 0;
  }

  /** Applies layout to the stage: one frame centred, or several in a row, scaled to fit. */
  function applyLayout() {
    var layout = state.layout;
    ensureFrames(layout.frames.length);
    var gap = 12;
    var totalWidth = 0;
    var maxHeight = 0;

    stage.className = layout.frames.length > 1 ? 'grid' : 'single';
    stage.style.width = '';
    stage.style.height = '';

    for (var i = 0; i < frames.length; i++) {
      var spec = layout.frames[i] || layout.frames[0];
      var w = Math.max(80, Math.min(2000, Number(spec.w) || 390));
      var h = Math.max(80, Math.min(3000, Number(spec.h) || 844));
      frames[i].el.style.width = w + 'px';
      frames[i].el.style.height = h + 'px';
      totalWidth += w + (i > 0 ? gap : 0);
      maxHeight = Math.max(maxHeight, h);
    }

    // Fit the row inside the viewport, but never upscale: a preview that lies about pixel
    // size is worse than a small one.
    var availWidth = window.innerWidth - 16;
    var availHeight = window.innerHeight - 16;
    var scale = Math.min(1, availWidth / totalWidth, availHeight / maxHeight);
    if (!isFinite(scale) || scale <= 0) scale = 1;

    stage.style.width = totalWidth + 'px';
    stage.style.height = maxHeight + 'px';
    stage.style.transform = 'translate(-50%, -50%) scale(' + scale + ')';
  }

  // A CSS path rather than an index: the agent has to be able to relate a finding back to the
  // markup it wrote, and a path is stable as long as the document is not re-mounted.
  function cssPath(el, root) {
    var parts = [];
    var node = el;
    while (node && node.nodeType === 1 && node !== root) {
      var part = node.tagName.toLowerCase();
      if (node.id) { parts.unshift(part + '#' + node.id); break; }
      var parent = node.parentElement;
      if (parent) {
        var index = Array.prototype.indexOf.call(parent.children, node) + 1;
        part += ':nth-child(' + index + ')';
      }
      parts.unshift(part);
      node = node.parentElement;
    }
    return parts.length ? parts.join(' > ') : (el.tagName || 'body').toLowerCase();
  }

  function px(value) {
    var n = parseFloat(value);
    return isFinite(n) ? n : null;
  }

  function buildSnapshot(frame) {
    var doc = frame && frame.el.contentDocument;
    if (!doc || !doc.body) return { viewport: null, nodes: [] };
    var win = frame.el.contentWindow;
    var root = doc.body;
    var nodes = [];
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length && nodes.length < MAX_NODES; i++) {
      var el = all[i];
      var cs = win.getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      var r = el.getBoundingClientRect();
      var tag = el.tagName.toLowerCase();
      var role = el.getAttribute('role');
      var interactive = tag === 'a' || tag === 'button' || tag === 'input' ||
        tag === 'select' || tag === 'textarea' || tag === 'summary' || tag === 'label' ||
        role === 'button' || role === 'link' || el.hasAttribute('onclick') ||
        (el.hasAttribute('tabindex') && el.getAttribute('tabindex') !== '-1');
      nodes.push({
        selector: cssPath(el, root),
        tag: tag,
        parentSelector: el.parentElement && el.parentElement !== root
          ? cssPath(el.parentElement, root)
          : (el.parentElement === root ? 'body' : null),
        text: el.childElementCount === 0 ? String(el.textContent || '').trim().slice(0, 120) : null,
        rect: { x: r.left, y: r.top, w: r.width, h: r.height },
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
        scrollHeight: el.scrollHeight,
        clientHeight: el.clientHeight,
        positioned: cs.position === 'absolute' || cs.position === 'fixed',
        interactive: interactive,
        style: {
          color: cs.color,
          backgroundColor: cs.backgroundColor,
          fontSizePx: px(cs.fontSize),
          overflowX: cs.overflowX,
          overflowY: cs.overflowY
        }
      });
    }
    var bodyStyle = win.getComputedStyle(root);
    var pageBackground = bodyStyle.backgroundColor;
    return {
      viewport: { w: root.clientWidth, h: root.clientHeight, density: 1.0 },
      backgroundColor: pageBackground && pageBackground !== 'rgba(0, 0, 0, 0)'
        ? pageBackground
        : '#ffffff',
      nodes: nodes
    };
  }

  function eachClock(fn) {
    for (var i = 0; i < frames.length; i++) {
      var win = frames[i].el.contentWindow;
      if (win && win.__cordiactClock) fn(win.__cordiactClock);
    }
  }

  // ---- command surface -----------------------------------------------------------

  function handle(raw) {
    var cmd;
    try { cmd = typeof raw === 'string' ? JSON.parse(raw) : raw; }
    catch (e) { fail('host', 'bad command json: ' + e.message); return; }
    try {
      switch (cmd.type) {
        case 'setContent':
          state.html = String(cmd.html || '');
          state.css = String(cmd.css || '');
          if (cmd.viewport && state.layout.frames.length === 1) {
            state.layout.frames = [{
              w: Number(cmd.viewport.w) || 390,
              h: Number(cmd.viewport.h) || 844
            }];
          }
          boot && (boot.style.display = 'none');
          applyLayout();
          mountAll();
          post('mounted', { viewport: state.layout.frames[primaryIndex()], frames: frames.length });
          break;
        case 'setLayout': {
          var next = Array.isArray(cmd.frames) && cmd.frames.length ? cmd.frames : [{ w: 390, h: 844 }];
          state.layout = { frames: next.slice(0, MAX_FRAMES), primary: Number(cmd.primary) || 0 };
          applyLayout();
          // Remount so each frame lays out at its own width from a clean document.
          if (state.html) mountAll();
          post('layout', { frames: state.layout.frames, primary: state.layout.primary });
          break;
        }
        case 'patch':
          // Style-only or body-only swap: keeps scroll position and any running animation,
          // which is what makes sub-150ms iteration possible.
          if (typeof cmd.css === 'string') {
            state.css = cmd.css;
            for (var p = 0; p < frames.length; p++) {
              var doc0 = frames[p].el.contentDocument;
              var styleEl = doc0 && doc0.querySelector('head > style');
              if (styleEl) styleEl.textContent = cmd.css;
            }
          }
          if (typeof cmd.html === 'string') {
            state.html = cmd.html;
            for (var q = 0; q < frames.length; q++) {
              var doc1 = frames[q].el.contentDocument;
              if (doc1) doc1.body.innerHTML = cmd.html;
            }
          }
          post('patched', {});
          break;
        case 'setViewport': {
          var w = Number(cmd.w) || 390;
          var h = Number(cmd.h) || 844;
          state.layout.frames = [{ w: w, h: h }];
          state.layout.primary = 0;
          applyLayout();
          post('viewport', { w: w, h: h, scale: Number(cmd.scale) || 1 });
          break;
        }
        case 'seek': {
          var t = Number(cmd.t) || 0;
          eachClock(function (clock) { clock.seek(t); });
          state.frozen = true;
          state.vt = t;
          post('seeked', { t: t });
          break;
        }
        case 'step': {
          var from = cmd.from == null ? state.vt : Number(cmd.from);
          var to = from + (Number(cmd.ms) || 16);
          eachClock(function (clock) { clock.seek(to); });
          state.frozen = true;
          state.vt = to;
          post('seeked', { t: to });
          break;
        }
        case 'play':
          eachClock(function (clock) { clock.resume(); });
          state.frozen = false;
          post('playing', {});
          break;
        case 'pause':
          eachClock(function (clock) { clock.seek(clock.now()); });
          state.frozen = true;
          post('paused', { t: state.vt });
          break;
        case 'snapshot':
          post('snapshot', buildSnapshot(primary()));
          break;
        case 'probe': {
          // Everything the instrumented tests need to assert about the environment, answered
          // from inside the primary frame rather than inferred from Kotlin.
          var frame = primary();
          var win = frame && frame.el.contentWindow;
          var doc = frame && frame.el.contentDocument;
          var clock = win && win.__cordiactClock;
          var animations = [];
          try {
            animations = doc && doc.getAnimations ? doc.getAnimations({ subtree: true }) : [];
          } catch (e) { animations = []; }
          post('probe', {
            hasClock: !!clock,
            frozen: !!(clock && clock.isFrozen()),
            pageSawClock: !!(win && win.__sawClockAtParse),
            animationsApi: !!(doc && typeof doc.getAnimations === 'function'),
            animationCount: animations.length,
            animationTimes: animations.map(function (a) {
              return typeof a.currentTime === 'number' ? a.currentTime : null;
            }),
            nodeCount: doc ? doc.querySelectorAll('*').length : -1,
            frameCount: frames.length,
            // Declared width, not the measured rect: the stage is scaled to fit, so a rect
            // measurement would report the zoom level instead of the breakpoint.
            frameWidths: frames.map(function (f) { return Math.round(parseFloat(f.el.style.width) || 0); })
          });
          break;
        }
        case 'ping':
          post('ready', { ua: navigator.userAgent, frozen: state.frozen });
          break;
        default:
          fail('host', 'unknown command: ' + cmd.type);
      }
    } catch (e) {
      fail(cmd && cmd.type ? cmd.type : 'host', e);
    }
  }

  window.__cordiact = { handle: handle };
  ensureFrames(1);
  applyLayout();
  window.addEventListener('resize', function () {
    try { applyLayout(); } catch (e) { /* ignore */ }
  });
  post('ready', { ua: navigator.userAgent });
})();

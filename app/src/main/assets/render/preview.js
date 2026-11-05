/*
 * CordiAct render surface — host page script.
 *
 * Responsibilities:
 *   1. own the stage geometry (viewport + scale),
 *   2. mount agent HTML into a same-origin iframe together with a virtual-clock shim,
 *   3. answer host commands: setContent / patch / setViewport / play / pause / seek / step /
 *      snapshot.
 *
 * Why an iframe instead of writing into this document: the agent's HTML may carry <html>,
 * <body>, <style> and <script>. Only a separate document gives it a clean cascade, and only
 * a separate document keeps its globals away from the bridge object below.
 *
 * The iframe must be same-origin (sandbox="allow-scripts allow-same-origin") — we need to
 * read its document to snapshot and to drive its clock. That also means agent code *can*
 * reach this page, so everything crossing the bridge is treated as untrusted data by Kotlin:
 * sizes are clamped, snapshot nodes are attached only after re-validation.
 */
(function () {
  'use strict';

  /** Injected by WebViewCompat.addWebMessageListener. Absent when the page is opened in a
   *  plain browser for debugging. */
  var HOST = window.CordiActHost || null;
  var stage = document.getElementById('stage');
  var frame = document.getElementById('frame');
  var boot = document.getElementById('boot');

  var state = {
    viewport: { w: 390, h: 844, scale: 1 },
    html: '',
    css: '',
    frozen: false,
    vt: 0,            // virtual time, ms — what the preview believes "now" is
    lastStepAt: 0,
    installed: false,
    shimVersion: 0
  };

  var MAX_NODES = 400;

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
  // Installed *inside the iframe document before the agent's scripts run*, by prepending
  // SHIM_SOURCE to the document we write. It starts in pass-through mode so live preview
  // behaves normally, and switches to frozen mode the first time the host seeks. Flipping the
  // mode later is safe because the patched requestAnimationFrame was captured by the page at
  // parse time — that is the whole reason the shim must be injected first rather than applied
  // on demand.
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
      // Absolute seek: the host speaks in absolute timeline ms, the shim stores a cursor.
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

  // ---- content mounting ----------------------------------------------------------

  function docFor(html, css) {
    // The shim goes first so the page's own scripts capture the patched rAF; <base> keeps
    // relative URLs pointing at the asset origin instead of the iframe's about:blank.
    return '<!doctype html><html><head><meta charset="utf-8">' +
      '<base href="https://appassets.androidplatform.net/render/">' +
      '<script>' + SHIM_SOURCE + '<\/script>' +
      '<style>' + (css || '') + '</style>' +
      '</head><body>' + (html || '') + '</body></html>';
  }

  function mount(html, css) {
    var doc = frame.contentDocument;
    if (!doc) { fail('mount', 'iframe document unavailable'); return; }
    doc.open();
    doc.write(docFor(html, css));
    doc.close();
    if (!state.installed) {
      state.installed = true;
      frame.contentWindow.addEventListener('error', function (e) {
        fail('frame', e.error || e.message);
      });
      frame.contentWindow.addEventListener('unhandledrejection', function (e) {
        fail('frame', e.reason);
      });
    }
    state.vt = 0;
  }

  function applyViewport() {
    var vp = state.viewport;
    var w = Math.max(80, Math.min(2000, vp.w));
    var h = Math.max(80, Math.min(3000, vp.h));
    var scale = Math.max(0.1, Math.min(2, vp.scale));
    stage.style.width = w + 'px';
    stage.style.height = h + 'px';
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

  function buildSnapshot() {
    var doc = frame.contentDocument;
    if (!doc || !doc.body) return { viewport: null, nodes: [] };
    var root = doc.body;
    var nodes = [];
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length && nodes.length < MAX_NODES; i++) {
      var el = all[i];
      var cs = frame.contentWindow.getComputedStyle(el);
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
    var bodyStyle = frame.contentWindow.getComputedStyle(root);
    var pageBackground = bodyStyle.backgroundColor;
    return {
      viewport: { w: root.clientWidth, h: root.clientHeight, density: 1.0 },
      backgroundColor: pageBackground && pageBackground !== 'rgba(0, 0, 0, 0)'
        ? pageBackground
        : '#ffffff',
      nodes: nodes
    };
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
          if (cmd.viewport) { state.viewport = cmd.viewport; }
          boot && (boot.style.display = 'none');
          applyViewport();
          mount(state.html, state.css);
          post('mounted', { viewport: state.viewport });
          break;
        case 'patch':
          // Style-only or body-only swap: keeps scroll position and any running animation,
          // which is what makes sub-150ms iteration possible.
          if (typeof cmd.css === 'string') {
            var styleEl = frame.contentDocument.querySelector('head > style');
            if (styleEl) styleEl.textContent = cmd.css;
            state.css = cmd.css;
          }
          if (typeof cmd.html === 'string') {
            frame.contentDocument.body.innerHTML = cmd.html;
            state.html = cmd.html;
          }
          post('patched', {});
          break;
        case 'setViewport':
          state.viewport = {
            w: Number(cmd.w) || state.viewport.w,
            h: Number(cmd.h) || state.viewport.h,
            scale: cmd.scale == null ? state.viewport.scale : Number(cmd.scale)
          };
          applyViewport();
          post('viewport', state.viewport);
          break;
        case 'seek': {
          var clock = frame.contentWindow.__cordiactClock;
          if (!clock) { fail('seek', 'clock shim missing from document'); break; }
          var t = clock.seek(Number(cmd.t) || 0);
          state.frozen = true;
          state.vt = t;
          post('seeked', { t: t });
          break;
        }
        case 'step': {
          var clock2 = frame.contentWindow.__cordiactClock;
          if (!clock2) { fail('step', 'clock shim missing from document'); break; }
          var next = clock2.seek((cmd.from == null ? clock2.now() : Number(cmd.from)) + (Number(cmd.ms) || 16));
          state.vt = next;
          post('seeked', { t: next });
          break;
        }
        case 'play': {
          var clock3 = frame.contentWindow.__cordiactClock;
          state.frozen = false;
          if (clock3) clock3.resume();
          post('playing', {});
          break;
        }
        case 'pause': {
          var clock4 = frame.contentWindow.__cordiactClock;
          if (clock4) { state.vt = clock4.seek(clock4.now()); state.frozen = true; }
          post('paused', { t: state.vt });
          break;
        }
        case 'snapshot':
          post('snapshot', buildSnapshot());
          break;
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
  applyViewport();
  post('ready', { ua: navigator.userAgent });
})();

/*
 * Headless harness for app/src/main/assets/render/preview.js.
 *
 * The Android side cannot be compiled on this machine, so the only executable check available
 * is the page script itself. This stubs just enough DOM to run the real file: frames are
 * created by the script under test, each with its own vm context whose document.write()
 * actually evaluates the inline <script> (which is how the virtual clock gets installed), and a
 * fake host bridge records outgoing messages.
 *
 * Run with: node tools/preview-test.js  (no dependencies)
 */
'use strict';
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const assert = require('assert');

const SRC = fs.readFileSync(
  path.join(__dirname, '..', 'app/src/main/assets/render/preview.js'), 'utf8');

// ---- stubs ----------------------------------------------------------------------
const sent = [];
const host = { postMessage: (json) => sent.push(JSON.parse(json)) };

function makeElement(tag) {
  return {
    tagName: tag ? tag.toUpperCase() : 'DIV',
    style: {},
    className: '',
    textContent: '',
    childElementCount: 0,
    scrollWidth: 100, clientWidth: 100, scrollHeight: 100, clientHeight: 100,
    id: '', parentElement: null,
    setAttribute() {},
    getAttribute: () => null,
    hasAttribute: () => false,
    querySelector: () => null,
    querySelectorAll: () => [],
    getBoundingClientRect() {
      const w = parseFloat(this.style.width);
      return { left: 0, top: 0, width: isFinite(w) ? w : 390, height: 100 };
    },
  };
}

const stage = {
  style: {}, className: '', children: [],
  appendChild(el) { el.parentNode = stage; stage.children.push(el); },
  removeChild(el) {
    const i = stage.children.indexOf(el);
    if (i >= 0) stage.children.splice(i, 1);
    el.parentNode = null;
  },
};
const boot = makeElement();

/** One iframe world: a vm context playing the role of that frame's window. */
function makeFrameWorld() {
  const w = { console, Map, Array, Object, JSON, Math, String, Number, isFinite, parseFloat };
  w.window = w;
  w.navigator = { userAgent: 'harness-frame' };
  w.__anims = [];
  w.requestAnimationFrame = (cb) => { cb(w.performance.now()); return 1; };
  w.cancelAnimationFrame = () => {};
  w.performance = { now: () => 1000 };
  w.addEventListener = () => {};
  w.document = {
    body: makeElement('body'),
    getAnimations: () => w.__anims,
    querySelector: () => null,
    querySelectorAll: () => [],
    open() {}, close() {},
    write(html) {
      const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
      for (const code of scripts) vm.runInContext(code, w);
    },
  };
  w.getComputedStyle = () => ({
    display: 'block', visibility: 'visible', position: 'static',
    color: 'rgb(17, 17, 17)', backgroundColor: 'rgba(0, 0, 0, 0)',
    fontSize: '14px', overflowX: 'visible', overflowY: 'visible',
  });
  vm.createContext(w);
  return w;
}

const created = [];

const mainWindow = {
  CordiActHost: host,
  innerWidth: 1200,
  innerHeight: 1000,
  addEventListener: () => {},
  __cordiact: null,
};
mainWindow.window = mainWindow;
mainWindow.navigator = { userAgent: 'harness' };
mainWindow.document = {
  getElementById: (id) => ({ stage, boot }[id] || null),
  createElement: () => {
    const win = makeFrameWorld();
    const el = makeElement('iframe');
    el.contentWindow = win;
    el.contentDocument = win.document;
    created.push(el);
    return el;
  },
};

const ctx = vm.createContext(mainWindow);
vm.runInContext(SRC, ctx);

const api = mainWindow.__cordiact;
assert(api && typeof api.handle === 'function', 'command surface missing');
assert(sent.some((m) => m.type === 'ready'), 'ready not posted');
assert(created.length === 1, 'exactly one frame should exist after boot, got ' + created.length);

// ---- setContent: mounts, installs the clock -------------------------------------
api.handle(JSON.stringify({
  type: 'setContent',
  html: '<script>window.__sawClockAtParse = (typeof window.__cordiactClock === "object");</script><h1>你好</h1>',
  css: 'h1{color:#111}',
  viewport: { w: 390, h: 844, scale: 0.5 },
}));

assert(sent.some((m) => m.type === 'mounted'), 'mounted not posted');
assert(stage.style.width === '390px', 'stage width not applied: ' + stage.style.width);
assert(stage.style.transform.includes('scale('), 'scale not applied: ' + stage.style.transform);

const clock = created[0].contentWindow.__cordiactClock;
assert(clock, 'clock shim was not installed by document.write');
assert(clock.isFrozen() === false, 'clock must start live');

// ---- rAF passes through while live ----------------------------------------------
let ran = 0;
created[0].contentWindow.requestAnimationFrame(() => { ran += 1; });
assert(ran === 1, 'live rAF should fire through the real scheduler');

// ---- seek freezes, drains and aligns animations ---------------------------------
const anim = { playState: 'running', currentTime: 0, pause() { this.playState = 'paused'; } };
created[0].contentWindow.__anims.push(anim);

api.handle(JSON.stringify({ type: 'seek', t: 300 }));
assert(clock.isFrozen() === true, 'seek must freeze the clock');
assert(anim.playState === 'paused' && anim.currentTime === 300, 'animation was not aligned');
assert(sent.some((m) => m.type === 'seeked' && m.t === 300), 'seeked not posted');

// While frozen a queued frame must stay queued until the next seek.
let drainedWith = null;
created[0].contentWindow.requestAnimationFrame((t) => { drainedWith = t; });
assert(drainedWith === null, 'frozen clock must not run queued frames');
api.handle(JSON.stringify({ type: 'seek', t: 500 }));
assert(drainedWith === 500, 'pending rAF should run with the virtual time, got ' + drainedWith);

// ---- repeated seeks are identical (determinism) ---------------------------------
api.handle(JSON.stringify({ type: 'seek', t: 0 }));
const t0 = created[0].contentWindow.performance.now();
api.handle(JSON.stringify({ type: 'seek', t: 300 }));
const t1 = created[0].contentWindow.performance.now();
api.handle(JSON.stringify({ type: 'seek', t: 300 }));
const t2 = created[0].contentWindow.performance.now();
assert(t0 === 0 && t1 === 300 && t2 === 300, 'virtual time is not deterministic');

// ---- step advances from the current cursor --------------------------------------
api.handle(JSON.stringify({ type: 'step', ms: 16 }));
assert(created[0].contentWindow.performance.now() === 316,
  'step did not advance: ' + created[0].contentWindow.performance.now());

// ---- play resumes ---------------------------------------------------------------
api.handle(JSON.stringify({ type: 'play' }));
assert(clock.isFrozen() === false, 'play must unfreeze');
assert(sent.some((m) => m.type === 'playing'), 'playing not posted');

// ---- probe: the shim must have run before the page's own script -----------------
api.handle(JSON.stringify({ type: 'probe' }));
const probe = sent.filter((m) => m.type === 'probe').pop();
assert(probe, 'probe not posted');
assert(probe.hasClock === true, 'clock shim missing in the frame');
assert(probe.pageSawClock === true, 'page script ran before the shim');
assert(probe.animationsApi === true, 'getAnimations not visible from the frame');
assert(probe.frameCount === 1, 'single layout should report one frame');

// ---- grid layout: three frames, each mounted, sized by its own spec --------------
api.handle(JSON.stringify({
  type: 'setLayout',
  frames: [{ w: 360, h: 800 }, { w: 390, h: 844 }, { w: 768, h: 1024 }],
  primary: 1,
}));
assert(created.length === 3, 'grid layout should create three frames, got ' + created.length);
assert(sent.some((m) => m.type === 'layout'), 'layout not posted');
assert(created[0].style.width === '360px' && created[2].style.width === '768px',
  'frames not sized per spec');
assert(created.every((f) => f.contentWindow.__cordiactClock),
  'every frame in a grid must get its own clock shim');

// Seek has to reach every frame, otherwise a side-by-side compare would show one paused
// frame and two live ones.
created.forEach((f) => {
  f.contentWindow.__anims.push({ playState: 'running', currentTime: 0, pause() { this.playState = 'paused'; } });
});
api.handle(JSON.stringify({ type: 'seek', t: 750 }));
created.forEach((f, i) => {
  const a = f.contentWindow.__anims[f.contentWindow.__anims.length - 1];
  assert(a.currentTime === 750, 'frame ' + i + ' was not aligned, got ' + a.currentTime);
});

api.handle(JSON.stringify({ type: 'probe' }));
const gridProbe = sent.filter((m) => m.type === 'probe').pop();
assert(gridProbe.frameCount === 3, 'grid probe should report three frames');

// ---- snapshot shape --------------------------------------------------------------
api.handle(JSON.stringify({ type: 'snapshot' }));
const snap = sent.filter((m) => m.type === 'snapshot').pop();
assert(snap, 'snapshot not posted');
assert(snap.viewport && typeof snap.viewport.w === 'number', 'snapshot viewport missing');
assert(Array.isArray(snap.nodes), 'snapshot nodes must be an array');
assert(snap.backgroundColor === '#ffffff', 'transparent page must fall back to white');

// ---- back to a single frame ------------------------------------------------------
api.handle(JSON.stringify({ type: 'setLayout', frames: [{ w: 390, h: 844 }], primary: 0 }));
assert(stage.children.length === 1,
  'collapsing to one frame must drop the extras, live frames = ' + stage.children.length);

// ---- bad command is reported, not thrown ----------------------------------------
api.handle('{not json');
assert(sent.some((m) => m.type === 'error' && m.where === 'host'), 'bad json must surface as an error');

console.log('preview.js: ' + sent.length + ' messages, all assertions passed');

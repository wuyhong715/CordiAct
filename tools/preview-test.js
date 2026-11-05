/*
 * Headless harness for app/src/main/assets/render/preview.js.
 *
 * The Android side cannot be compiled on this machine, so the only executable check available
 * is the page script itself. This stubs just enough DOM to run the real file: an iframe whose
 * contentDocument.write() actually evaluates the inline <script> (which is how the virtual
 * clock gets installed), and a fake host bridge that records outgoing messages.
 *
 * What it proves: seek()/step()/play() drive the clock deterministically, CSS/WAAPI
 * animations are paused and aligned, and the snapshot payload keeps the shape SnapshotParser
 * expects.
 */
'use strict';
// Run with: node tools/preview-test.js  (no dependencies)
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

const path = require('path');
const SRC = fs.readFileSync(
  path.join(__dirname, '..', 'app/src/main/assets/render/preview.js'), 'utf8');

// ---- stubs ----------------------------------------------------------------------
const sent = [];
const host = { postMessage: (json) => sent.push(JSON.parse(json)) };

function makeElement(tag) {
  return {
    tagName: tag ? tag.toUpperCase() : 'DIV',
    style: {},
    textContent: '',
    childElementCount: 0,
    scrollWidth: 100, clientWidth: 100, scrollHeight: 100, clientHeight: 100,
    id: '', parentElement: null,
    getBoundingClientRect: () => ({ left: 0, top: 0, width: 100, height: 40 }),
    getAttribute: () => null,
    hasAttribute: () => false,
    querySelector: () => null,
    querySelectorAll: () => [],
  };
}

const stage = makeElement();
const boot = makeElement();

// The iframe world: a vm context that plays the role of the frame's window.
const frameWorld = { console, Map, Array, Object, JSON, Math, String, Number, isFinite, parseFloat };
frameWorld.window = frameWorld;
frameWorld.navigator = { userAgent: 'harness-frame' };
frameWorld.__raf = [];
frameWorld.__anims = [];
frameWorld.requestAnimationFrame = (cb) => { frameWorld.__raf.push(cb); cb(frameWorld.performance.now()); return frameWorld.__raf.length; };
frameWorld.cancelAnimationFrame = () => {};
frameWorld.performance = { now: () => 1000 };
frameWorld.addEventListener = () => {};
frameWorld.document = {
  body: makeElement('body'),
  getAnimations: () => frameWorld.__anims,
  querySelector: () => null,
  querySelectorAll: () => [],
  open() {}, close() {},
  write(html) {
    // Evaluate every inline script, exactly as the browser would on document.write.
    const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
    for (const code of scripts) vm.runInContext(code, frameWorld);
  },
};
frameWorld.getComputedStyle = () => ({
  display: 'block', visibility: 'visible', position: 'static',
  color: 'rgb(17, 17, 17)', backgroundColor: 'rgba(0, 0, 0, 0)',
  fontSize: '14px', overflowX: 'visible', overflowY: 'visible',
});
vm.createContext(frameWorld);

const frame = {
  contentDocument: frameWorld.document,
  contentWindow: frameWorld,
};

const mainWindow = {
  CordiActHost: host,
  addEventListener: () => {},
  __cordiact: null,
};
mainWindow.window = mainWindow;
mainWindow.navigator = { userAgent: 'harness' };
mainWindow.document = {
  getElementById: (id) => ({ stage, frame, boot }[id] || null),
};

const ctx = vm.createContext(mainWindow);
vm.runInContext(SRC, ctx);

const api = mainWindow.__cordiact;
assert(api && typeof api.handle === 'function', 'command surface missing');
assert(sent.some((m) => m.type === 'ready'), 'ready not posted');

// ---- setContent: mounts, installs the clock -------------------------------------
api.handle(JSON.stringify({
  type: 'setContent',
  html: '<script>window.__sawClockAtParse = (typeof window.__cordiactClock === "object");</script><h1>你好</h1>',
  css: 'h1{color:#111}',
  viewport: { w: 390, h: 844, scale: 0.5 },
}));

assert(sent.some((m) => m.type === 'mounted'), 'mounted not posted');
assert(stage.style.width === '390px', 'stage width not applied: ' + stage.style.width);
assert(stage.style.transform.includes('scale(0.5)'), 'scale not applied: ' + stage.style.transform);
const clock = frameWorld.__cordiactClock;
assert(clock, 'clock shim was not installed by document.write');
assert(clock.isFrozen() === false, 'clock must start live');

// ---- rAF passes through while live ----------------------------------------------
let ran = 0;
frameWorld.requestAnimationFrame(() => { ran += 1; });
assert(ran === 1, 'live rAF should fire through the real scheduler');

// ---- seek freezes, drains and aligns animations ---------------------------------
const anim = { playState: 'running', currentTime: 0, pause() { this.playState = 'paused'; } };
frameWorld.__anims.push(anim);

api.handle(JSON.stringify({ type: 'seek', t: 300 }));
assert(clock.isFrozen() === true, 'seek must freeze the clock');
assert(anim.playState === 'paused' && anim.currentTime === 300, 'animation was not aligned');
assert(sent.some((m) => m.type === 'seeked' && m.t === 300), 'seeked not posted');

// While frozen a queued frame must stay queued until the next seek.
let drainedWith = null;
frameWorld.requestAnimationFrame((t) => { drainedWith = t; });
assert(drainedWith === null, 'frozen clock must not run queued frames');
api.handle(JSON.stringify({ type: 'seek', t: 500 }));
assert(drainedWith === 500, 'pending rAF should run with the virtual time, got ' + drainedWith);

// ---- repeated seeks are identical (determinism) ---------------------------------
api.handle(JSON.stringify({ type: 'seek', t: 0 }));
const t0 = frameWorld.performance.now();
api.handle(JSON.stringify({ type: 'seek', t: 300 }));
const t1 = frameWorld.performance.now();
api.handle(JSON.stringify({ type: 'seek', t: 300 }));
const t2 = frameWorld.performance.now();
assert(t0 === 0 && t1 === 300 && t2 === 300, 'virtual time is not deterministic');

// ---- step advances from the current cursor --------------------------------------
api.handle(JSON.stringify({ type: 'step', ms: 16 }));
assert(frameWorld.performance.now() === 316, 'step did not advance: ' + frameWorld.performance.now());

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

// ---- snapshot shape --------------------------------------------------------------
api.handle(JSON.stringify({ type: 'snapshot' }));
const snap = sent.filter((m) => m.type === 'snapshot').pop();
assert(snap, 'snapshot not posted');
assert(snap.viewport && typeof snap.viewport.w === 'number', 'snapshot viewport missing');
assert(Array.isArray(snap.nodes), 'snapshot nodes must be an array');
assert(snap.backgroundColor === '#ffffff', 'transparent page must fall back to white');

// ---- bad command is reported, not thrown ----------------------------------------
api.handle('{not json');
assert(sent.some((m) => m.type === 'error' && m.where === 'host'), 'bad json must surface as an error');

console.log('preview.js: ' + (sent.length) + ' messages, all assertions passed');

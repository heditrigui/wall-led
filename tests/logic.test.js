// Tests for the pure logic inside led-control.html (the <script data-test> blocks).
// Run with: node tests/logic.test.js
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const html = fs.readFileSync(path.join(__dirname, '..', 'led-control.html'), 'utf8');
const code = [...html.matchAll(/<script data-test>([\s\S]*?)<\/script>/g)].map((m) => m[1]).join('\n');
const page = new Function(code + '\nreturn { BeatSense: globalThis.BeatSense, BeatClock, screenColor, nextFire, fadeLevel };')();

let failed = 0;
function test(name, fn) {
  try { fn(); console.log('ok   ' + name); }
  catch (e) { failed++; console.log('FAIL ' + name + '\n     ' + e.message); }
}

// A drum loop with a known tempo, like BeatSense's test loops: kick on every beat, snare on
// 2 and 4, off-beat hi-hats, a sustained tone and a little noise.
function drumLoop(bpm, seconds, rate, startOffset = 0) {
  const out = new Float32Array(Math.floor(seconds * rate));
  const beat = 60 / bpm;
  let seed = 1;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 * 2 - 1; };
  const hit = (t0, len, fn) => {
    const start = Math.floor(t0 * rate);
    for (let i = 0; i < len * rate && start + i < out.length; i++) out[start + i] += fn(i / rate);
  };
  for (let k = 0; startOffset + k * beat < seconds; k++) {
    const t0 = startOffset + k * beat;
    hit(t0, 0.25, (t) => 0.8 * Math.sin(2 * Math.PI * (50 + 70 * Math.exp(-t * 30)) * t) * Math.exp(-t * 14));
    if (k % 2) hit(t0, 0.18, (t) => (rnd() * 0.45 + 0.2 * Math.sin(2 * Math.PI * 190 * t)) * Math.exp(-t * 22));
    hit(t0 + beat / 2, 0.05, (t) => rnd() * 0.15 * Math.exp(-t * 70));
  }
  for (let i = 0; i < out.length; i++) out[i] += 0.08 * Math.sin(2 * Math.PI * 55 * i / rate) + rnd() * 0.02;
  return out;
}

// Runs the show's loop: 2048-sample ticks, an estimate every 12 ticks, beat clock on every tick.
function runShow(samples, rate) {
  const det = new page.BeatSense.TempoDetector(rate, 10);
  const clock = new page.BeatClock();
  const beats = [];
  for (let off = 0, n = 0; off + 2048 <= samples.length; off += 2048, n++) {
    det.feed(samples.subarray(off, off + 2048));
    const now = (off + 2048) / rate;
    if (n % 12 === 0) clock.update(det.estimate(80, 160), now);
    if (clock.tick(now, 0)) beats.push(clock.lastBeat);
  }
  return { clock, beats };
}

for (const [bpm, offset] of [[120, 0.1], [128, 0.33], [95, 0.05], [140, 0.21]]) {
  test(`beat clock locks to ${bpm} BPM on the beat`, () => {
    const rate = 48000;
    const { clock, beats } = runShow(drumLoop(bpm, 30, rate, offset), rate);
    assert(clock.locked, 'not locked');
    assert(Math.abs(clock.bpm - bpm) < 1.5, `bpm ${clock.bpm.toFixed(1)}`);
    const beat = 60 / bpm;
    const late = beats.filter((t) => t > 15);
    assert(late.length > 10, 'too few beats');
    const errs = late.map((t) => { const k = (t - offset) / beat; return (k - Math.round(k)) * beat * 1000; });
    const worst = Math.max(...errs.map(Math.abs));
    const mean = errs.reduce((a, b) => a + b, 0) / errs.length;
    console.log(`     mean ${mean.toFixed(1)} ms, worst ${worst.toFixed(1)} ms`);
    assert(worst < 20, `beat off by ${worst.toFixed(1)} ms`);
    const gaps = late.slice(1).map((t, i) => t - late[i]);
    assert(gaps.every((g) => Math.abs(g - beat) < beat * 0.2), 'missed or doubled beats');
  });
}

test('beat clock follows a tempo change', () => {
  const rate = 48000;
  const s = new Float32Array(rate * 40);
  s.set(drumLoop(120, 20, rate));
  s.set(drumLoop(128, 20, rate), rate * 20);
  const { clock, beats } = runShow(s, rate);
  assert(Math.abs(clock.bpm - 128) < 1.5, `bpm ${clock.bpm.toFixed(1)}`);
  const beat = 60 / 128;
  const errs = beats.filter((t) => t > 32).map((t) => { const k = (t - 20) / beat; return Math.abs(k - Math.round(k)) * beat * 1000; });
  assert(Math.max(...errs) < 20, `beat off by ${Math.max(...errs).toFixed(1)} ms`);
});

test('beat clock unlocks in silence', () => {
  const rate = 48000;
  const s = new Float32Array(rate * 30);
  s.set(drumLoop(120, 15, rate));
  const { clock } = runShow(s, rate);
  assert(!clock.locked);
});

// RGBA pixels: `count` of each [r, g, b].
function picture(...parts) {
  const px = [];
  for (const [rgb, count] of parts) for (let i = 0; i < count; i++) px.push(...rgb, 255);
  return new Uint8ClampedArray(px);
}

test('screen color: average is the plain mean', () => {
  assert.deepStrictEqual(page.screenColor(picture([[200, 100, 0], 1], [[0, 100, 200], 1]), 'average'), [100, 100, 100]);
});

test('screen color: vivid takes the hue of a small bright red area on a dark screen', () => {
  const [r, g, b] = page.screenColor(picture([[10, 10, 10], 90], [[255, 0, 0], 10]), 'vivid');
  assert(r > 20 && g < r / 4 && b < r / 4, `got ${[r, g, b]}`);
  assert(r < 60, `too bright for a mostly dark screen: ${r}`);
});

test('screen color: vivid keeps a white screen white', () => {
  assert.deepStrictEqual(page.screenColor(picture([[255, 255, 255], 50]), 'vivid'), [255, 255, 255]);
});

test('screen color: black stays black', () => {
  assert.deepStrictEqual(page.screenColor(picture([[0, 0, 0], 50]), 'vivid'), [0, 0, 0]);
});

const ALL = [0, 1, 2, 3, 4, 5, 6];
const at = (y, mo, d, h, mi) => new Date(y, mo - 1, d, h, mi).getTime();

test('schedule: later today, else tomorrow', () => {
  const sch = { at: '19:00', days: ALL, action: 'scene' };
  assert.strictEqual(page.nextFire(sch, at(2026, 3, 4, 12, 0)), at(2026, 3, 4, 19, 0));
  assert.strictEqual(page.nextFire(sch, at(2026, 3, 4, 19, 0)), at(2026, 3, 5, 19, 0));
});

test('schedule: only on the chosen days', () => {
  // 2026-03-06 is a Friday; weekdays only, so the next one is Monday the 9th.
  const sch = { at: '07:30', days: [1, 2, 3, 4, 5], action: 'scene' };
  assert.strictEqual(page.nextFire(sch, at(2026, 3, 6, 8, 0)), at(2026, 3, 9, 7, 30));
});

test('schedule: a wake-up fade starts early, across midnight', () => {
  const sch = { at: '00:10', days: ALL, action: 'wake', minutes: 30 };
  assert.strictEqual(page.nextFire(sch, at(2026, 3, 4, 23, 0)), at(2026, 3, 4, 23, 40));
});

test('schedule: no days, never', () => {
  assert.strictEqual(page.nextFire({ at: '07:00', days: [], action: 'off' }, at(2026, 3, 4, 0, 0)), null);
});

test('fade level eases in from 1 to the target', () => {
  assert.strictEqual(page.fadeLevel(0, 1000, 80), 1);
  assert.strictEqual(page.fadeLevel(500, 1000, 80), 20);
  assert.strictEqual(page.fadeLevel(1000, 1000, 80), 80);
  assert.strictEqual(page.fadeLevel(5000, 1000, 80), 80);
});

if (failed) { console.log(failed + ' failed'); process.exit(1); }

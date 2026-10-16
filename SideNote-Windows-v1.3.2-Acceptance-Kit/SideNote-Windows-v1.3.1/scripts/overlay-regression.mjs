// Run against a test launch with WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=
// --remote-debugging-port=9223. Does not edit or delete note contents.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';

const port = process.argv[2] || '9223';
const output = new URL('../acceptance-evidence/overlay/', import.meta.url);
await mkdir(output, { recursive: true });
const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = pages.find(p => p.type === 'page' && !p.url.includes('view='));
assert.ok(page, 'Dock WebView must be running');
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
let sequence = 0;
const pending = new Map();
socket.onmessage = event => {
  const message = JSON.parse(event.data);
  if (!message.id) return;
  const task = pending.get(message.id);
  if (!task) return;
  pending.delete(message.id);
  message.error ? task.reject(message.error) : task.resolve(message.result);
};
function call(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  assert.ok(!result.exceptionDetails, result.exceptionDetails?.text);
  return result.result.value;
}
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const snapshot = () => evaluate(`(() => {
  const tab = document.querySelector('.tab');
  const rect = el => { const r = el.getBoundingClientRect(); return [r.left,r.top,r.right,r.bottom]; };
  return { count: document.querySelectorAll('.tab').length,
    open: !!document.querySelector('.tab.open'), dragging: !!document.querySelector('.tab.dragging'),
    preview: getComputedStyle(tab.querySelector('.tab-preview')).display,
    card: getComputedStyle(tab.querySelector('.card')).display,
    titleDirection: getComputedStyle(tab.querySelector('.tab-preview-title')).writingMode,
    previewBodies: document.querySelectorAll('.tab-preview-body').length,
    tabWidth: Math.min(innerWidth, tab.getBoundingClientRect().right)-tab.getBoundingClientRect().left,
    tabExtendsPastEdge: tab.getBoundingClientRect().right > innerWidth,
    panelBounds: rect(document.querySelector('.frame')),
    spineBounds: rect(document.querySelector('.dock-spine')),
    titleBounds: [...document.querySelectorAll('.tab-preview-title')].map(rect),
    addColor: getComputedStyle(document.querySelector('.fab')).backgroundColor,
    tabBounds: [...document.querySelectorAll('.tab')].map(rect),
    footerBounds: [...document.querySelectorAll('.fab, .folder')].map(rect),
    bridgeBounds: rect(tab.querySelector('.dock-popup-bridge')),
    buttonWidth: document.querySelector('.fab').getBoundingClientRect().width,
    cardBounds: rect(tab.querySelector('.card')),
    gripBounds: rect(tab.querySelector('.grip')),
    width: innerWidth, height: innerHeight, screenHeight: screen.height, scale: devicePixelRatio,
    controls: [...document.querySelectorAll('.tab:not(.dragging), .tab.open .card, .fab, .folder')].map(rect) };
})()`);
async function screenshot(name) {
  const clip = await evaluate(`(() => { const r=document.querySelector('.frame').getBoundingClientRect(); const y=Math.max(0,r.top-12); return {x:Math.max(0,innerWidth-340),y,width:Math.min(340,innerWidth),height:Math.min(innerHeight-y,r.height+24),scale:1}; })()`);
  const result = await call('Page.captureScreenshot', { format: 'png', clip });
  await writeFile(new URL(`${name}.png`, output), Buffer.from(result.data, 'base64'));
}
async function hover() {
  await evaluate(`document.querySelector('.tab').dispatchEvent(new PointerEvent('pointerover', { bubbles:true, pointerId:1, pointerType:'mouse' }))`);
  await delay(650);
}
async function down() {
  await evaluate(`document.querySelector('.tab .grip').dispatchEvent(new PointerEvent('pointerdown', { bubbles:true, button:0, pointerId:1, pointerType:'mouse', screenX:screen.width-30, screenY:300 }))`);
}
const checks = [];
try {
  await delay(700);
  const initial = await snapshot();
  assert.ok(initial.count > 0, 'Use a profile containing at least one dock note');
  await evaluate(`(() => {
    window.__overlayResizes = 0;
    const original = window.__TAURI_INTERNALS__.invoke;
    window.__TAURI_INTERNALS__.invoke = function(command, ...args) {
      if (command === 'set_window_bounds_atomic') window.__overlayResizes++;
      return original.call(this, command, ...args);
    };
  })()`);

  await hover();
  const opened = await snapshot();
  assert.ok(opened.open);
  assert.equal(opened.preview, 'flex');
  assert.equal(opened.card, 'block');
  assert.equal(opened.titleDirection, 'vertical-rl');
  assert.equal(opened.previewBodies, 0);
  assert.ok(opened.tabWidth > opened.buttonWidth);
  assert.ok(opened.tabBounds.every(r => opened.spineBounds[0]-r[0] >= 6), 'Every note must peek beyond the rectangle');
  assert.equal(opened.addColor, 'rgb(139, 76, 255)');
  assert.ok(opened.cardBounds[0] > 10);
  assert.ok(opened.tabBounds.every(r => r[0]-opened.cardBounds[2] >= 10), 'Popup needs a clear gutter from every tab');
  assert.ok(opened.footerBounds.every(r => Math.abs((r[0]+r[2])/2-(opened.titleBounds[0][0]+opened.titleBounds[0][2])/2) < 1), 'Footer icons must stay aligned with the note titles');
  assert.ok(opened.footerBounds.every(r => r[0]-opened.cardBounds[2] >= 10), 'Popup must also clear footer buttons');
  assert.ok(opened.bridgeBounds[2] > opened.bridgeBounds[0], 'Popup gap must remain traversable');
  assert.ok(opened.tabExtendsPastEdge);
  checks.push('all paper tabs peek outside the background rectangle while title and footer columns remain aligned');
  checks.push('add button uses the original purple');
  checks.push('popup has a 10px+ gutter from lower notes and aligned footer buttons');
  await screenshot('hover');

  await down();
  await delay(350);
  await evaluate(`window.dispatchEvent(new PointerEvent('pointermove', { pointerId:1, screenX:screen.width-180, screenY:320 }))`);
  await delay(200);
  const dragging = await snapshot();
  assert.ok(dragging.dragging);
  assert.equal(dragging.preview, 'none');
  assert.equal(dragging.width, initial.width);
  assert.equal(dragging.height, initial.height);
  checks.push('drag keeps native canvas dimensions unchanged');
  checks.push('leftward drag shows only one note surface');
  await screenshot('drag');
  await evaluate(`window.dispatchEvent(new PointerEvent('pointercancel', { pointerId:1 }))`);
  await delay(650);
  const cancelled = await snapshot();
  assert.equal(cancelled.dragging, false);
  assert.equal(cancelled.height, initial.height);
  checks.push('cancel releases drag capture on the unchanged canvas');

  await hover();
  // Deliberately release before the asynchronous monitor/resize completes.
  await evaluate(`(() => {
    document.querySelector('.tab .grip').dispatchEvent(new PointerEvent('pointerdown', { bubbles:true, button:0, pointerId:2, screenX:screen.width-30, screenY:300 }));
    window.dispatchEvent(new PointerEvent('pointerup', { pointerId:2, screenX:screen.width-30, screenY:300 }));
  })()`);
  await delay(700);
  const quickRelease = await snapshot();
  assert.equal(quickRelease.dragging, false);
  assert.equal(quickRelease.height, initial.height);
  checks.push('quick release cannot leave drag capture active');

  await evaluate(`window.dispatchEvent(new PointerEvent('pointerleave'))`);
  await delay(800);
  const collapsed = await snapshot();
  await screenshot('bookmarks');
  assert.equal(collapsed.open, false);
  assert.equal(collapsed.card, 'none');
  assert.equal(collapsed.width, opened.width);
  const railItems = [...collapsed.tabBounds, ...collapsed.footerBounds];
  for (let i=1; i<railItems.length; i++) assert.ok(railItems[i][1]-railItems[i-1][3] >= 5, 'Rail items need breathing room');
  checks.push('pointer leave restores the narrow bookmark without resizing the native window');
  for (let cycle=0; cycle<5; cycle++) {
    await hover();
    await evaluate(`window.dispatchEvent(new PointerEvent('pointerleave'))`);
    await delay(450);
  }
  assert.equal(await evaluate('window.__overlayResizes'), 0);
  checks.push('hover, left drag, cancel, quick release and five repeat cycles cause zero native resizes');
  await writeFile(new URL('results.json', output), JSON.stringify({ checks, opened, cancelled, quickRelease, collapsed }, null, 2));
  console.log(JSON.stringify({ status: 'PASS', checks }));
} finally {
  socket.close();
}

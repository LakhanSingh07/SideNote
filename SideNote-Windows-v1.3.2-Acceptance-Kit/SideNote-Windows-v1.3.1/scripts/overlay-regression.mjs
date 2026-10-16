// Run against a test launch with WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=
// --remote-debugging-port=9223. Does not edit or delete note contents.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';

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
const runFile = promisify(execFile);
async function dragBetweenRows(from, to) {
  await evaluate(`document.querySelectorAll('.tab')[${from}].dispatchEvent(new PointerEvent('pointerover',{bubbles:true,pointerId:1,pointerType:'mouse'}))`);
  await delay(350);
  const points = await evaluate(`(() => {
    const tabs=[...document.querySelectorAll('.tab')];
    const grip=tabs[${from}].querySelector('.grip').getBoundingClientRect();
    const target=tabs[${to}].getBoundingClientRect();const scale=devicePixelRatio;
    return {x:Math.round((screenX+grip.left+grip.width/2)*scale),
      y:Math.round((screenY+grip.top+grip.height/2)*scale),
      end:Math.round((screenY+target.top+target.height/2)*scale)};
  })()`);
  await runFile('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',
    fileURLToPath(new URL('drag-pointer.ps1',import.meta.url)),
    '-StartX',String(points.x),'-StartY',String(points.y),'-EndY',String(points.end)],{windowsHide:true});
  await delay(250);
}
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
    spineColor: getComputedStyle(document.querySelector('.dock-spine')).backgroundColor,
    titleBounds: [...document.querySelectorAll('.tab-preview-title')].map(rect),
    addColor: getComputedStyle(document.querySelector('.fab')).backgroundColor,
    tabBounds: [...document.querySelectorAll('.tab')].map(rect),
    footerBounds: [...document.querySelectorAll('.fab, .folder')].map(rect),
    arrowBounds: [...document.querySelectorAll('.dock-scroll-arrow')].map(rect),
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
    window.__overlayResizes=0;
    window.addEventListener('resize',()=>window.__overlayResizes++);
  })()`);

  await hover();
  const opened = await snapshot();
  assert.ok(opened.open);
  assert.equal(opened.preview, 'none');
  assert.equal(opened.card, 'block');
  assert.equal(opened.titleDirection, 'vertical-rl');
  assert.equal(opened.previewBodies, 0);
  assert.ok(opened.tabWidth > opened.buttonWidth);
  assert.ok(opened.tabBounds.every(r => opened.spineBounds[0]-r[0] >= 6), 'Every note must peek beyond the rectangle');
  assert.equal(opened.addColor, 'rgb(139, 76, 255)');
  assert.equal(opened.spineColor, 'rgb(255, 254, 253)');
  assert.equal(await evaluate("document.querySelectorAll('.dock-scroll-status').length"),0);
  assert.ok(opened.cardBounds[0] > 10);
  assert.ok(Math.abs(opened.cardBounds[2]-opened.width) < 1, 'Same note must expand from the screen edge');
  assert.ok(opened.tabBounds.slice(1).every(r => r[1]-opened.cardBounds[3] >= 5), 'Lower notes must move below the expanded note');
  assert.ok(opened.footerBounds.every(r => r[1]-opened.cardBounds[3] >= 5), 'Footer must remain below the expanded note');
  assert.ok(initial.footerBounds.every(r => Math.abs((r[0]+r[2])/2-(initial.titleBounds[0][0]+initial.titleBounds[0][2])/2) < 1), 'Footer icons must stay aligned with collapsed titles');
  assert.ok(opened.tabExtendsPastEdge);
  checks.push('all paper tabs peek outside the background rectangle while title and footer columns remain aligned');
  checks.push('add button uses the original purple');
  checks.push('same edge note expands without a duplicate preview; lower notes and footer move down');
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

  // Inject fixture state into this renderer only. Reload restores all timers
  // and state; no storage commands or database writes are used.
  await evaluate(`(() => {
    window.__originalDockOrder=localStorage.getItem('sidenote_dock_order');
    const element=document.querySelector('.frame');
    let fiber=element[Object.keys(element).find(key=>key.startsWith('__reactFiber$'))];
    while(fiber && !(fiber.memoizedState?.queue?.dispatch && Array.isArray(fiber.memoizedState.memoizedState))) fiber=fiber.return;
    if(!fiber) throw new Error('Notes state hook not found');
    for(let id=1;id<10000;id++) clearInterval(id);
    fiber.memoizedState.queue.dispatch(Array.from({length:7},(_,i)=>({
      id:'overlay-fixture-'+i,
      title:i===0?'Complete weekly study plan and creative project checklist':'Later note '+(i+1),
      body:'Temporary renderer-only test note',color:i%6,isChecklist:false,
      updatedAt:Date.now()-i*1000,pinned:false,archived:false,reminderAt:0
    })));
  })()`);
  try {
    await delay(900);
    assert.equal(await evaluate("document.querySelectorAll('.tab').length"),4);
    const wheel = async direction => {
      await evaluate(`document.querySelector('.tab').dispatchEvent(new WheelEvent('wheel',{bubbles:true,deltaY:${direction*120}}))`);
      await delay(220);
    };
    for(let step=0;step<3;step++) await wheel(1);
    assert.ok(await evaluate("[...document.querySelectorAll('.tab-preview-title')].some(el=>el.textContent.trim()==='Later note 7')"));
    assert.equal(await evaluate("document.querySelector('.dock-scroll-arrow.is-next').disabled"),true);
    await wheel(1);
    assert.ok(await evaluate("[...document.querySelectorAll('.tab-preview-title')].some(el=>el.textContent.trim()==='Later note 7')"));
    for(let step=0;step<3;step++) await wheel(-1);
    assert.equal(await evaluate("document.querySelector('.dock-scroll-arrow.is-previous').disabled"),true);
    await evaluate("document.querySelector('.dock-scroll-arrow.is-next').click()");
    await delay(150);
    assert.equal(await evaluate("document.querySelector('.tab-preview-title').textContent.trim()"),'Later note 2');
    await evaluate("document.querySelector('.dock-scroll-arrow.is-previous').click()");
    await delay(150);
    assert.equal(await evaluate("document.querySelector('.dock-scroll-arrow.is-previous').disabled"),true);
    checks.push('top and bottom arrows navigate notes alongside the wheel; unavailable directions are disabled');
    checks.push('number counter is removed and the reference off-white rail color is applied');
    await hover();
    const heading = await evaluate(`(() => { const el=document.querySelector('.tab.open .dock-card-title');return {
      text:el.textContent.trim(),wrap:getComputedStyle(el).whiteSpace,
      clipped:el.scrollHeight>el.clientHeight+1
    };})()`);
    assert.equal(heading.text,'Complete weekly study plan and creative project checklist');
    assert.equal(heading.wrap,'normal');
    assert.equal(heading.clipped,false);
    checks.push('wheel reaches the seventh note, clamps at the end and scrolls back to the first');
    checks.push('complete long heading wraps visibly in the expanded note');
    await screenshot('full-title');
    const firstTitle = await evaluate("document.querySelector('.tab-preview-title').textContent.trim()");
    await dragBetweenRows(0,3);
    assert.equal(await evaluate("document.querySelectorAll('.tab-preview-title')[3].textContent.trim()"),firstTitle,'Top note must drag all the way down to the fourth position');
    assert.ok(await evaluate("JSON.parse(localStorage.getItem('sidenote_dock_order')).slice(4).join(',')==='overlay-fixture-4,overlay-fixture-5,overlay-fixture-6'"),'Reorder must preserve off-screen notes');
    await dragBetweenRows(3,0);
    assert.equal(await evaluate("document.querySelector('.tab-preview-title').textContent.trim()"),firstTitle,'Bottom note must drag back up to the first position');
    checks.push('real mouse reorders first-to-fourth and fourth-to-first while preserving off-screen notes');
  } finally {
    await evaluate(`(() => {
      const original=window.__originalDockOrder;
      if(original===null) localStorage.removeItem('sidenote_dock_order');
      else localStorage.setItem('sidenote_dock_order',original);
      delete window.__originalDockOrder;
    })()`);
    await call('Page.reload');
    await delay(1000);
  }
  await writeFile(new URL('results.json', output), JSON.stringify({ checks, opened, cancelled, quickRelease, collapsed }, null, 2));
  console.log(JSON.stringify({ status: 'PASS', checks }));
} finally {
  socket.close();
}

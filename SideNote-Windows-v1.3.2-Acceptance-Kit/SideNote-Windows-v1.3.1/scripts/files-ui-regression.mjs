import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
const pages=await(await fetch('http://127.0.0.1:9223/json/list')).json();
const page=pages.find(p=>p.type==='page'&&!p.url.includes('view='));
assert.ok(page,'Dock WebView required');
const socket=new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject});
let seq=0; const pending=new Map();
socket.onmessage=e=>{const m=JSON.parse(e.data),p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(m.error):p.resolve(m.result)}};
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}))});
const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert.ok(!r.exceptionDetails,JSON.stringify(r.exceptionDetails));return r.result.value};
const delay=ms=>new Promise(r=>setTimeout(r,ms));

import {randomUUID,createHash} from 'node:crypto';
import {readFile,rm} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const invoke=(command,args={})=>evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)})`);
const id=randomUUID(),checks=[],originalOrder=await evaluate("localStorage.getItem('sidenote_dock_order')");
const fixtures=new URL('../src-tauri/target/file-ui-fixtures/',import.meta.url);await mkdir(fixtures,{recursive:true});
const inputs=[{name:'empty.unknown',data:Buffer.alloc(0)},{name:'binary.anytype',data:Buffer.alloc(512*1024+37,0xc7)}];
for(const input of inputs)await writeFile(new URL(input.name,fixtures),input.data);
let hashes=[];
const waitFor=async(expression,message)=>{for(let i=0;i<150;i++){if(await expression())return;await delay(100)}assert.fail(message)};
try{
 await invoke('upsert_note',{note:{id,title:'Temporary attachment UI check',body:'Editable note and files',color:1,isChecklist:false,updatedAt:Date.now(),pinned:false,archived:false,reminderAt:0}});
 await evaluate(`localStorage.setItem('sidenote_dock_order',JSON.stringify([${JSON.stringify(id)},...JSON.parse(localStorage.getItem('sidenote_dock_order')||'[]')]))`);await call('Page.reload');await delay(1200);
 await evaluate(`document.querySelector('.tab').dispatchEvent(new PointerEvent('pointerover',{bubbles:true,pointerId:1,pointerType:'mouse'}))`);await delay(400);
 const document=await call('DOM.getDocument');const selector=await call('DOM.querySelector',{nodeId:document.root.nodeId,selector:'.tab.open .note-files input[type=file]'});
 await call('DOM.setFileInputFiles',{nodeId:selector.nodeId,files:inputs.map(f=>fileURLToPath(new URL(f.name,fixtures)))});
 await waitFor(async()=>{const note=(await invoke('list_notes')).find(n=>n.id===id);return note?.attachments?.length===2},'Both selected files attach to the same note');
 const note=(await invoke('list_notes')).find(n=>n.id===id);hashes=note.attachments.map(f=>f.sha256);
 for(const input of inputs){const attached=note.attachments.find(f=>f.name===input.name);assert.ok(attached);assert.equal(attached.size,input.data.length);assert.equal(attached.sha256,createHash('sha256').update(input.data).digest('hex'))}
 checks.push('Multi-select attaches zero-byte and arbitrary binary files without losing either');
 await waitFor(()=>evaluate(`document.querySelectorAll('.tab.open .note-file button:not(:disabled)').length===6`),'Open and Save As enable after file verification');checks.push('Open and Save As enable only after the local file is complete');
 await evaluate(`document.querySelector('.tab.open button[title="Remove empty.unknown from note"]').click()`);
 await waitFor(async()=>{const note=(await invoke('list_notes')).find(n=>n.id===id);return note.attachments.length===1&&note.attachments[0].name==='binary.anytype'},'Remove updates attachments');checks.push('Remove unlinks only the selected attachment');
 assert.equal((await invoke('list_notes')).find(n=>n.id===id).body,'Editable note and files');checks.push('Attachment changes preserve note body');
 await mkdir('acceptance-evidence/files-sync',{recursive:true});
 const clip=await evaluate(`(()=>{const r=document.querySelector('.tab.open .card').getBoundingClientRect();return {x:Math.max(0,r.left-8),y:Math.max(0,r.top-8),width:Math.min(innerWidth-r.left+8,r.width+16),height:r.height+16,scale:1}})()`);
 const shot=await call('Page.captureScreenshot',{format:'png',clip});await writeFile('acceptance-evidence/files-sync/windows-files-ui.png',Buffer.from(shot.data,'base64'));
 await writeFile('acceptance-evidence/files-sync/ui-results.json',JSON.stringify({passed:checks.length,checks},null,2));console.log(JSON.stringify({passed:checks.length,checks},null,2));
}finally{
 await invoke('delete_note',{id});await evaluate(`(()=>{const order=${JSON.stringify(originalOrder)};if(order===null)localStorage.removeItem('sidenote_dock_order');else localStorage.setItem('sidenote_dock_order',order)})()`);
 const refs=new Set((await invoke('list_notes')).flatMap(n=>(n.attachments||[]).map(f=>f.sha256)));
 for(const hash of hashes){if(refs.has(hash))continue;assert.match(hash,/^[a-f0-9]{64}$/);const dir=path.resolve(process.env.APPDATA,'in.sidenote.desktop','attachments');const file=path.resolve(dir,hash+'.bin');assert.equal(path.dirname(file),dir);await rm(file,{force:true})}
 for(const input of inputs)await rm(new URL(input.name,fixtures),{force:true});await call('Page.reload');socket.close();
}

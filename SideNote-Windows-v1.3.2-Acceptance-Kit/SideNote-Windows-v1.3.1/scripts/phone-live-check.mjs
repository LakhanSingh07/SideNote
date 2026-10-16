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


import {readFile} from 'node:fs/promises';
import {randomUUID,createHash} from 'node:crypto';
import {execFile} from 'node:child_process';import {promisify} from 'node:util';
import {fileURLToPath} from 'node:url';
const run=promisify(execFile),sessionPath=new URL('../src-tauri/target/physical-phone-check/session.json',import.meta.url);
const session=JSON.parse(await readFile(sessionPath,'utf8'));
const invoke=(command,args={})=>evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)})`);
const save=()=>writeFile(sessionPath,JSON.stringify(session,null,2));
const action=process.argv[2]||'status';
try{
 if(action==='seed'){
  const baseline=await invoke('list_notes');session.windowsBaseline=baseline.filter(n=>n.id!==session.androidNoteId).map(n=>({id:n.id,title:n.title,body:n.body,color:n.color,attachments:n.attachments||[]}));
  const size=session.fileSize,bytes=Buffer.alloc(512*1024,0x6d),digest=createHash('sha256');const importId=await invoke('attachment_import_start');
  for(let offset=0;offset<size;offset+=bytes.length){await invoke('attachment_import_chunk',{importId,offset,data:Array.from(bytes)});digest.update(bytes)}
  const file=await invoke('attachment_import_finish',{importId,name:session.tag+'_PC.anytype',mimeType:'application/octet-stream'});
  assert.equal(file.sha256,digest.digest('hex'));
  session.windowsNoteId=randomUUID();session.windowsFileSha256=file.sha256;session.windowsFileName=file.name;session.windowsFileStartedAt=Date.now();await save();
  await invoke('upsert_note',{note:{id:session.windowsNoteId,title:session.tag+'_PC',body:session.tag+'_PC',color:2,isChecklist:false,updatedAt:Date.now(),pinned:false,archived:false,reminderAt:0,attachments:[file]}});
  console.log(JSON.stringify({windowsTestNoteCreated:true,bytes:size,sha256:file.sha256}));
 }else if(action==='pc-edit'){
  const note=(await invoke('list_notes')).find(n=>n.id===session.androidNoteId);assert.ok(note);await invoke('upsert_note',{note:{...note,body:session.tag+'_edited_on_PC',updatedAt:Math.max(Date.now(),note.updatedAt+1)}});console.log('PC edit sent to the physical phone');
 }else if(action==='status'){
  const notes=await invoke('list_notes');const own=notes.filter(n=>[session.androidNoteId,session.windowsNoteId].includes(n.id));
  const result={windowsTotal:notes.length,tests:[]};
  for(const note of own){result.tests.push({id:note.id,title:note.title,body:note.body,attachments:note.attachments,states:await invoke('attachment_status',{noteId:note.id})})}
  console.log(JSON.stringify(result,null,2));
 }else if(action==='verify'){
  const notes=await invoke('list_notes');
  assert.equal(notes.find(n=>n.id===session.windowsNoteId)?.body,session.tag+'_PC_edited_on_phone');
  assert.equal(notes.find(n=>n.id===session.androidNoteId)?.body,session.tag+'_edited_on_PC');
  for(const [id,key] of [[session.androidNoteId,'androidFileSha256'],[session.windowsNoteId,'windowsFileSha256']]){
   const note=notes.find(n=>n.id===id);assert.equal(note.attachments[0].sha256,session[key]);
   const blob=await readFile(`${process.env.APPDATA}/in.sidenote.desktop/attachments/${session[key]}.bin`);
   assert.equal(blob.length,session.fileSize);assert.equal(createHash('sha256').update(blob).digest('hex'),session[key]);
  }
  console.log(JSON.stringify({bothDirectionEditsPassed:true,bothWindowsFileHashesMatched:true}));
 }else if(action==='assert-phone-delete'){
  assert.ok(!(await invoke('list_notes')).some(n=>n.id===session.windowsNoteId));
  console.log('Android UI deletion received on Windows');
 }else if(action==='final-state'){
  const notes=await invoke('list_notes');
  for(const original of session.windowsBaseline){assert.ok(notes.some(n=>n.id===original.id));}
  assert.equal(notes.length,session.windowsBaseline.length);
  assert.ok(!notes.some(n=>[session.androidNoteId,session.windowsNoteId].includes(n.id)));
  await writeFile(new URL('../src-tauri/target/physical-phone-check/final-windows-notes.json',import.meta.url),JSON.stringify(notes));
  console.log(JSON.stringify({originalNoteIdsRetained:true,temporaryNotesGone:true,count:notes.length,notesEditedDuringSession:session.windowsBaseline.filter(n=>notes.find(c=>c.id===n.id).body!==n.body).length}));
 }else if(action==='cleanup'){
  for(const id of [session.androidNoteId,session.windowsNoteId].filter(Boolean)){if((await invoke('list_notes')).some(n=>n.id===id))await invoke('delete_note',{id})}
  const notes=await invoke('list_notes');for(const original of session.windowsBaseline||[]){const current=notes.find(n=>n.id===original.id);assert.ok(current);assert.equal(current.title,original.title);assert.equal(current.body,original.body);assert.deepEqual(current.attachments||[],original.attachments)}
  console.log(JSON.stringify({temporaryNotesRemovedOnWindows:true,originalWindowsNotesPreserved:true,remaining:notes.length}));
 }
}finally{socket.close()}

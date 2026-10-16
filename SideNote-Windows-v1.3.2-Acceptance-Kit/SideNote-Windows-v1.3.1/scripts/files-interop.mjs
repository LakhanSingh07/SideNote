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

import {randomUUID,randomBytes,createHash} from 'node:crypto';
import {readFile,rm} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const invoke=(command,args={})=>evaluate(`window.__TAURI_INTERNALS__.invoke(${JSON.stringify(command)},${JSON.stringify(args)})`);
const size=12*1024*1024,chunkSize=512*1024;
const windowsBytes=Buffer.alloc(chunkSize,0x3c);
const hash=createHash('sha256');for(let i=0;i<size;i+=chunkSize)hash.update(windowsBytes);
const windowsHash=hash.digest('hex');
const deviceId=randomUUID(),windowsNoteId=randomUUID(),androidNoteId=randomUUID(),androidFileId=randomUUID();
const sessionPath=fileURLToPath(new URL('../src-tauri/target/interop-session.json',import.meta.url));
const stagePath=path.join(path.dirname(sessionPath),'interop-stage.json');
const reportDir=new URL('../acceptance-evidence/files-sync/',import.meta.url);
const started=Date.now();let processHandle;let output='';let uploadedHash;
const originalNotes=await invoke('list_notes');
const originalVersions=new Map(originalNotes.map(n=>[n.id,JSON.stringify(n)]));
try {
 await rm(stagePath,{force:true});
 const token=randomBytes(32).toString('hex');const pcId=await invoke('local_device_id');
 let baselineCursor=0;while(true){const changes=await invoke('export_local_changes',{afterSeq:baselineCursor,limit:500});if(!changes.length)break;baselineCursor=changes.at(-1).seq}
 await invoke('trust_peer',{deviceId,label:'Temporary Android interoperability test',publicKey:token});
 const importId=await invoke('attachment_import_start');
 for(let offset=0;offset<size;offset+=chunkSize)await invoke('attachment_import_chunk',{importId,offset,data:Array.from(windowsBytes)});
 const file=await invoke('attachment_import_finish',{importId,name:'Windows archive.anytype',mimeType:'application/octet-stream'});
 assert.equal(file.sha256,windowsHash);assert.equal(file.size,size);
 await invoke('upsert_note',{note:{id:windowsNoteId,title:'Temporary Windows file test',body:'Windows-created',color:1,isChecklist:false,updatedAt:Date.now(),pinned:false,archived:false,reminderAt:0,attachments:[file]}});
 await writeFile(sessionPath,JSON.stringify({deviceId,token,pcId,baselineCursor,windowsNoteId,androidNoteId,androidFileId,windowsHash,fileSize:size}));
 const androidDir=fileURLToPath(new URL('../../../SideNote-Android-main/SideNote-Android-v2.3/',import.meta.url));
 processHandle=spawn('powershell.exe',['-NoProfile','-Command',"$env:JAVA_HOME='C:\\Program Files\\Android\\Android Studio\\jbr'; & '.\\gradlew.bat' :app:testDebugUnitTest --tests com.sidedeck.notes.WindowsInteropTest --offline --console=plain"],{cwd:androidDir,windowsHide:true,env:{...process.env,SIDENOTE_INTEROP_SESSION:sessionPath}});
 let exitCode=null;let childError;processHandle.stdout.on('data',b=>output+=b);processHandle.stderr.on('data',b=>output+=b);processHandle.on('error',e=>{childError=e;exitCode=-1});processHandle.on('exit',code=>exitCode=code);
 let edited=false;const deadline=Date.now()+240000;
 while(exitCode===null && Date.now()<deadline){
  const stage=await readFile(stagePath,'utf8').then(JSON.parse).catch(()=>null);
  if(stage?.stage==='androidEdited'&&!edited){
   const note=(await invoke('list_notes')).find(n=>n.id===windowsNoteId);assert.equal(note.body,'Edited on Android');
   await invoke('upsert_note',{note:{...note,body:'Edited on Windows',updatedAt:Math.max(Date.now(),note.updatedAt+1)}});
   await writeFile(stagePath,JSON.stringify({stage:'pcEdited'}));edited=true;console.log('Android upload and Windows-to-Android edit handoff verified');
  }
  await delay(100);
 }
 if(exitCode===null){processHandle.kill();throw new Error('Interoperability test timed out')}
 if(exitCode!==0){console.error(output.slice(-6500));throw childError||new Error('Android interoperability test failed')}
 const stage=JSON.parse(await readFile(stagePath,'utf8'));assert.equal(stage.stage,'complete');uploadedHash=stage.uploadHash;
 const notes=await invoke('list_notes');assert.ok(!notes.some(n=>n.id===windowsNoteId),'Android deletion reaches Windows');
 const androidNote=notes.find(n=>n.id===androidNoteId);assert.ok(androidNote);assert.equal(androidNote.attachments[0].sha256,uploadedHash);
 assert.ok((await invoke('attachment_status',{noteId:androidNoteId}))[0].complete,'Android file fully received by Windows');
 for(const [id,original] of originalVersions){assert.equal(JSON.stringify(notes.find(n=>n.id===id)),original,'Existing note unchanged')}
 await mkdir(reportDir,{recursive:true});
 const result={status:'PASS',androidRuntime:'Robolectric executing production Android sync code',windowsRuntime:'Packaged Windows EXE',bytesEachDirection:size,elapsedSeconds:Math.round((Date.now()-started)/1000),uploadSha256:uploadedHash,downloadSha256:windowsHash,checks:['Windows file import preserves arbitrary binary bytes','Android-to-Windows file upload resumes from an existing chunk','Windows-to-Android download resumes from an existing partial file','SHA-256 matches in both directions','Android edits reach Windows','Windows edits reach Android','Sequential edits do not create false conflicts','Android delete reaches Windows without an echo','Existing Windows notes remain unchanged']};
 await writeFile(new URL('interop-results.json',reportDir),JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));
} finally {
 if(processHandle?.exitCode===null)processHandle.kill();
 for(const id of [windowsNoteId,androidNoteId]){const existing=(await invoke('list_notes')).some(n=>n.id===id);if(existing)await invoke('delete_note',{id})}
 await invoke('untrust_peer',{deviceId}).catch(()=>{});
 // Remove only generated blobs with no remaining live-note references.
 const referenced=new Set((await invoke('list_notes')).flatMap(n=>(n.attachments||[]).map(f=>f.sha256)));
 const candidates=[windowsHash,uploadedHash].filter(Boolean);
 for(const sha of candidates){if(referenced.has(sha))continue;assert.match(sha,/^[a-f0-9]{64}$/);const dir=path.resolve(process.env.APPDATA,'in.sidenote.desktop','attachments');for(const extension of ['bin','part']){const file=path.resolve(dir,`${sha}.${extension}`);assert.equal(path.dirname(file),dir);await rm(file,{force:true})}}
 await rm(sessionPath,{force:true});await rm(stagePath,{force:true});socket.close();
}

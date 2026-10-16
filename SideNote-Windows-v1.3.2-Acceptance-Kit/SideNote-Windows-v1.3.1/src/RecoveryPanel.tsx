import React,{useEffect,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import {listen} from '@tauri-apps/api/event';
type Note={id:string;title:string;body:string};
type Trash={note:Note;deletedAt:number};
type Conflict={id:number;noteId:string;operation:string;payload?:string;changedAt:number};
export function RecoveryPanel(){
 const [trash,setTrash]=useState<Trash[]>([]),[conflicts,setConflicts]=useState<Conflict[]>([]),[status,setStatus]=useState(''),[busy,setBusy]=useState(false);
 const load=()=>Promise.all([invoke<Trash[]>('trash_notes').then(setTrash),invoke<Conflict[]>('list_sync_conflicts').then(setConflicts)]).catch(e=>setStatus(String(e)));
 useEffect(()=>{void load();const stop=listen('sidenote_sync_updated',()=>void load());return()=>{void stop.then(f=>f())}},[]);
 const run=async(command:string,args:Record<string,unknown>)=>{setBusy(true);try{await invoke(command,args);await load();setStatus('Done. Your choice has been saved.')}catch(e){setStatus(String(e))}finally{setBusy(false)}};
 const unique=conflicts.filter((c,i,a)=>a.findIndex(n=>n.noteId===c.noteId)===i);
 return <section className="recovery-panel"><h2>Nothing lost.</h2><p>Restore deleted notes or choose between versions. Different versions can be kept as separate notes.</p>
  {status&&<p role="status">{status}</p>}
  <h3>Sync review · {unique.length}</h3>
  {!unique.length&&<p className="muted">Everything agrees. No conflicts need your attention.</p>}
  {unique.map(c=>{let remote:Note|undefined;try{remote=c.payload?JSON.parse(c.payload):undefined}catch{}return <article className="recovery-card" key={c.id}><h4>{remote?.title||'Note change'}</h4><p>{remote?.body?.slice(0,400)||'The other device deleted this note.'}</p><div className="recovery-actions">
   <button disabled={busy} onClick={()=>void run('resolve_sync_conflict',{conflictId:c.id,action:'keep_local'})}>Keep this PC version</button>
   <button disabled={busy} onClick={()=>void run('resolve_sync_conflict',{conflictId:c.id,action:'use_remote'})}>Use other device</button>
   {remote&&<button disabled={busy} onClick={()=>void run('resolve_sync_conflict',{conflictId:c.id,action:'save_copy'})}>Keep both</button>}
  </div></article>})}
  <h3>Trash · {trash.length}</h3>{!trash.length&&<p className="muted">Deleted notes will appear here.</p>}
  {trash.map(item=><article className="recovery-card" key={item.note.id}><h4>{item.note.title||'Untitled note'}</h4><p>{item.note.body.slice(0,180)}</p><small>{new Date(item.deletedAt).toLocaleString()}</small><div className="recovery-actions"><button disabled={busy} onClick={()=>void run('restore_trash',{id:item.note.id})}>Restore</button><button disabled={busy} onClick={()=>{if(window.confirm('Permanently remove this recovery copy?'))void run('permanently_delete_trash',{id:item.note.id})}}>Delete forever</button></div></article>)}
 </section>
}

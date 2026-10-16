import React, { useEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import {getCurrentWebview} from '@tauri-apps/api/webview';
import { Paperclip, Plus, Download, ExternalLink, X, LoaderCircle, FileText, Image as ImageIcon, Search, CheckSquare, Square } from 'lucide-react';
export type NoteAttachment = {id:string;name:string;mimeType:string;size:number;sha256:string};
const EMPTY_FILES:NoteAttachment[]=[];
function FilePreview({noteId,file,ready}:{noteId:string;file:NoteAttachment;ready:boolean}){
 const [preview,setPreview]=useState('');
 useEffect(()=>{let active=true;if(ready&&file.mimeType.startsWith('image/'))void invoke<string>('attachment_thumbnail',{noteId,attachmentId:file.id}).then(value=>{if(active)setPreview(value)}).catch(()=>{});return()=>{active=false}},[noteId,file.id,ready]);
 return preview?<img className="note-file-thumbnail" src={preview} alt={`Preview of ${file.name}`}/>:<span className="note-file-type">{file.mimeType.startsWith('image/')?<ImageIcon size={18}/>:<FileText size={18}/>}<small>{file.name.split('.').pop()?.slice(0,5).toUpperCase()||'FILE'}</small></span>
}
type FileState={id:string;complete:boolean;bytes:number;transfer?:string|null};
export function formatFileSize(bytes:number){if(bytes===0)return '0 B';const units=['B','KB','MB','GB','TB','PB'];const i=Math.min(Math.floor(Math.log(bytes)/Math.log(1024)),units.length-1);return `${(bytes/1024**i).toFixed(i>0?1:0)} ${units[i]}`;}
export function NoteFiles({noteId,files=EMPTY_FILES,compact,onChange}:{noteId:string;files?:NoteAttachment[];compact?:boolean;onChange:(files:NoteAttachment[])=>void|Promise<void>}){
 const input=useRef<HTMLInputElement>(null),cancelled=useRef(false),latest=useRef(files),root=useRef<HTMLDivElement>(null),importing=useRef(false);
 const [states,setStates]=useState<FileState[]>([]),[busy,setBusy]=useState(''),[error,setError]=useState(''),[expanded,setExpanded]=useState(false);
 const [query,setQuery]=useState(''),[kind,setKind]=useState('all'),[selected,setSelected]=useState<string[]>([]),[dragOver,setDragOver]=useState(false),[notice,setNotice]=useState('');
 useEffect(()=>{latest.current=files;setSelected(ids=>ids.filter(id=>files.some(f=>f.id===id)))},[files]);
 useEffect(()=>{
  if(!('__TAURI_INTERNALS__' in window))return;
  let active=true;
  const stop=getCurrentWebview().onDragDropEvent(event=>{
   if(!active)return;
   const payload=event.payload;
   if(payload.type==='leave'){setDragOver(false);return}
   const area=root.current?.closest('.card, .sticky-note-window')||root.current;const rect=area?.getBoundingClientRect();
   if(!rect||!rect.width||!('position' in payload))return;
   const x=payload.position.x/(window.devicePixelRatio||1),y=payload.position.y/(window.devicePixelRatio||1);
   const inside=x>=rect.left&&x<=rect.right&&y>=rect.top&&y<=rect.bottom;setDragOver(inside&&payload.type!=='drop');
   if(payload.type==='drop'&&inside&&!importing.current){
    importing.current=true;cancelled.current=false;setBusy('Adding dropped files...');setExpanded(true);setError('');
    void invoke<NoteAttachment[]>('attachment_import_paths',{paths:payload.paths}).then(async added=>{if(!cancelled.current){const next=[...latest.current,...added];latest.current=next;await onChange(next)}}).catch(e=>setError(String(e))).finally(()=>{setBusy('');importing.current=false})
   }
  });return()=>{active=false;void stop.then(f=>f())}
 },[noteId,onChange]);
 useEffect(()=>{let active=true;const poll=()=>void invoke<FileState[]>('attachment_status',{noteId}).then(value=>{if(active)setStates(value)}).catch(()=>{});poll();const timer=setInterval(poll,1000);return()=>{active=false;clearInterval(timer)}},[noteId]);
 useEffect(()=>()=>{cancelled.current=true},[noteId]);
 const add=async(selected:FileList|File[]|null)=>{
  if(!selected?.length||importing.current)return;
  importing.current=true;
  cancelled.current=false;setExpanded(true);setError('');
  try{for(const file of Array.from(selected)){
   if(cancelled.current)break;
   setBusy(`Adding ${file.name} · 0%`);
   const importId=await invoke<string>('attachment_import_start');
   try{
    for(let offset=0;offset<file.size;offset+=512*1024){
     if(cancelled.current)throw new Error('File import cancelled');
     const data=new Uint8Array(await file.slice(offset,offset+512*1024).arrayBuffer());
     await invoke('attachment_import_chunk',{importId,offset,data:Array.from(data)});
     setBusy(`Adding ${file.name} · ${Math.round(Math.min(offset+data.length,file.size)/file.size*100)}%`);
    }
    if(cancelled.current)throw new Error('File import cancelled');
    setBusy(`Verifying ${file.name}…`);
    const attachment=await invoke<NoteAttachment>('attachment_import_finish',{importId,name:file.name,mimeType:file.type||'application/octet-stream'});
    if(cancelled.current)throw new Error('File import cancelled');
    const next=[...latest.current,attachment];latest.current=next;await onChange(next);
   }finally{await invoke('attachment_import_cancel',{importId}).catch(()=>{})}
  }}catch(e){setError(String(e))}finally{setBusy('');importing.current=false;if(input.current)input.current.value=''}
 };
 const action=async(command:string,id:string)=>{setError('');try{await invoke(command,{noteId,attachmentId:id})}catch(e){setError(String(e))}};
 const visible=files.filter(file=>file.name.toLowerCase().includes(query.toLowerCase())&&(kind==='all'||kind==='images'&&file.mimeType.startsWith('image/')||kind==='documents'&&(file.mimeType==='application/pdf'||file.mimeType.startsWith('text/'))||kind==='media'&&(file.mimeType.startsWith('audio/')||file.mimeType.startsWith('video/'))));
 const saveSelected=async()=>{setError('');try{setNotice(await invoke<string>('attachment_save_bundle',{noteId,attachmentIds:selected}))}catch(e){setError(String(e))}};
 return <div ref={root} className={`note-files ${compact?'note-files--compact':''} ${dragOver?'is-file-drop':''}`} data-no-drag onMouseDown={e=>e.stopPropagation()}
  onDragOver={e=>{if(e.dataTransfer.types.includes('Files')){e.preventDefault();setDragOver(true)}}} onDragLeave={()=>setDragOver(false)} onDrop={e=>{e.preventDefault();e.stopPropagation();setDragOver(false);void add(e.dataTransfer.files)}}>
  <div className="note-files-heading">
   <button type="button" onClick={()=>setExpanded(!expanded)} aria-expanded={expanded}><Paperclip size={14}/><span>Files{files.length?` ? ${files.length}`:''}</span></button>
   <button type="button" title="Attach any file" disabled={!!busy} onClick={()=>input.current?.click()}><Plus size={14}/><span>Add</span></button>
   <input ref={input} type="file" multiple hidden onChange={e=>void add(e.target.files)}/>
  </div>
  {busy&&<div className="note-files-progress" role="status"><LoaderCircle size={12}/><span>{busy}</span><button type="button" onClick={()=>{cancelled.current=true}}>Cancel</button></div>}
  {error&&<div className="note-files-error" role="alert">{error}</div>}{notice&&<div role="status">{notice}</div>}
  {dragOver&&<div className="note-files-drop-hint">Drop files into this note</div>}
  {expanded&&<div className="note-files-list">
   {files.length>0&&<div className="note-files-tools"><label><Search size={13}/><input aria-label="Search files" placeholder="Find a file" value={query} onChange={e=>setQuery(e.target.value)}/></label><select aria-label="File type" value={kind} onChange={e=>setKind(e.target.value)}><option value="all">All types</option><option value="images">Images</option><option value="documents">Documents</option><option value="media">Audio & video</option></select><button type="button" disabled={!selected.length||!!busy} onClick={()=>void saveSelected()} title="Save selected files in one ZIP"><Download size={13}/>{selected.length?`Save ${selected.length} as ZIP`:'Save selected'}</button></div>}
   {files.length===0&&!busy&&<span className="note-files-empty">Drop photos, PDFs, videos, ZIPs or any file here. Syncs with your paired device.</span>}
   {files.length>0&&!visible.length&&<span className="note-files-empty">No matching files. Try a different name or type.</span>}
   {visible.map(file=>{const state=states.find(s=>s.id===file.id);const ready=state?.complete;const progress=file.size?Math.round((state?.bytes||0)/file.size*100):0;
    return <div className="note-file" key={file.id}>
     <button className="note-file-select" type="button" aria-pressed={selected.includes(file.id)} title={`Select ${file.name}`} onClick={()=>setSelected(ids=>ids.includes(file.id)?ids.filter(id=>id!==file.id):[...ids,file.id])}>{selected.includes(file.id)?<CheckSquare size={15}/>:<Square size={15}/>}</button>
     <FilePreview noteId={noteId} file={file} ready={!!ready}/>
     <div className="note-file-info"><span title={file.name}>{file.name}</span><small>{formatFileSize(file.size)} - {ready?(state?.transfer||'On this device'):state?.bytes?`Receiving ${progress}% - resumes after reconnect`:'Waiting for paired device'}</small>{!ready&&!!state?.bytes&&<progress max={100} value={progress} aria-label={`Receiving ${file.name}`}/>}</div>
     <button type="button" title={`Open ${file.name}`} disabled={!ready} onClick={()=>void action('attachment_open',file.id)}><ExternalLink size={13}/></button>
     <button type="button" title={`Save ${file.name} as`} disabled={!ready} onClick={()=>void action('attachment_save_as',file.id)}><Download size={13}/></button>
     <button type="button" title={`Remove ${file.name} from note`} disabled={!!busy} onClick={()=>{if(window.confirm(`Remove ${file.name} from this note? Saved copies elsewhere will remain.`))void onChange(latest.current.filter(f=>f.id!==file.id))}}><X size={13}/></button>
    </div>
   })}</div>}
 </div>
}

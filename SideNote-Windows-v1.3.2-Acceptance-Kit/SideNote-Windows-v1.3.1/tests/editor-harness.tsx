import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {NoteEditor} from '../src/NoteEditor';
import '../src/style.css';
function Harness(){
const [value,setValue]=useState('[x] Completed task\n[ ] Pending task\n\n**Bold** and *italic* and <u>underlined</u>');
const [key,setKey]=useState(0);
Object.assign(window,{ reloadEditor:(text:string)=>{setValue(text);setKey(k=>k+1)}, saved:value });
return <div style={{width:600,height:550,padding:20,background:'#fef4aa',display:'flex',flexDirection:'column'}}><NoteEditor key={key} value={value} onChange={v=>setValue(v)} onReady={editor=>Object.assign(window,{editor})} onOpenLink={url=>Object.assign(window,{openedLink:url})}/></div>
}
createRoot(document.getElementById('root')!).render(<Harness/>);
// Browser smoke check with synthetic plans only. It never calls a payment provider.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,mkdtemp,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createApp} from '../server.mjs';
const target=resolve('SideNote-Windows-v1.3.2-Acceptance-Kit/SideNote-Windows-v1.3.1/src-tauri/target');await mkdir(target,{recursive:true});const profile=await mkdtemp(resolve(target,'regional-ui-profile-'));
const evidence=resolve('acceptance-evidence/regional-pricing');await mkdir(evidence,{recursive:true});
const checkouts=[];
const provider={products:async region=>['monthly','yearly'].map(plan=>({plan,currency:region==='IN'?'inr':'usd',amount:region==='IN'?(plan==='monthly'?12300:123400):(plan==='monthly'?234:2345),billingRegion:region})),checkout:async(plan,url,id,region)=>{checkouts.push({plan,region});return {id,url:'https://rzp.io/synthetic-test',provider:'razorpay',billingRegion:region}}};
const store={provider:()=>provider,createCheckout:async(source,plan,secret,url,region)=>provider.checkout(plan,url,'synthetic-'+checkouts.length,region),claimCheckout:async()=>{throw new Error('Payment is not complete')}};
const server=createApp({store,webProvider:'razorpay',publicUrl:'https://example.test'});await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const base='http://127.0.0.1:'+server.address().port;
const browser=spawn('C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--disable-background-networking','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:'ignore'});let socket;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));let seq=0;const pending=new Map();
async function until(fn){for(let i=0;i<150;i++){try{const result=await fn();if(result)return result}catch{}await delay(100)}throw new Error('Browser check timed out')}
try{
 const port=await until(async()=>Number((await readFile(resolve(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]));
 const pages=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json();const page=pages.find(p=>p.type==='page');socket=new WebSocket(page.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject});socket.onmessage=e=>{const m=JSON.parse(e.data),p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(new Error(m.error.message)):p.resolve(m.result)}};
 const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}))});
 const evaluate=async expression=>{const result=await call('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw new Error('Browser JavaScript error');return result.result.value};
 await call('Page.navigate',{url:base});await until(()=>evaluate("document.querySelectorAll('#plan-cards button').length===2"));
 await evaluate("document.querySelector('#billing-region').value='IN';document.querySelector('#billing-region').dispatchEvent(new Event('change'))");await until(()=>evaluate("document.querySelector('#plan-cards').textContent.includes('INR')&&document.querySelectorAll('#plan-cards button').length===2"));
 assert.equal(await evaluate("document.querySelector('#plan-cards').textContent.includes('USD')"),false);
 const inr=await call('Page.captureScreenshot',{format:'png'});await writeFile(resolve(evidence,'india-fixture.png'),Buffer.from(inr.data,'base64'));
 await evaluate("document.querySelector('#plan-cards button').click()");await until(()=>evaluate("location.pathname==='/success.html'"));assert.deepEqual(checkouts[0],{plan:'monthly',region:'IN'});
 await call('Page.navigate',{url:base});await until(()=>evaluate("document.querySelectorAll('#plan-cards button').length===2"));assert.equal(await evaluate("document.querySelector('#billing-region').value"),'IN');
 await evaluate("document.querySelector('#billing-region').value='GLOBAL';document.querySelector('#billing-region').dispatchEvent(new Event('change'))");await until(()=>evaluate("document.querySelector('#plan-cards').textContent.includes('USD')&&document.querySelectorAll('#plan-cards button').length===2"));assert.equal(await evaluate("document.querySelector('#plan-cards').textContent.includes('INR')"),false);
 await evaluate("document.querySelector('#plans').scrollIntoView()");const usd=await call('Page.captureScreenshot',{format:'png'});await writeFile(resolve(evidence,'global-fixture.png'),Buffer.from(usd.data,'base64'));
 await evaluate("document.querySelectorAll('#plan-cards button')[1].click()");await until(()=>evaluate("location.pathname==='/success.html'"));assert.deepEqual(checkouts[1],{plan:'yearly',region:'GLOBAL'});
 const report={syntheticPlansOnly:true,realPaymentAttempted:false,checks:['INR prices for India','USD prices for other regions','billing region remembered','selected region preserved in checkout','monthly and yearly checkout paths'],passed:true};await writeFile(resolve(evidence,'browser-check.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 socket.send(JSON.stringify({id:++seq,method:'Browser.close'}));await delay(400);
}finally{socket?.close();browser.kill();await new Promise(resolve=>server.close(resolve))}

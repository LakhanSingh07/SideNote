import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync} from 'node:crypto';
import {RazorpayProvider} from '../providers.mjs';
import {Entitlements} from '../store.mjs';
import {secret} from '../crypto.mjs';
import {createApp} from '../server.mjs';
function fixture(){
 const regions={IN:{monthly:'plan_inr_month',yearly:'plan_inr_year'},GLOBAL:{monthly:'plan_usd_month',yearly:'plan_usd_year'}};
 const plans=Object.fromEntries(Object.entries(regions).flatMap(([region,ids])=>Object.entries(ids).map(([period,id])=>[id,{id,period,interval:1,item:{amount:region==='IN'?(period==='monthly'?7900:59900):(period==='monthly'?199:1499),currency:region==='IN'?'INR':'USD'}}])));
 const provider=new RazorpayProvider({regions});const created=[];
 provider.request=async(path,method,body)=>{if(path.startsWith('plans/'))return structuredClone(plans[path.slice(6)]);if(path==='subscriptions'&&method==='POST'){created.push(body);return {id:'sub_'+created.length,short_url:'https://rzp.io/example'}}if(path.startsWith('subscriptions/'))return {id:path.slice(14),plan_id:created[Number(path.slice(18))-1]?.plan_id||'plan_usd_year',status:'active',current_end:200000,customer_id:'customer'};throw new Error('Unexpected test request')};
 return {provider,plans,created}
}
test('India receives INR; every other billing region receives USD with the correct periods',async()=>{
 const {provider}=fixture();const india=await provider.products('IN'),world=await provider.products('GLOBAL');assert.deepEqual(india.map(p=>p.currency),['inr','inr']);assert.deepEqual(world.map(p=>p.currency),['usd','usd']);assert.deepEqual(india.map(p=>p.amount),[7900,59900]);assert.deepEqual(world.map(p=>p.amount),[199,1499]);assert.ok(world.every(p=>p.billingRegion==='GLOBAL'));assert.ok(world.every(p=>!('id'in p)));await assert.rejects(()=>provider.products('US'),/Invalid billing/)
});
test('checkout uses the configured server plan for the selected region',async()=>{const {provider,created}=fixture();await provider.checkout('monthly','https://example.test','one','IN');await provider.checkout('yearly','https://example.test','two','GLOBAL');assert.equal(created[0].plan_id,'plan_inr_month');assert.equal(created[1].plan_id,'plan_usd_year');assert.equal(created[1].notes.sidenote_billing_region,'GLOBAL');await assert.rejects(()=>provider.checkout('lifetime','https://example.test','three','IN'),/Invalid plan/)});
test('wrong currency, period, amount or interval blocks checkout before creating a subscription',async()=>{
 for(const mutate of [p=>p.item.currency='INR',p=>p.period='monthly',p=>p.interval=2,p=>p.item.amount=-1]){const {provider,plans,created}=fixture();mutate(plans.plan_usd_year);await assert.rejects(()=>provider.checkout('yearly','https://example.test','one','GLOBAL'),/Invalid regional plan/);assert.equal(created.length,0)}
});
test('missing USD configuration never falls back to a legacy INR plan',async()=>{const provider=new RazorpayProvider({monthly:'plan_inr_month',yearly:'plan_inr_year'});provider.request=()=>{throw new Error('Should not contact provider')};await assert.rejects(()=>provider.products('GLOBAL'),/not configured/);await assert.rejects(()=>provider.checkout('monthly','https://example.test','one','GLOBAL'),/not configured/);assert.throws(()=>new RazorpayProvider({regions:{IN:{monthly:'same'},GLOBAL:{monthly:'same'}}}),/distinct/)});
test('both regional subscription IDs can be restored',async()=>{const {provider,created}=fixture();await provider.checkout('monthly','https://example.test','one','IN');await provider.checkout('yearly','https://example.test','two','GLOBAL');for(const plan of [created[0].plan_id,created[1].plan_id]){const old=provider.request.bind(provider);provider.request=(path,...rest)=>path==='subscriptions/sub_restore'?Promise.resolve({id:'sub_restore',plan_id:plan,status:'active',current_end:200000}):old(path,...rest);assert.equal((await provider.refresh('sub_restore')).accessUntil,200000)}});
test('HTTP products and checkout preserve billing region and ignore client price and plan IDs',async()=>{
 const {provider,created}=fixture();const {privateKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'});const store=new Entitlements({masterKey:secret(),signingKey:privateKey,providers:{razorpay:provider},clock:()=>100000});const server=createApp({store,webProvider:'razorpay',publicUrl:'https://example.test'});await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const base='http://127.0.0.1:'+server.address().port;
 try{const products=await (await fetch(base+'/v1/products?region=IN')).json();assert.equal(products.products[0].currency,'inr');const response=await fetch(base+'/v1/web/checkout',{method:'POST',body:JSON.stringify({plan:'yearly',claimSecret:secret(),billingRegion:'GLOBAL',amount:1,currency:'INR',planId:'attacker-plan'})});assert.equal(response.status,200);assert.equal((await response.json()).billingRegion,'GLOBAL');assert.equal(created[0].plan_id,'plan_usd_year');const invalid=await fetch(base+'/v1/web/checkout',{method:'POST',body:JSON.stringify({plan:'yearly',claimSecret:secret(),billingRegion:'USD'})});assert.equal(invalid.status,400);assert.equal(created.length,1)}finally{await new Promise(resolve=>server.close(resolve));store.close()}
});

test('existing legacy INR subscriptions still restore after new regional prices are configured',async()=>{
 const provider=new RazorpayProvider({monthly:'old_inr_month',yearly:'old_inr_year',regions:{IN:{monthly:'new_inr_month',yearly:'new_inr_year'},GLOBAL:{monthly:'usd_month',yearly:'usd_year'}}});
 provider.request=async path=>path.startsWith('subscriptions/')?{id:'sub_old',plan_id:'old_inr_year',status:'active',current_end:200000}:{id:path.slice(6),period:'yearly',interval:1,item:{currency:'INR',amount:59900}};
 const restored=await provider.refresh('sub_old');assert.equal(restored.plan,'yearly');assert.equal(restored.accessUntil,200000)
});

import {DatabaseSync} from 'node:sqlite';
import {randomUUID} from 'node:crypto';
import {billingRegion} from './providers.mjs';
import {digest,secret,seal,unseal,ticket} from './crypto.mjs';
export class Entitlements {
 constructor({database=':memory:',masterKey,signingKey,providers,maxDevices=5,clock=()=>Math.floor(Date.now()/1000)}){
  this.db=new DatabaseSync(database);this.masterKey=masterKey;this.signingKey=signingKey;this.providers=providers;this.maxDevices=maxDevices;this.clock=clock;
  this.db.exec(`PRAGMA journal_mode=WAL;PRAGMA busy_timeout=5000;
   CREATE TABLE IF NOT EXISTS licenses(id TEXT PRIMARY KEY,source TEXT NOT NULL,ref_hash TEXT NOT NULL,reference_enc TEXT NOT NULL,secret_hash TEXT UNIQUE NOT NULL,secret_enc TEXT NOT NULL,customer TEXT NOT NULL,plan TEXT NOT NULL,state TEXT NOT NULL,access_until INTEGER NOT NULL,verified_at INTEGER NOT NULL,UNIQUE(source,ref_hash));
   CREATE TABLE IF NOT EXISTS activations(license_id TEXT NOT NULL,device_id TEXT NOT NULL,last_seen INTEGER NOT NULL,PRIMARY KEY(license_id,device_id));
   CREATE TABLE IF NOT EXISTS checkouts(id TEXT PRIMARY KEY,claim_hash TEXT NOT NULL,source TEXT NOT NULL,provider_ref TEXT NOT NULL,created_at INTEGER NOT NULL,license_id TEXT);
   CREATE TABLE IF NOT EXISTS trials(device_hash TEXT PRIMARY KEY,id TEXT NOT NULL,started_at INTEGER NOT NULL,access_until INTEGER NOT NULL);
   CREATE TABLE IF NOT EXISTS webhook_events(id TEXT PRIMARY KEY,processed_at INTEGER NOT NULL);`)
 }
 trial(deviceId,trialDeviceKey){
  if(typeof deviceId!=='string'||!/^[a-zA-Z0-9._:-]{1,128}$/.test(deviceId)||typeof trialDeviceKey!=='string'||!/^[a-f0-9]{64}$/.test(trialDeviceKey))throw new Error('Invalid trial device identifier');
  const hash=digest(trialDeviceKey),now=this.clock();
  this.db.prepare('INSERT OR IGNORE INTO trials VALUES (?,?,?,?)').run(hash,randomUUID(),now,now+7*86400);
  const row=this.db.prepare('SELECT * FROM trials WHERE device_hash=?').get(hash);
  return {ticket:ticket(this.signingKey,{id:row.id,plan:'trial',accessUntil:row.access_until},deviceId,now),active:row.access_until>now,accessUntil:row.access_until,plan:'trial'}
 }
 provider(source){const p=this.providers[source];if(!p)throw new Error('This purchase channel is not configured');return p}
 async upsert(source,verified){
  if(!['monthly','yearly'].includes(verified.plan)||!Number.isSafeInteger(verified.accessUntil)||verified.accessUntil<0)throw new Error('Invalid verified subscription');
  const refHash=digest(verified.reference);let row=this.db.prepare('SELECT * FROM licenses WHERE source=? AND ref_hash=?').get(source,refHash);
  if(!row){const key=secret();this.db.prepare('INSERT INTO licenses VALUES (?,?,?,?,?,?,?,?,?,?,?)').run(randomUUID(),source,refHash,seal(verified.reference,this.masterKey),digest(key),seal(key,this.masterKey),verified.customer||'',verified.plan,verified.state,verified.accessUntil,this.clock());row=this.db.prepare('SELECT * FROM licenses WHERE source=? AND ref_hash=?').get(source,refHash)}
  else{this.db.prepare('UPDATE licenses SET customer=?,plan=?,state=?,access_until=?,verified_at=? WHERE id=?').run(verified.customer||'',verified.plan,verified.state,verified.accessUntil,this.clock(),row.id);row=this.db.prepare('SELECT * FROM licenses WHERE id=?').get(row.id)}
  return row
 }
 async play(purchaseToken,deviceId){if(typeof purchaseToken!=='string'||purchaseToken.length<10||purchaseToken.length>4096)throw new Error('Invalid purchase token');const verified=await this.provider('play').refresh(purchaseToken);const row=await this.upsert('play',verified);return this.activateRow(row,deviceId,true)}
 async createCheckout(source,plan,claimSecret,publicUrl,region='GLOBAL'){
  region=billingRegion(region);
  if(!/^[a-f0-9]{64}$/.test(claimSecret))throw new Error('Invalid checkout claim');if(!['monthly','yearly'].includes(plan))throw new Error('Invalid plan');
  const id=randomUUID();const result=await this.provider(source).checkout(plan,publicUrl,id,region);
  this.db.prepare('INSERT INTO checkouts(id,claim_hash,source,provider_ref,created_at) VALUES (?,?,?,?,?)').run(id,digest(claimSecret),source,result.reference,this.clock());return {id,url:result.url,provider:source,billingRegion:region}
 }
 async claimCheckout(id,claimSecret,deviceId){
  const checkout=this.db.prepare('SELECT * FROM checkouts WHERE id=? AND claim_hash=?').get(id,digest(String(claimSecret)));
  if(!checkout||this.clock()-checkout.created_at>7*86400)throw new Error('Checkout claim is invalid or expired');
  const verified=await this.provider(checkout.source).claim(checkout.provider_ref);
  if(verified.accessUntil<=this.clock())throw new Error('Payment is not complete');
  const row=await this.upsert(checkout.source,verified);this.db.prepare('UPDATE checkouts SET license_id=? WHERE id=?').run(row.id,id);return this.activateRow(row,deviceId,true)
 }
 row(key){if(!/^[a-f0-9]{64}$/.test(String(key)))throw new Error('Invalid activation code');const row=this.db.prepare('SELECT * FROM licenses WHERE secret_hash=?').get(digest(key));if(!row)throw new Error('Invalid activation code');return row}
 async refreshRow(row){return this.upsert(row.source,await this.provider(row.source).refresh(unseal(row.reference_enc,this.masterKey)))}
 async activate(key,deviceId){const row=await this.refreshRow(this.row(key));return this.activateRow(row,deviceId,false)}
 activateRow(row,deviceId,includeKey){
  if(typeof deviceId!=='string'||!deviceId.length||deviceId.length>128||!/^[a-zA-Z0-9._:-]+$/.test(deviceId))throw new Error('Invalid device identifier');
  this.db.exec('BEGIN IMMEDIATE');
  try{
   const exists=this.db.prepare('SELECT 1 FROM activations WHERE license_id=? AND device_id=?').get(row.id,deviceId);
   if(!exists&&this.db.prepare('SELECT COUNT(*) AS count FROM activations WHERE license_id=?').get(row.id).count>=this.maxDevices)throw new Error('Device limit reached. Remove an old device first.');
   this.db.prepare('INSERT INTO activations VALUES (?,?,?) ON CONFLICT(license_id,device_id) DO UPDATE SET last_seen=excluded.last_seen').run(row.id,deviceId,this.clock());this.db.exec('COMMIT')
  }catch(e){this.db.exec('ROLLBACK');throw e}
  const license={id:row.id,plan:row.plan,accessUntil:row.access_until};
  return {ticket:ticket(this.signingKey,license,deviceId,this.clock()),active:row.access_until>this.clock(),accessUntil:row.access_until,plan:row.plan,...(includeKey?{activationCode:unseal(row.secret_enc,this.masterKey)}:{})}
 }
 devices(key){const row=this.row(key);return this.db.prepare('SELECT device_id AS deviceId,last_seen AS lastSeen FROM activations WHERE license_id=? ORDER BY last_seen DESC').all(row.id)}
 removeDevice(key,id){const row=this.row(key);this.db.prepare('DELETE FROM activations WHERE license_id=? AND device_id=?').run(row.id,id)}
 async manage(key,publicUrl){const row=this.row(key);const provider=this.provider(row.source);if(!provider.manage)throw new Error('Manage this subscription in Google Play');return provider.manage({reference:unseal(row.reference_enc,this.masterKey),customer:row.customer},publicUrl)}
 async cancel(key){const row=this.row(key);const p=this.provider(row.source);if(!p.cancel)throw new Error('Use the billing management page to cancel');return p.cancel({reference:unseal(row.reference_enc,this.masterKey),customer:row.customer})}
 async webhook(source,id,reference){
  if(this.db.prepare('SELECT 1 FROM webhook_events WHERE id=?').get(source+':'+id))return;
  const row=this.db.prepare('SELECT * FROM licenses WHERE source=? AND ref_hash=?').get(source,digest(reference));if(row)await this.refreshRow(row);
  this.db.prepare('INSERT OR IGNORE INTO webhook_events VALUES (?,?)').run(source+':'+id,this.clock())
 }
 close(){this.db.close()}
}

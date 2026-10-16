import {createHash,createHmac,createPrivateKey,createPublicKey,randomBytes,sign,verify,timingSafeEqual,createCipheriv,createDecipheriv} from 'node:crypto';
export const digest=value=>createHash('sha256').update(value).digest('hex');
export const secret=()=>randomBytes(32).toString('hex');
export function equal(a,b){const x=Buffer.from(a),y=Buffer.from(b);return x.length===y.length&&timingSafeEqual(x,y)}
export function seal(value,key){if(!/^[a-f0-9]{64}$/.test(key))throw new Error('License encryption key is not configured');const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',Buffer.from(key,'hex'),iv);const ciphertext=Buffer.concat([cipher.update(value,'utf8'),cipher.final()]);return Buffer.concat([iv,cipher.getAuthTag(),ciphertext]).toString('base64')}
export function unseal(value,key){const bytes=Buffer.from(value,'base64'),cipher=createDecipheriv('aes-256-gcm',Buffer.from(key,'hex'),bytes.subarray(0,12));cipher.setAuthTag(bytes.subarray(12,28));return Buffer.concat([cipher.update(bytes.subarray(28)),cipher.final()]).toString('utf8')}
export function ticket(privateKey,license,deviceId,now=Math.floor(Date.now()/1000)){
 const head=Buffer.from(JSON.stringify({alg:'ES256',typ:'JWT'})).toString('base64url');
 const active=license.accessUntil>now;
 const body=Buffer.from(JSON.stringify({iss:'sidenote-pro',aud:'sidenote-apps',sub:license.id,deviceId,plan:license.plan,active,iat:now,exp:active?Math.min(license.accessUntil,now+72*3600):now+3600,accessUntil:license.accessUntil})).toString('base64url');
 const signature=sign('sha256',Buffer.from(head+'.'+body),{key:privateKey,dsaEncoding:'ieee-p1363'}).toString('base64url');return head+'.'+body+'.'+signature;
}
export function verifyTicket(token,publicKey,deviceId,now=Math.floor(Date.now()/1000)){
 const parts=token.split('.');if(parts.length!==3)throw new Error('Invalid ticket');const [head,body,sig]=parts;
 const header=JSON.parse(Buffer.from(head,'base64url'));if(header.alg!=='ES256'||header.typ!=='JWT')throw new Error('Invalid algorithm');
 if(!verify('sha256',Buffer.from(head+'.'+body),{key:publicKey,dsaEncoding:'ieee-p1363'},Buffer.from(sig,'base64url')))throw new Error('Invalid signature');
 const claims=JSON.parse(Buffer.from(body,'base64url'));if(claims.iss!=='sidenote-pro'||claims.aud!=='sidenote-apps'||claims.deviceId!==deviceId||claims.exp<=now||claims.iat>now+300)throw new Error('Invalid claims');return claims;
}
export function publicKeyRaw(privateKey){const jwk=createPublicKey(privateKey).export({format:'jwk'});if(jwk.crv!=='P-256')throw new Error('Use a P-256 license signing key');return Buffer.concat([Buffer.from([4]),Buffer.from(jwk.x,'base64url'),Buffer.from(jwk.y,'base64url')]).toString('base64')}
export function stripeSignature(raw,header,secret,now=Math.floor(Date.now()/1000)){
 const values=String(header).split(',').map(p=>p.split('=')),t=values.find(p=>p[0]==='t')?.[1];if(!t||!/^\d+$/.test(t)||Math.abs(now-Number(t))>300)return false;
 const expected=createHmac('sha256',secret).update(t+'.').update(raw).digest('hex');return values.some(([key,value])=>key==='v1'&&value&&equal(value,expected));
}
export function razorpaySignature(raw,signature,key){return /^[a-f0-9]{64}$/.test(signature||'')&&equal(signature,createHmac('sha256',key).update(raw).digest('hex'))}

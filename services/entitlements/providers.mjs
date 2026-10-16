import {createPrivateKey,sign,verify,createPublicKey} from 'node:crypto';
import {readFile} from 'node:fs/promises';
const unix=()=>Math.floor(Date.now()/1000);
async function json(url,options={}){const response=await fetch(url,{...options,signal:AbortSignal.timeout(20000)});if(!response.ok)throw new Error('Billing provider temporarily unavailable');const text=await response.text();return text?JSON.parse(text):{}}
function allowedPlan(plan,config){if(!['monthly','yearly'].includes(plan))throw new Error('Invalid plan');const id=config[plan];if(!id)throw new Error('This plan is not configured');return id}
export class StripeProvider {
 constructor(config){this.c=config}
 request(path,method='GET',body){return json('https://api.stripe.com/v1/'+path,{method,headers:{Authorization:'Bearer '+this.c.key,...(body?{'Content-Type':'application/x-www-form-urlencoded'}:{})},body:body?new URLSearchParams(body):undefined})}
 async checkout(plan,returnUrl,id){const price=allowedPlan(plan,this.c);const session=await this.request('checkout/sessions','POST',{'mode':'subscription','line_items[0][price]':price,'line_items[0][quantity]':'1',success_url:returnUrl+'/success.html?checkout='+id,cancel_url:returnUrl,'client_reference_id':id});return {reference:session.id,url:session.url}}
 async claim(reference){const session=await this.request('checkout/sessions/'+encodeURIComponent(reference));if(session.payment_status!=='paid'||!session.subscription)throw new Error('Payment is not complete');return this.refresh(session.subscription)}
 async refresh(reference){const s=await this.request('subscriptions/'+encodeURIComponent(reference));const item=s.items?.data?.[0];const price=item?.price?.id;const plan=price===this.c.monthly?'monthly':price===this.c.yearly?'yearly':null;if(!plan)throw new Error('Unknown subscription product');return {reference:s.id,customer:s.customer,plan,state:s.status,accessUntil:['active','trialing'].includes(s.status)?(item?.current_period_end??s.current_period_end??0):0}}
 async products(){return Promise.all(['monthly','yearly'].filter(p=>this.c[p]).map(async plan=>{const p=await this.request('prices/'+encodeURIComponent(this.c[plan]));return {plan,amount:p.unit_amount,currency:p.currency,interval:p.recurring?.interval}}))}
 async manage(license,returnUrl){const s=await this.request('billing_portal/sessions','POST',{customer:license.customer,return_url:returnUrl});return s.url}
}
export function billingRegion(value='GLOBAL'){
 if(value!=='IN'&&value!=='GLOBAL')throw new Error('Invalid billing region');return value
}
export class RazorpayProvider {
 constructor(config){
  this.c=config;
  this.regions={IN:{monthly:config.regions?.IN?.monthly||config.monthly,yearly:config.regions?.IN?.yearly||config.yearly},GLOBAL:{monthly:config.regions?.GLOBAL?.monthly,yearly:config.regions?.GLOBAL?.yearly}};
  const ids=Object.values(this.regions).flatMap(region=>Object.values(region)).filter(Boolean);
  if(new Set(ids).size!==ids.length)throw new Error('Invalid regional configuration: each plan needs a distinct Razorpay ID')
 }
 request(path,method='GET',body){return json('https://api.razorpay.com/v1/'+path,{method,headers:{Authorization:'Basic '+Buffer.from(this.c.key+':'+this.c.secret).toString('base64'),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined})}
 async product(plan,region='GLOBAL',legacyId){
  region=billingRegion(region);const id=legacyId||allowedPlan(plan,this.regions[region]);const p=await this.request('plans/'+encodeURIComponent(id));const currency=region==='IN'?'INR':'USD';
  if(p.id!==id||p.item?.currency!==currency||p.period!==plan||p.interval!==1||!Number.isSafeInteger(p.item?.amount)||p.item.amount<=0)throw new Error('Invalid regional plan currency, price or billing period');
  return {plan,amount:p.item.amount,currency:currency.toLowerCase(),interval:p.period,billingRegion:region,id}
 }
 async checkout(plan,returnUrl,id,region='GLOBAL'){
  const product=await this.product(plan,region);
  const s=await this.request('subscriptions','POST',{plan_id:product.id,total_count:plan==='monthly'?120:10,customer_notify:1,notes:{sidenote_checkout:id,sidenote_billing_region:product.billingRegion},expire_by:unix()+86400});
  return {reference:s.id,url:s.short_url}
 }
 async claim(reference){return this.refresh(reference)}
 async refresh(reference){
  const s=await this.request('subscriptions/'+encodeURIComponent(reference));let selected;
  for(const [region,plans]of Object.entries(this.regions))for(const plan of ['monthly','yearly'])if(plans[plan]&&plans[plan]===s.plan_id)selected={plan,region};
  if(!selected)for(const plan of ['monthly','yearly'])if(this.c[plan]&&this.c[plan]===s.plan_id)selected={plan,region:'IN'};
  if(!selected)throw new Error('Unknown subscription product');
  await this.product(selected.plan,selected.region,s.plan_id);
  return {reference:s.id,customer:s.customer_id||'',plan:selected.plan,state:s.status,accessUntil:s.status==='active'?s.current_end||0:0}
 }
 async products(region='GLOBAL'){
  region=billingRegion(region);
  if(!this.regions[region].monthly||!this.regions[region].yearly)throw new Error('This billing region is not configured');
  return Promise.all(['monthly','yearly'].map(async plan=>{const {id,...product}=await this.product(plan,region);return product}))
 }
 async manage(license){const s=await this.request('subscriptions/'+encodeURIComponent(license.reference));if(!s.short_url)throw new Error('Billing management is unavailable');return s.short_url}
 async cancel(license){await this.request('subscriptions/'+encodeURIComponent(license.reference)+'/cancel','POST',{cancel_at_cycle_end:1});return 'Subscription will end after the current paid period.'}
}
export class GooglePlayProvider {
 constructor(config){this.c=config;this.cached=null}
 async accessToken(){
  if(this.cached?.until>unix()+60)return this.cached.token;
  const c=JSON.parse(await readFile(this.c.credentials,'utf8'));const now=unix();const head=Buffer.from(JSON.stringify({alg:'RS256',typ:'JWT'})).toString('base64url');const body=Buffer.from(JSON.stringify({iss:c.client_email,scope:'https://www.googleapis.com/auth/androidpublisher',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600})).toString('base64url');
  const assertion=head+'.'+body+'.'+sign('RSA-SHA256',Buffer.from(head+'.'+body),c.private_key).toString('base64url');const value=await json('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion})});this.cached={token:value.access_token,until:now+value.expires_in};return value.access_token
 }
 async refresh(reference){
  const base='https://androidpublisher.googleapis.com/androidpublisher/v3/applications/'+encodeURIComponent(this.c.packageName)+'/purchases/subscriptions';const token=await this.accessToken();const s=await json(base+'v2/tokens/'+encodeURIComponent(reference),{headers:{Authorization:'Bearer '+token}});
  const item=s.lineItems?.find(i=>i.productId===this.c.product);if(!item)throw new Error('Unknown subscription product');const basePlan=item.offerDetails?.basePlanId;const plan=basePlan===this.c.monthly?'monthly':basePlan===this.c.yearly?'yearly':null;if(!plan)throw new Error('Unknown subscription plan');
  const allowed=['SUBSCRIPTION_STATE_ACTIVE','SUBSCRIPTION_STATE_CANCELED','SUBSCRIPTION_STATE_IN_GRACE_PERIOD'].includes(s.subscriptionState);const accessUntil=allowed?Math.floor(Date.parse(item.expiryTime)/1000):0;
  if(accessUntil>unix()&&s.acknowledgementState==='ACKNOWLEDGEMENT_STATE_PENDING')await json(base+'/'+encodeURIComponent(this.c.product)+'/tokens/'+encodeURIComponent(reference)+':acknowledge',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:'{}'});
  return {reference,customer:'',plan,state:s.subscriptionState,accessUntil:Number.isFinite(accessUntil)?accessUntil:0}
 }
}
let googleKeys;
export async function verifyGooglePush(token,audience,email){
 const [head,body,signature]=String(token||'').split('.');if(!head||!body||!signature)throw new Error('Unauthorized push');const h=JSON.parse(Buffer.from(head,'base64url'));if(h.alg!=='RS256')throw new Error('Unauthorized push');
 if(!googleKeys||googleKeys.until<unix())googleKeys={...(await json('https://www.googleapis.com/oauth2/v3/certs')),until:unix()+1800};
 let jwk=googleKeys.keys.find(k=>k.kid===h.kid);if(!jwk){googleKeys=null;throw new Error('Unknown Google signing key')}
 if(!verify('RSA-SHA256',Buffer.from(head+'.'+body),createPublicKey({key:jwk,format:'jwk'}),Buffer.from(signature,'base64url')))throw new Error('Unauthorized push');
 const claims=JSON.parse(Buffer.from(body,'base64url'));if(!['accounts.google.com','https://accounts.google.com'].includes(claims.iss)||claims.aud!==audience||claims.email!==email||claims.email_verified!==true||claims.exp<=unix()||claims.iat>unix()+300)throw new Error('Unauthorized push');return claims
}

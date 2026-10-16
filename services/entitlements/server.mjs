import {createServer} from 'node:http';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve,dirname} from 'node:path';
import {Entitlements} from './store.mjs';
import {StripeProvider,RazorpayProvider,GooglePlayProvider,verifyGooglePush,billingRegion} from './providers.mjs';
import {stripeSignature,razorpaySignature,publicKeyRaw} from './crypto.mjs';
const here=dirname(fileURLToPath(import.meta.url));
function validHttps(url){try{return new URL(url).protocol==='https:'}catch{return false}}
export function createApp({store,webProvider,publicUrl='',stripeWebhook='',razorpayWebhook='',googleAudience='',googleEmail='',verifyPush=verifyGooglePush}){
 const limits=new Map();
 return createServer(async(req,res)=>{
  res.setHeader('Content-Type','application/json');res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');
  const reply=(status,body)=>{res.writeHead(status);res.end(JSON.stringify(body))};
  const url=new URL(req.url,'http://localhost');
  if(req.method==='GET'&&!url.pathname.startsWith('/v1/')){
   if(url.pathname==='/health')return reply(200,{service:'sidenote-entitlements',configured:!!store,webProvider:webProvider||null});
   const asset={'/':'index.html','/index.html':'index.html','/success.html':'success.html','/manage.html':'manage.html','/privacy.html':'privacy.html','/terms.html':'terms.html','/support.html':'support.html','/app.js':'app.js','/style.css':'style.css'}[url.pathname];
   if(!asset)return reply(404,{error:'Not found'});
   try{res.setHeader('Content-Type',asset.endsWith('.js')?'text/javascript':asset.endsWith('.css')?'text/css':'text/html');res.setHeader('Content-Security-Policy',"default-src 'self';script-src 'self';style-src 'self';img-src 'self' data:;connect-src 'self';frame-ancestors 'none';base-uri 'none';form-action 'self'");res.writeHead(200);return res.end(readFileSync(resolve(here,'../../website',asset)))}catch{return reply(404,{error:'Not found'})}
  }
  if(!store)return reply(503,{error:'Subscriptions are not configured. No payment has been initiated.'});
  const bucket=(req.socket.remoteAddress||'local')+':'+url.pathname;const now=Date.now();const limit=limits.get(bucket);if(limit&&now-limit.at<60000&&limit.count>=(url.pathname.includes('checkout')?10:60))return reply(429,{error:'Please wait a moment before trying again'});limits.set(bucket,limit&&now-limit.at<60000?{at:limit.at,count:limit.count+1}:{at:now,count:1});if(limits.size>5000)for(const [key,value]of limits)if(now-value.at>60000)limits.delete(key);
  try{
   if(req.method==='GET'&&url.pathname==='/v1/products')return reply(200,{products:await store.provider(webProvider).products(billingRegion(url.searchParams.get('region')||'GLOBAL'))});
   if(req.method!=='POST')return reply(405,{error:'Method not allowed'});
   const chunks=[];let count=0;for await(const chunk of req){count+=chunk.length;if(count>1024*1024)return reply(413,{error:'Request too large'});chunks.push(chunk)}const raw=Buffer.concat(chunks);
   const body=JSON.parse(raw.toString('utf8')||'{}');
   if(url.pathname==='/v1/hooks/stripe'){
    if(!stripeWebhook||!stripeSignature(raw,req.headers['stripe-signature'],stripeWebhook))return reply(401,{error:'Invalid webhook signature'});
    if(body.type?.startsWith('customer.subscription.'))await store.webhook('stripe',body.id,body.data.object.id);return reply(200,{received:true})
   }
   if(url.pathname==='/v1/hooks/razorpay'){
    if(!razorpayWebhook||!razorpaySignature(raw,req.headers['x-razorpay-signature'],razorpayWebhook))return reply(401,{error:'Invalid webhook signature'});
    const subscription=body.payload?.subscription?.entity;if(subscription?.id)await store.webhook('razorpay',String(req.headers['x-razorpay-event-id']||body.created_at+':'+body.event+':'+subscription.id),subscription.id);return reply(200,{received:true})
   }
   if(url.pathname==='/v1/hooks/google'){
    if(!googleAudience||!googleEmail)return reply(503,{error:'Push authentication is not configured'});
    await verifyPush(String(req.headers.authorization||'').replace(/^Bearer /,''),googleAudience,googleEmail);
    const message=JSON.parse(Buffer.from(body.message?.data||'','base64').toString());if(message.subscriptionNotification?.purchaseToken)await store.webhook('play',body.message.messageId,message.subscriptionNotification.purchaseToken);return reply(200,{received:true})
   }
   if(url.pathname==='/v1/play/verify')return reply(200,await store.play(body.purchaseToken,body.deviceId));
   if(url.pathname==='/v1/trial')return reply(200,store.trial(body.deviceId,body.trialDeviceKey));
   if(url.pathname==='/v1/activate')return reply(200,await store.activate(body.activationCode,body.deviceId));
   if(url.pathname==='/v1/devices')return reply(200,{devices:store.devices(body.activationCode)});
   if(url.pathname==='/v1/devices/remove'){store.removeDevice(body.activationCode,body.deviceId);return reply(200,{removed:true})}
   if(url.pathname==='/v1/web/checkout'){
    if(!validHttps(publicUrl))return reply(503,{error:'The secure checkout domain is not configured'});
    return reply(200,await store.createCheckout(webProvider,body.plan,body.claimSecret,publicUrl,body.billingRegion))
   }
   if(url.pathname==='/v1/web/claim')return reply(200,await store.claimCheckout(body.id,body.claimSecret,body.deviceId));
   if(url.pathname==='/v1/manage')return reply(200,{url:await store.manage(body.activationCode,publicUrl)});
   if(url.pathname==='/v1/cancel')return reply(200,{message:await store.cancel(body.activationCode)});
   return reply(404,{error:'Not found'})
  }catch(error){
   // Provider responses can contain purchase tokens. Never log request bodies or upstream error bodies.
   const known=/Invalid|invalid|not complete|expired|limit reached|Unknown|not configured|Manage this|Use the billing/.test(error.message);
   reply(known?400:503,{error:known?error.message:'Verification is temporarily unavailable. Your purchase can be restored when the connection returns.'})
  }
 })
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
 const env=process.env;let store=null;const providers={};
 if(env.STRIPE_SECRET_KEY)providers.stripe=new StripeProvider({key:env.STRIPE_SECRET_KEY,monthly:env.STRIPE_MONTHLY_PRICE,yearly:env.STRIPE_YEARLY_PRICE});
 if(env.RAZORPAY_KEY_ID&&env.RAZORPAY_KEY_SECRET)providers.razorpay=new RazorpayProvider({key:env.RAZORPAY_KEY_ID,secret:env.RAZORPAY_KEY_SECRET,monthly:env.RAZORPAY_MONTHLY_PLAN,yearly:env.RAZORPAY_YEARLY_PLAN,regions:{IN:{monthly:env.RAZORPAY_INR_MONTHLY_PLAN,yearly:env.RAZORPAY_INR_YEARLY_PLAN},GLOBAL:{monthly:env.RAZORPAY_USD_MONTHLY_PLAN,yearly:env.RAZORPAY_USD_YEARLY_PLAN}}});
 if(env.GOOGLE_PLAY_CREDENTIALS)providers.play=new GooglePlayProvider({credentials:env.GOOGLE_PLAY_CREDENTIALS,packageName:env.GOOGLE_PLAY_PACKAGE||'com.sidedeck.notes',product:env.GOOGLE_PLAY_PRODUCT||'sidenote_pro',monthly:env.GOOGLE_PLAY_MONTHLY_PLAN||'monthly',yearly:env.GOOGLE_PLAY_YEARLY_PLAN||'yearly'});
 if(env.LICENSE_SIGNING_KEY_FILE&&env.LICENSE_MASTER_KEY){const signingKey=readFileSync(env.LICENSE_SIGNING_KEY_FILE,'utf8');publicKeyRaw(signingKey);store=new Entitlements({database:env.DATABASE_PATH||'entitlements.sqlite3',masterKey:env.LICENSE_MASTER_KEY,signingKey,providers})}
 const app=createApp({store,webProvider:env.WEB_PAYMENT_PROVIDER||'razorpay',publicUrl:env.PUBLIC_URL,stripeWebhook:env.STRIPE_WEBHOOK_SECRET,razorpayWebhook:env.RAZORPAY_WEBHOOK_SECRET,googleAudience:env.GOOGLE_PUSH_AUDIENCE,googleEmail:env.GOOGLE_PUSH_EMAIL});app.listen(Number(env.PORT)||38880,env.HOST||'127.0.0.1',()=>console.log('SideNote entitlement service started; '+(store?'verification configured':'purchases disabled until configured')));
 const stop=()=>app.close(()=>{store?.close();process.exit(0)});process.on('SIGTERM',stop);process.on('SIGINT',stop)
}

const status=document.querySelector('#status');
const message=text=>{if(status)status.textContent=text};
async function api(path,body){const response=await fetch('/v1/'+path,{method:body?'POST':'GET',headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined});const value=await response.json();if(!response.ok)throw new Error(value.error||'Please try again');return value}
const randomCode=()=>Array.from(crypto.getRandomValues(new Uint8Array(32)),n=>n.toString(16).padStart(2,'0')).join('');
let checkoutBusy=false;
async function buy(plan,billingRegion){
 if(checkoutBusy)return;
 if(document.querySelector('#billing-region')?.value!==billingRegion){message('Your billing region changed. Choose a plan from the updated prices.');return}
 checkoutBusy=true;document.querySelectorAll('#plan-cards button').forEach(button=>button.disabled=true);
 try{message('Opening secure checkout...');const claimSecret=randomCode();const checkout=await api('web/checkout',{plan,claimSecret,billingRegion});sessionStorage.setItem('sidenote-checkout-'+checkout.id,claimSecret);if(checkout.provider==='razorpay'){location.assign('/success.html?checkout='+encodeURIComponent(checkout.id)+'&pay='+encodeURIComponent(checkout.url))}else location.assign(checkout.url)}catch(e){message(e.message);checkoutBusy=false;document.querySelectorAll('#plan-cards button').forEach(button=>button.disabled=false)}
}
if(document.querySelector('#plan-cards')){
 const root=document.querySelector('#plan-cards'),region=document.querySelector('#billing-region');let requestVersion=0;
 let preferred='GLOBAL';try{const saved=localStorage.getItem('sidenote-billing-region');preferred=['IN','GLOBAL'].includes(saved)?saved:new Intl.Locale(navigator.language).region==='IN'?'IN':'GLOBAL'}catch{}
 region.value=preferred;
 async function loadPlans(){
  const version=++requestVersion,selected=region.value;root.textContent='Checking '+(selected==='IN'?'INR':'USD')+' plans...';
  try{localStorage.setItem('sidenote-billing-region',selected)}catch{}
  try{const {products}=await api('products?region='+encodeURIComponent(selected));if(version!==requestVersion)return;
   if(!products.length)throw new Error('No plans are available for this region yet.');
   root.replaceChildren();for(const product of products){
    const currency=selected==='IN'?'inr':'usd';if(product.currency!==currency||product.billingRegion!==selected)throw new Error('Prices could not be verified for this billing region.');
    const card=document.createElement('article');card.className='plan';const name=document.createElement('h3');name.textContent=product.plan==='yearly'?'Yearly Windows Pro':'Monthly Windows Pro';const price=document.createElement('p');price.className='price';price.textContent=new Intl.NumberFormat(undefined,{style:'currency',currency:product.currency,currencyDisplay:'code'}).format(product.amount/100)+' / '+(product.plan==='yearly'?'year':'month');const button=document.createElement('button');button.className='primary';button.textContent='Choose '+product.plan;button.disabled=checkoutBusy;button.onclick=()=>buy(product.plan,product.billingRegion);card.append(name,price,button);root.append(card)
   }
  }catch(e){if(version!==requestVersion)return;root.textContent='Plans are not available for this billing region yet. No payment has been initiated.';message(e.message)}
 }
 region.onchange=()=>{message('');void loadPlans()};void loadPlans()
}
if(document.querySelector('#activation')){
 const query=new URL(location.href).searchParams;const id=query.get('checkout');const pay=query.get('pay');
 if(pay){try{const url=new URL(pay);if(url.protocol==='https:'&&['rzp.io','rzp.io.in','razorpay.com','pages.razorpay.com'].includes(url.hostname)){const link=document.createElement('a');link.className='primary';link.href=url.href;link.target='_blank';link.rel='noopener noreferrer';link.textContent='Open Razorpay checkout';status.before(link);message('Complete payment in Razorpay, then return here and choose Verify payment.')}}catch{}}

 const claim=async()=>{try{const claimSecret=sessionStorage.getItem('sidenote-checkout-'+id);if(!id||!claimSecret)throw new Error('Open this page in the same browser tab used for checkout. Keep your payment receipt and contact support if you need help.');let deviceId=localStorage.getItem('sidenote-license-device');if(!deviceId){deviceId='web-'+crypto.randomUUID();localStorage.setItem('sidenote-license-device',deviceId)}const result=await api('web/claim',{id,claimSecret,deviceId});document.querySelector('#activation').hidden=false;document.querySelector('#activation-code').textContent=result.activationCode;document.querySelector('#copy-code').onclick=()=>navigator.clipboard.writeText(result.activationCode).then(()=>message('Copied. Save your code privately.')).catch(()=>message('Select and copy the code above.'));message('Payment verified. Your Pro access is ready.');document.querySelector('#retry-claim').hidden=true}catch(e){message(e.message);document.querySelector('#retry-claim').hidden=false}};
 document.querySelector('#retry-claim').onclick=claim;void claim()
}

const manageForm=document.querySelector('#manage-form');
if(manageForm){
 const input=document.querySelector('#manage-code'),list=document.querySelector('#device-list'),cancel=document.querySelector('#cancel-subscription');
 const key=()=>input.value.trim().toLowerCase();
 async function devices(){const value=await api('devices',{activationCode:key()});list.replaceChildren();for(const device of value.devices){const row=document.createElement('article'),name=document.createElement('p'),remove=document.createElement('button');row.className='plan';name.textContent=device.deviceId+' - last verified '+new Date(device.lastSeen*1000).toLocaleString();remove.textContent='Remove device';remove.className='secondary';remove.onclick=async()=>{if(!confirm('Remove access from this device? Existing offline access can last up to 72 hours.'))return;try{await api('devices/remove',{activationCode:key(),deviceId:device.deviceId});await devices();message('Device removed.')}catch(e){message(e.message)}};row.append(name,remove);list.append(row)}cancel.hidden=false}
 manageForm.onsubmit=async e=>{e.preventDefault();try{await devices();message('Devices loaded. Keep your activation code private.')}catch(e){message(e.message)}};
 cancel.onclick=async()=>{if(!confirm('Cancel renewal at the end of your paid period? Your existing notes remain accessible.'))return;cancel.disabled=true;try{const result=await api('cancel',{activationCode:key()});message(result.message)}catch(e){message(e.message)}finally{cancel.disabled=false}}
}

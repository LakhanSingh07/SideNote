package com.sidedeck.notes
import android.app.Activity
import android.os.Bundle
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.*
import com.android.billingclient.api.*

/** Google Play owns checkout; access is granted only after backend verification. */
class ProActivity:Activity(),PurchasesUpdatedListener {
    private lateinit var billing:BillingClient
    private lateinit var status:TextView
    private lateinit var plans:LinearLayout
    override fun onCreate(state:Bundle?){super.onCreate(state);Ui.configure(this)
        if(BuildConfig.ANDROID_FREE_BETA){
            val beta=Ui.column(this).apply {setPadding(48,48,48,48);setBackgroundColor(Ui.surface);fitsSystemWindows=true}
            beta.addView(Ui.outline(this,"Back"){finish()});beta.addView(Ui.space(this,24));beta.addView(Ui.editorial(this,"Your little space.\nEarly access.",30f));beta.addView(Ui.space(this,16))
            beta.addView(Ui.text(this,"Android beta is free",20f,true));beta.addView(Ui.space(this,12));beta.addView(Ui.text(this,"Use notes, checklists and attached files while helping us improve SideNote. No Android purchase is required during early access.",15f,false,Ui.muted));beta.addView(Ui.space(this,20))
            beta.addView(Ui.text(this,"Connect your Windows PC",18f,true));beta.addView(Ui.space(this,8));beta.addView(Ui.text(this,"Windows includes a 7-day trial of all features. After the trial, sync and file transfer require active Windows Pro. Your existing local notes and export stay accessible.",14f,false,Ui.muted));beta.addView(Ui.space(this,20));beta.addView(Ui.button(this,"Manage connected PCs"){startActivity(Intent(this,PairingActivity::class.java))})
            setContentView(ScrollView(this).apply {addView(beta)});return
        }

        val root=Ui.column(this).apply {setPadding(48,48,48,48);setBackgroundColor(Ui.surface);fitsSystemWindows=true}
        root.addView(Ui.outline(this,"Back"){finish()});root.addView(Ui.space(this,24));root.addView(Ui.editorial(this,"A little more.\nTogether.",30f));root.addView(Ui.space(this,12))
        root.addView(Ui.text(this,"SideNote Pro",19f,true));root.addView(Ui.space(this,8));root.addView(Ui.text(this,"Secure Android + Windows sync, files that travel with your notes, and saved PCs. One subscription for both apps.",14f,false,Ui.muted));root.addView(Ui.space(this,20))
        status=Ui.text(this,ProAccess.status(this).message,13f,false,Ui.muted).apply {accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE};root.addView(status)
        plans=Ui.column(this);root.addView(plans);root.addView(Ui.space(this,20))
        root.addView(Ui.outline(this,"Restore Google Play purchase"){if(::billing.isInitialized&&billing.isReady)restore()else message("Google Play is not connected yet. Please try again.")});root.addView(Ui.space(this,10))
        root.addView(Ui.outline(this,"Manage Google Play subscription"){startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/account/subscriptions?sku=${BuildConfig.PLAY_PRODUCT}&package=$packageName")))})
        root.addView(Ui.space(this,10));root.addView(Ui.outline(this,"Activate an existing Pro code"){
            val input=EditText(this).apply {hint="Your private activation code";setSingleLine();inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;setTextColor(Ui.ink)}
            AlertDialog.Builder(this).setTitle("Link your existing subscription").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Activate"){_,_->message("Verifying securely...");ProAccess.activate(this,input.text.toString()){_,text->message(text)}}.show()
        });root.addView(Ui.space(this,10));root.addView(Ui.outline(this,"Refresh Pro access"){message("Checking subscription...");ProAccess.activate(this,null){_,text->message(text)}})
        root.addView(Ui.space(this,10));root.addView(Ui.outline(this,"Copy code for my Windows PC"){
            runCatching{val code=ProAccess.activationCode(this);(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Private SideNote activation code",code));message("Copied. Paste it into Windows SideNote > Pro. Keep it private.")}.onFailure {message(it.message ?: "Restore your purchase first")}
        });root.addView(Ui.space(this,20))
        root.addView(Ui.text(this,"Subscriptions renew until cancelled. Google Play shows the price and billing period before you confirm. Cancel in Google Play. If Pro ends, local notes and data export remain available. Verified offline access lasts up to 72 hours, within your paid access period.",12f,false,Ui.muted))
        setContentView(ScrollView(this).apply {addView(root)})
        if(!ProAccess.configured()||!BuildConfig.COMMERCIAL_BUILD){plans.addView(Ui.text(this,"Monthly and yearly purchases will be available in the configured store release.",14f));return}
        billing=BillingClient.newBuilder(this).setListener(this).enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).enableAutoServiceReconnection().build()
        billing.startConnection(object:BillingClientStateListener{override fun onBillingServiceDisconnected(){message("Google Play disconnected. Try again when connected.")};override fun onBillingSetupFinished(result:BillingResult){if(result.responseCode==BillingClient.BillingResponseCode.OK){loadProducts();restore(false)}else message("Google Play is unavailable. Your local notes stay accessible.")}})
    }
    private fun message(text:String){if(!isDestroyed)status.text=text}
    private fun loadProducts(){
        val query=QueryProductDetailsParams.newBuilder().setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(BuildConfig.PLAY_PRODUCT).setProductType(BillingClient.ProductType.SUBS).build())).build()
        billing.queryProductDetailsAsync(query){result,value->runOnUiThread {
            if(isDestroyed)return@runOnUiThread;plans.removeAllViews()
            if(result.responseCode!=BillingClient.BillingResponseCode.OK){message("Could not load plans. No purchase was started.");return@runOnUiThread}
            val product=value.productDetailsList.firstOrNull() ?: run{message("Plans are not available for this account yet.");return@runOnUiThread}
            for((id,label,period) in listOf(Triple(BuildConfig.PLAY_MONTHLY_PLAN,"Monthly","P1M"),Triple(BuildConfig.PLAY_YEARLY_PLAN,"Yearly","P1Y"))){
                val offer=product.subscriptionOfferDetails?.firstOrNull {it.basePlanId==id&&it.offerId==null&&it.pricingPhases.pricingPhaseList.lastOrNull()?.billingPeriod==period} ?: continue
                val price=offer.pricingPhases.pricingPhaseList.last().formattedPrice
                plans.addView(Ui.space(this,12));plans.addView(Ui.button(this,"$label Pro - $price / ${if(period=="P1M")"month" else "year"}"){
                    if(ProAccess.status(this).active&&!ProAccess.preview()){message("Pro is already active. Manage your existing subscription to avoid duplicate billing.");return@button}
                    val params=BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offer.offerToken).build())).build()
                    val response=billing.launchBillingFlow(this,params);if(response.responseCode!=BillingClient.BillingResponseCode.OK)message("Could not open checkout. No purchase was started.")
                })
            }
        }}
    }
    private fun restore(showEmpty:Boolean=true){billing.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()){result,purchases->
        if(result.responseCode==BillingClient.BillingResponseCode.OK){val owned=purchases.filter {BuildConfig.PLAY_PRODUCT in it.products};if(owned.isEmpty()&&showEmpty)runOnUiThread{message("No active Play purchase was found on this Google account.")};owned.forEach(::process)}else runOnUiThread{message("Could not check purchases. Please try Restore again.")}
    }}
    private fun process(purchase:Purchase){
        if(purchase.purchaseState==Purchase.PurchaseState.PENDING){runOnUiThread{message("Payment is pending. Pro will unlock only after payment and verification finish.")};return}
        if(purchase.purchaseState!=Purchase.PurchaseState.PURCHASED)return
        runOnUiThread{message("Verifying your purchase securely...")}
        ProAccess.verifyPlay(this,purchase.purchaseToken){_,text->message(text)}
    }
    override fun onPurchasesUpdated(result:BillingResult,purchases:MutableList<Purchase>?){
        when(result.responseCode){BillingClient.BillingResponseCode.OK->purchases?.filter {BuildConfig.PLAY_PRODUCT in it.products}?.forEach(::process);BillingClient.BillingResponseCode.USER_CANCELED->message("Purchase cancelled. Your free notes are ready whenever you are.");BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED->restore();else->message("Purchase could not be completed. Try Restore Purchase if you were charged.")}
    }
    override fun onDestroy(){if(::billing.isInitialized)billing.endConnection();super.onDestroy()}
}

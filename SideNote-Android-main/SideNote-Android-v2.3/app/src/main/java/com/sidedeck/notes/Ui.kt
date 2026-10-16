package com.sidedeck.notes

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** SideNote's single palette and component foundation, shared by Activity + overlay service. */
object Ui {
    var dark: Boolean = false
        private set
    fun configure(c: Context) {
        val mode = c.getSharedPreferences("overlay_ui", Context.MODE_PRIVATE).getString("theme_mode", "System")
        dark = when(mode) {
            "Dark" -> true
            "Light" -> false
            else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }
    fun motionDuration(c:Context,millis:Long):Long = if(c.getSharedPreferences("overlay_ui",0).getBoolean("reduce_motion",false)||!android.animation.ValueAnimator.areAnimatorsEnabled())0L else millis
    val ink: Int get() = if (dark) Color.rgb(244,243,238) else Color.rgb(32,36,38)
    val muted: Int get() = if (dark) Color.rgb(174,177,181) else Color.rgb(119,123,125)
    val surface: Int get() = if (dark) Color.rgb(20,23,26) else Color.rgb(250,248,240)
    val card: Int get() = if (dark) Color.rgb(35,38,43) else Color.WHITE
    val field: Int get() = if (dark) Color.rgb(44,47,52) else Color.rgb(245,243,235)
    // Royal Purple matching app icon brand identity
    val violet: Int get() = if (dark) Color.rgb(160, 105, 255) else Color.rgb(138, 75, 255)
    val onAccent: Int get() = Color.WHITE
    val accentSoft: Int get() = if (dark) Color.argb(60, 140, 90, 255) else Color.argb(35, 138, 75, 255)
    val positive: Int get() = if (dark) Color.rgb(153,221,178) else Color.rgb(48,119,81)
    val border: Int get() = if (dark) Color.rgb(55,59,64) else Color.rgb(236,235,228)
    val subtle: Int get() = if (dark) Color.rgb(44,47,52) else Color.rgb(247,246,239)
    val colorNames = arrayOf("Soft Yellow", "Warm Peach", "Blush Pink", "Mint Green", "Sky Blue", "Lavender")
    val colors: IntArray get() = if (dark) intArrayOf(
        0xFF4E4624.toInt(), // Soft Yellow (Dark)
        0xFF543825.toInt(), // Warm Peach (Dark)
        0xFF522E3A.toInt(), // Blush Pink (Dark)
        0xFF254734.toInt(), // Mint Green (Dark)
        0xFF243B52.toInt(), // Sky Blue (Dark)
        0xFF3B2F57.toInt()  // Lavender (Dark)
    ) else intArrayOf(
        0xFFFFF6B8.toInt(), // Soft Yellow (Canary)
        0xFFFFDFC7.toInt(), // Warm Peach
        0xFFFFD4DE.toInt(), // Blush Pink
        0xFFD4F5DE.toInt(), // Mint Green
        0xFFD6EBFC.toInt(), // Sky Blue
        0xFFEBE0FD.toInt()  // Lavender
    )
    val noteBorders: IntArray get() = if (dark) intArrayOf(
        0x4DFFE76A.toInt(),
        0x4DFFBA8C.toInt(),
        0x4DFFA8BD.toInt(),
        0x4DA2F2BA.toInt(),
        0x4DA8D5FF.toInt(),
        0x4DCDB8FF.toInt()
    ) else intArrayOf(
        0x2B7A6A00.toInt(),
        0x2B994A1F.toInt(),
        0x2B991F41.toInt(),
        0x2B1F703B.toInt(),
        0x2B1A548A.toInt(),
        0x2B4B278A.toInt()
    )
    fun dp(c: Context, n: Int): Int = (n * c.resources.displayMetrics.density + .5f).toInt()
    fun bg(color: Int, radius: Float = 18f, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius=radius; stroke?.let { setStroke(dpWidth(), it) }
    }
    private fun dpWidth() = 1
    fun text(c: Context, value: String, sp: Float = 16f, bold: Boolean = false, color: Int = ink): TextView = TextView(c).apply {
        text=value; textSize=sp; setTextColor(color)
        typeface=Typeface.create(if(bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false; setLineSpacing(dp(c,2).toFloat(),1f)
    }
    fun editorial(c: Context, value: String, size: Float = 36f): TextView = text(c,value,size,false).apply {
        typeface=Typeface.create("sans-serif-light",Typeface.NORMAL)
        letterSpacing=-.035f
    }
    fun secondary(c: Context, value: String, action: () -> Unit): TextView = text(c,value,13f,true,ink).apply {
        gravity=Gravity.CENTER;setPadding(dp(c,12),dp(c,9),dp(c,12),dp(c,9))
        background=bg(accentSoft,dp(c,14).toFloat());minHeight=dp(c,48)
        isClickable=true;isFocusable=true;contentDescription=value;setOnClickListener{action()}
    }
    fun button(c: Context, value: String, action: () -> Unit): TextView = text(c,value,14f,true,onAccent).apply {
        gravity=Gravity.CENTER;setPadding(dp(c,16),dp(c,11),dp(c,16),dp(c,11))
        background=bg(violet,dp(c,16).toFloat());minHeight=dp(c,48)
        isClickable=true;isFocusable=true;contentDescription=value;setOnClickListener{action()}
    }
    fun outline(c: Context, value: String, action: () -> Unit): TextView = text(c,value,14f,true,ink).apply {
        gravity=Gravity.CENTER;setPadding(dp(c,14),dp(c,11),dp(c,14),dp(c,11))
        background=bg(card,dp(c,16).toFloat(),border);minHeight=dp(c,48)
        isClickable=true;isFocusable=true;contentDescription=value;setOnClickListener{action()}
    }
    fun surfaceCard(c: Context, color: Int=card): LinearLayout = column(c).apply {
        background=bg(color,dp(c,20).toFloat());setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16))
    }
    fun touchTarget(c: Context, view: View) = view.apply {minimumHeight=dp(c,48);minimumWidth=dp(c,48)}
    fun chip(c: Context, label: String, selected: Boolean, action: () -> Unit): TextView = text(c,label,14f,selected,if(selected)ink else muted).apply {
        gravity=Gravity.CENTER;minHeight=dp(c,48)
        setPadding(dp(c,10),dp(c,11),dp(c,10),dp(c,11))
        isClickable=true;isFocusable=true;isSelected=selected
        contentDescription=label + if(selected)", selected" else ", not selected"
        setOnClickListener{action()}
    }
    fun icon(c: Context, resId: Int, sizeDp: Int = 24, tint: Int? = null): ImageView = ImageView(c).apply {
        setImageResource(resId)
        tint?.let { setColorFilter(it) }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    fun emptyState(c: Context, title: String, detail: String): LinearLayout = surfaceCard(c).apply {
        gravity=Gravity.CENTER
        val ic = icon(c, R.drawable.ic_nav_notes, 36, violet)
        addView(ic, LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)).apply { gravity = Gravity.CENTER })
        addView(space(c,12))
        addView(text(c,title,18f,true).apply{gravity=Gravity.CENTER})
        addView(space(c,6))
        addView(text(c,detail,13f,false,muted).apply{gravity=Gravity.CENTER;textAlignment=View.TEXT_ALIGNMENT_CENTER})
        minimumHeight=dp(c,174)
    }
    fun divider(c: Context) = View(c).apply {setBackgroundColor(border)}
    fun section(c: Context,title: String,subtitle:String)=column(c).apply {
        addView(text(c,title,20f,true));addView(space(c,5));addView(text(c,subtitle,13f,false,muted))
    }
    fun column(c: Context)=LinearLayout(c).apply{orientation=LinearLayout.VERTICAL}
    fun row(c: Context)=LinearLayout(c).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    fun space(c: Context, height: Int)=View(c).apply{layoutParams=LinearLayout.LayoutParams(1,dp(c,height))}
    fun pad(v: View,c: Context,all:Int)=v.apply{setPadding(dp(c,all),dp(c,all),dp(c,all),dp(c,all))}
    fun lp(c: Context,w:Int=-1,h:Int=-2) = LinearLayout.LayoutParams(if(w<0) w else dp(c,w),if(h<0) h else dp(c,h))

    data class ThemedMenuItem(
        val icon: String = "",
        val label: String,
        val isDestructive: Boolean = false,
        val iconRes: Int = 0,
        val action: () -> Unit
    )

    fun strikeThrough(tv: TextView, strike: Boolean) {
        tv.paintFlags = if (strike) tv.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        else tv.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }

    fun showThemedMenu(anchor: View, items: List<ThemedMenuItem>) {
        val c = anchor.context
        val root = column(c).apply {
            background = bg(card, dp(c, 16).toFloat(), stroke = border)
            elevation = dp(c, 10).toFloat()
            setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6))
        }
        val popup = android.widget.PopupWindow(root, -2, -2, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(c, 10).toFloat()
        }
        items.forEach { item ->
            val row = row(c).apply {
                minimumHeight = dp(c, 44)
                setPadding(dp(c, 14), dp(c, 8), dp(c, 18), dp(c, 8))
                background = bg(Color.TRANSPARENT, dp(c, 10).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    item.action()
                }
            }
            val color = if (item.isDestructive) Color.rgb(235, 75, 75) else ink
            if (item.iconRes != 0) {
                row.addView(icon(c, item.iconRes, 18, color))
                row.addView(View(c), LinearLayout.LayoutParams(dp(c, 10), 1))
            } else if (item.icon.isNotBlank()) {
                row.addView(text(c, item.icon, 15f, true, color).apply {
                    minWidth = dp(c, 24)
                    gravity = Gravity.CENTER
                })
                row.addView(View(c), LinearLayout.LayoutParams(dp(c, 10), 1))
            }
            row.addView(text(c, item.label, 14f, false, color))
            root.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        popup.showAsDropDown(anchor, 0, dp(c, 2))
    }
}

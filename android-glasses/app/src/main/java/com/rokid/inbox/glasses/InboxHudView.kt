package com.rokid.inbox.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Amber-monospace-on-black HUD for the Rokid see-through display, following the
 * amber-on-black visual style. Renders three page kinds: a windowed list, a full
 * text block, and a text block with a small option list (the reply preview).
 */
class InboxHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val header: TextView
    private val body: LinearLayout
    private val hint: TextView
    private var detailScroll: ScrollView? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        setPadding(px(14), px(10), px(14), px(8))

        header = mono(13f, COLOR_DIM).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        body = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        hint = mono(11f, COLOR_DIM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(6)
            }
        }
        addView(header)
        addView(body)
        addView(hint)
    }

    fun renderList(headerText: String, rows: List<String>, selected: Int, hintText: String) {
        header.text = headerText
        hint.text = hintText
        body.gravity = Gravity.TOP
        body.removeAllViews()
        if (rows.isEmpty()) {
            body.addView(mono(16f, COLOR_SECONDARY).apply {
                text = "(vazio)"
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
            })
            return
        }
        rows.forEachIndexed { i, rowText ->
            val isSel = i == selected
            body.addView(mono(if (isSel) 17f else 15f, if (isSel) COLOR_PRIMARY else COLOR_SECONDARY).apply {
                text = rowText
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (isSel) {
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setBackgroundColor(COLOR_SELECTED_BG)
                }
                setPadding(px(6), px(6), px(6), px(6))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = px(3)
                }
            })
        }
    }

    /** A chat-list row with an optional leading brand icon (0 = no icon). */
    class Row(val iconRes: Int, val text: String)

    /** Header + windowed list where each row may carry a leading channel logo. */
    fun renderChatList(headerText: String, rows: List<Row>, selected: Int, hintText: String) {
        header.text = headerText
        hint.text = hintText
        body.gravity = Gravity.TOP
        detailScroll = null
        body.removeAllViews()
        if (rows.isEmpty()) {
            body.addView(mono(16f, COLOR_SECONDARY).apply {
                text = "(vazio)"
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
            })
            return
        }
        val sel = selected.coerceIn(0, rows.size - 1)
        rows.forEachIndexed { i, row ->
            val isSel = i == sel
            val color = if (isSel) COLOR_PRIMARY else COLOR_SECONDARY
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (isSel) setBackgroundColor(COLOR_SELECTED_BG)
                setPadding(px(6), px(6), px(6), px(6))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(3) }
            }
            if (row.iconRes != 0) {
                rowView.addView(ImageView(context).apply {
                    setImageResource(row.iconRes)
                    setColorFilter(color, PorterDuff.Mode.SRC_IN)
                    val s = px(if (isSel) 20 else 17)
                    layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = px(8) }
                })
            }
            rowView.addView(mono(if (isSel) 17f else 15f, color).apply {
                text = row.text
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (isSel) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
            body.addView(rowView)
        }
    }

    /**
     * Conversation view: fills the screen with full messages (already capped and
     * windowed by the caller), the selected one highlighted, oldest at top.
     */
    fun renderConversation(
        headerText: String,
        texts: List<String>,
        selectedInWindow: Int,
        olderAbove: Boolean,
        newerBelow: Boolean,
        hintText: String,
    ) {
        header.text = headerText
        hint.text = hintText
        body.gravity = Gravity.CENTER_VERTICAL
        detailScroll = null
        body.removeAllViews()
        if (texts.isEmpty()) {
            body.addView(mono(15f, COLOR_SECONDARY).apply {
                text = "(sem mensagens)"
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
            })
            return
        }
        if (olderAbove) body.addView(mono(11f, COLOR_DIM).apply { text = "\u2191 mais antigas" })
        texts.forEachIndexed { i, t ->
            val isSel = i == selectedInWindow
            body.addView(mono(if (isSel) 15f else 14f, if (isSel) COLOR_PRIMARY else COLOR_SECONDARY).apply {
                text = t
                maxLines = 9
                ellipsize = TextUtils.TruncateAt.END
                if (isSel) {
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setBackgroundColor(COLOR_SELECTED_BG)
                }
                setPadding(px(6), px(5), px(6), px(5))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(3) }
            })
        }
        if (newerBelow) body.addView(mono(11f, COLOR_DIM).apply { text = "\u2193 mais recentes" })
    }

    /** Expanded single-message reader: full text in a scrollable view. */
    fun renderDetail(headerText: String, fullText: String, hintText: String) {
        header.text = headerText
        hint.text = hintText
        body.gravity = Gravity.TOP
        body.removeAllViews()
        val text = mono(15f, COLOR_PRIMARY).apply {
            this.text = fullText
            setPadding(px(6), px(4), px(6), px(4))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            addView(text)
        }
        detailScroll = scroll
        body.addView(scroll)
    }

    /** Scroll the open detail message by ~80% of its height (dir: +1 down, -1 up). */
    fun scrollDetailBy(dir: Int) {
        val scroll = detailScroll ?: return
        val amount = (scroll.height * 0.8f).toInt().coerceAtLeast(px(40))
        scroll.smoothScrollBy(0, dir * amount)
    }

    /** Full-screen image view for a photo message, with an optional caption. */
    fun renderImage(headerText: String, bitmap: Bitmap, caption: String, hintText: String) {
        header.text = headerText
        hint.text = hintText
        body.gravity = Gravity.CENTER
        body.removeAllViews()
        detailScroll = null
        body.addView(ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        })
        if (caption.isNotBlank()) {
            body.addView(mono(13f, COLOR_SECONDARY).apply {
                text = caption
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) }
            })
        }
    }

    fun renderText(headerText: String, bodyText: String, hintText: String, big: Boolean = false) {
        detailScroll = null
        body.gravity = Gravity.TOP
        header.text = headerText
        hint.text = hintText
        body.removeAllViews()
        body.addView(mono(if (big) 22f else 15f, COLOR_PRIMARY).apply {
            text = bodyText
            if (big) {
                gravity = Gravity.CENTER
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            }
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(6)
            }
        })
    }

    fun renderOptions(
        headerText: String,
        bodyText: String,
        options: List<String>,
        selected: Int,
        hintText: String,
    ) {
        header.text = headerText
        hint.text = hintText
        body.gravity = Gravity.TOP
        body.removeAllViews()
        body.addView(mono(15f, COLOR_PRIMARY).apply {
            text = bodyText
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6) }
        })
        options.forEachIndexed { i, opt ->
            val isSel = i == selected
            body.addView(mono(15f, if (isSel) COLOR_PRIMARY else COLOR_SECONDARY).apply {
                text = opt
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (isSel) {
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setBackgroundColor(COLOR_SELECTED_BG)
                }
                setPadding(px(6), px(6), px(6), px(6))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = px(3)
                }
            })
        }
    }

    private fun mono(sizeSp: Float, color: Int) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        includeFontPadding = false
        setShadowLayer(6f * resources.displayMetrics.density, 0f, 0f, COLOR_SHADOW)
    }

    private fun px(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        private val COLOR_PRIMARY = Color.parseColor("#FFE7A3")
        private val COLOR_SECONDARY = Color.parseColor("#D5BB7A")
        private val COLOR_DIM = Color.parseColor("#A48B59")
        private val COLOR_SELECTED_BG = Color.parseColor("#2A2210")
        private val COLOR_SHADOW = Color.parseColor("#CC000000")
    }
}

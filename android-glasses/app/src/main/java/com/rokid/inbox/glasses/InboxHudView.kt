package com.rokid.inbox.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
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
        detailScroll = null
        body.removeAllViews()
        if (rows.isEmpty()) {
            body.addView(mono(16f, COLOR_SECONDARY).apply {
                text = context.getString(R.string.hud_empty)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
            })
            return
        }
        val sel = selected.coerceIn(0, rows.size - 1)
        val range = windowRange(rows.size, sel, ROW_HEIGHT_1LINE_DP)
        if (range.first > 0) body.addView(chevron("\u2191 +${range.first}"))
        for (i in range) {
            val isSel = i == sel
            body.addView(mono(if (isSel) 17f else 15f, if (isSel) COLOR_PRIMARY else COLOR_SECONDARY).apply {
                text = rows[i]
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
        if (range.last < rows.size - 1) body.addView(chevron("\u2193 +${rows.size - 1 - range.last}"))
    }

    /** A chat-list row with an optional leading brand icon (0 = no icon) and an
     *  optional second line (e.g. "HH:mm | last message preview"). */
    class Row(val iconRes: Int, val text: String, val subtitle: String = "")

    /** Header + windowed list where each row may carry a leading channel logo.
     *  Only the rows around the selection are drawn (with ↑/↓ counters), so long
     *  inboxes stay reachable without a flickering full-list rebuild + scroll. */
    fun renderChatList(headerText: String, rows: List<Row>, selected: Int, hintText: String) {
        header.text = headerText
        hint.text = hintText
        body.gravity = Gravity.TOP
        detailScroll = null
        body.removeAllViews()
        if (rows.isEmpty()) {
            body.addView(mono(16f, COLOR_SECONDARY).apply {
                text = context.getString(R.string.hud_empty)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
            })
            return
        }
        val sel = selected.coerceIn(0, rows.size - 1)
        val twoLine = rows.any { it.subtitle.isNotBlank() }
        val range = windowRange(rows.size, sel, if (twoLine) ROW_HEIGHT_2LINE_DP else ROW_HEIGHT_1LINE_DP)
        if (range.first > 0) body.addView(chevron("\u2191 +${range.first}"))
        for (i in range) {
            val row = rows[i]
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
            val textCol = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(mono(if (isSel) 17f else 15f, color).apply {
                text = row.text
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (isSel) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
            if (row.subtitle.isNotBlank()) {
                textCol.addView(mono(12f, COLOR_DIM).apply {
                    text = row.subtitle
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            rowView.addView(textCol)
            body.addView(rowView)
        }
        if (range.last < rows.size - 1) body.addView(chevron("\u2193 +${rows.size - 1 - range.last}"))
    }

    /** A dim single-line "N more above/below" counter row. */
    private fun chevron(text: String) = mono(11f, COLOR_DIM).apply {
        this.text = text
        setPadding(px(6), px(2), px(6), px(2))
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    /**
     * Window of row indices to draw around [selected], sized to the body height so
     * the selection is always visible. Reserves two lines for the ↑/↓ counters.
     */
    private fun windowRange(total: Int, selected: Int, rowHeightDp: Int): IntRange {
        val fit = visibleRowCount(rowHeightDp)
        if (total <= fit) return 0 until total
        val maxRows = (fit - 2).coerceAtLeast(3)
        var start = selected - maxRows / 2
        if (start < 0) start = 0
        var end = start + maxRows - 1
        if (end > total - 1) {
            end = total - 1
            start = (end - maxRows + 1).coerceAtLeast(0)
        }
        return start..end
    }

    /** How many rows of the given height fit in the body now (fallback before layout). */
    private fun visibleRowCount(rowHeightDp: Int): Int {
        val h = if (body.height > 0) body.height else body.measuredHeight
        if (h <= 0) return DEFAULT_VISIBLE_ROWS
        return (h / px(rowHeightDp)).coerceIn(3, 24)
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
                text = context.getString(R.string.hud_no_messages)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
            })
            return
        }
        if (olderAbove) body.addView(mono(11f, COLOR_DIM).apply { text = context.getString(R.string.hud_older) })
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
        if (newerBelow) body.addView(mono(11f, COLOR_DIM).apply { text = context.getString(R.string.hud_newer) })
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
        private const val DEFAULT_VISIBLE_ROWS = 5
        private const val ROW_HEIGHT_1LINE_DP = 34
        private const val ROW_HEIGHT_2LINE_DP = 52
        private val COLOR_PRIMARY = Color.parseColor("#FFE7A3")
        private val COLOR_SECONDARY = Color.parseColor("#D5BB7A")
        private val COLOR_DIM = Color.parseColor("#A48B59")
        private val COLOR_SELECTED_BG = Color.parseColor("#2A2210")
        private val COLOR_SHADOW = Color.parseColor("#CC000000")
    }
}

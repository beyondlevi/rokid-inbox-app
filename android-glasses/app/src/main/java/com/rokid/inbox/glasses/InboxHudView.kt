package com.rokid.inbox.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Amber-monospace-on-black HUD for the Rokid see-through display, redesigned to
 * use the full field of view. Lists and the conversation reader render every
 * row into a full-height [PassiveScrollView] and keep the selected row visible
 * with a programmatic, clamped scroll (instead of drawing a small windowed
 * subset that left the lower part of the canvas empty). This also lets the R08
 * Access Bridge page long content via the scrollable's accessibility scroll
 * actions.
 *
 * Renders: a windowed list, a chat list with leading logos, a conversation
 * feed, an expanded scrollable reader, a full-screen image, and status/option
 * screens.
 */
class InboxHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val header: TextView
    private val body: LinearLayout
    private val hint: TextView

    // The scroll container currently mounted in [body] (list / conversation /
    // detail), plus the selected row so we can keep it in view after layout.
    private var activeScroll: ScrollView? = null
    private var selectedView: View? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        // Focus discipline for the see-through display: the framework paints a
        // default focus highlight over whichever view holds focus, which shows
        // up as a bright opaque wash on this display. Block focus for every
        // descendant and let the HUD root absorb focus with the highlight off,
        // so it never falls back to the decor view either.
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
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

    /**
     * A ScrollView that never touches the screen pipeline in ways that break the
     * see-through display: it ignores touch events entirely (gestures are owned
     * by the Activity; scrolling is programmatic via clamped scroll) and disables
     * the overscroll edge effect and focus highlight, both of which render as a
     * bright opaque wash on the transparent HUD.
     */
    private class PassiveScrollView(context: Context) : ScrollView(context) {
        init {
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            setBackgroundColor(Color.BLACK)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean = false

        // Refuse accessibility scroll actions: the R08 bridge would otherwise
        // page-scroll this container directly, bypassing the Activity's
        // selection model (the selection would sit still while the page moved).
        // All scrolling here is programmatic (keep-selection-visible).
        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
            if (action == android.R.id.accessibilityActionScrollDown ||
                action == android.R.id.accessibilityActionScrollUp ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ) {
                return false
            }
            return super.performAccessibilityAction(action, arguments)
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.isScrollable = false
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    private fun passiveScroll(content: View): ScrollView = PassiveScrollView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        content.setBackgroundColor(Color.BLACK)
        addView(content)
    }

    /** Clears the body and every per-screen scroll/selection registry. */
    private fun resetBody() {
        body.gravity = Gravity.TOP
        body.removeAllViews()
        activeScroll = null
        selectedView = null
    }

    /** Simple full-height list of single-line rows (actions, react, quick…). */
    fun renderList(headerText: String, rows: List<String>, selected: Int, hintText: String) {
        header.text = headerText
        hint.text = hintText
        resetBody()
        if (rows.isEmpty()) {
            body.gravity = Gravity.CENTER
            body.addView(mono(16f, COLOR_SECONDARY).apply { text = context.getString(R.string.hud_empty) })
            return
        }
        val sel = selected.coerceIn(0, rows.size - 1)
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        rows.forEachIndexed { i, label ->
            val isSel = i == sel
            val v = mono(if (isSel) 15f else 13f, if (isSel) COLOR_PRIMARY else COLOR_SECONDARY).apply {
                text = label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (isSel) {
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setBackgroundColor(COLOR_SELECTED_BG)
                }
                setPadding(px(6), px(5), px(6), px(5))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = px(3)
                }
            }
            if (isSel) selectedView = v
            content.addView(v)
        }
        mountScroll(content)
    }

    /** A chat-list row with an optional leading brand icon (0 = no icon) and an
     *  optional second line (e.g. "HH:mm | last message preview"). */
    class Row(val iconRes: Int, val text: String, val subtitle: String = "")

    /** Header + full-height chat list where each row may carry a leading logo.
     *  Every row is drawn; the selection is kept visible so long inboxes stay
     *  reachable and the whole canvas is used. */
    fun renderChatList(headerText: String, rows: List<Row>, selected: Int, hintText: String) {
        header.text = headerText
        hint.text = hintText
        resetBody()
        if (rows.isEmpty()) {
            body.gravity = Gravity.CENTER
            body.addView(mono(16f, COLOR_SECONDARY).apply { text = context.getString(R.string.hud_empty) })
            return
        }
        val sel = selected.coerceIn(0, rows.size - 1)
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        rows.forEachIndexed { i, row ->
            val v = buildChatRow(row, i == sel)
            if (i == sel) selectedView = v
            content.addView(v)
        }
        mountScroll(content)
    }

    private fun buildChatRow(row: Row, isSel: Boolean): View {
        val color = if (isSel) COLOR_PRIMARY else COLOR_SECONDARY
        val rowView = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (isSel) setBackgroundColor(COLOR_SELECTED_BG)
            setPadding(px(6), px(4), px(6), px(4))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) }
        }
        if (row.iconRes != 0) {
            rowView.addView(ImageView(context).apply {
                setImageResource(row.iconRes)
                setColorFilter(color, PorterDuff.Mode.SRC_IN)
                val s = px(if (isSel) 17 else 14)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = px(8) }
            })
        }
        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(mono(if (isSel) 14f else 12f, color).apply {
            text = row.text
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (isSel) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        })
        if (row.subtitle.isNotBlank()) {
            textCol.addView(mono(10f, COLOR_DIM).apply {
                text = row.subtitle
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        rowView.addView(textCol)
        return rowView
    }

    /**
     * Conversation view: a full-height scrolling feed of complete messages
     * (oldest at top, newest at bottom), the selected one highlighted and kept
     * in view. An optional "older above" marker hints that a fling up at the top
     * loads more history.
     */
    fun renderConversation(
        headerText: String,
        texts: List<String>,
        selected: Int,
        olderAbove: Boolean,
        hintText: String,
    ) {
        header.text = headerText
        hint.text = hintText
        resetBody()
        if (texts.isEmpty()) {
            body.gravity = Gravity.CENTER
            body.addView(mono(15f, COLOR_SECONDARY).apply { text = context.getString(R.string.hud_no_messages) })
            return
        }
        val sel = selected.coerceIn(0, texts.size - 1)
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        if (olderAbove) content.addView(mono(11f, COLOR_DIM).apply {
            text = context.getString(R.string.hud_older)
            setPadding(px(6), px(2), px(6), px(4))
        })
        texts.forEachIndexed { i, t ->
            val isSel = i == sel
            val v = mono(if (isSel) 15f else 14f, if (isSel) COLOR_PRIMARY else COLOR_SECONDARY).apply {
                text = t
                if (isSel) {
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setBackgroundColor(COLOR_SELECTED_BG)
                }
                setPadding(px(6), px(5), px(6), px(5))
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(3) }
            }
            if (isSel) selectedView = v
            content.addView(v)
        }
        mountScroll(content)
    }

    /** Mount [content] in a full-height passive scroll and keep the selection
     *  visible once the view is laid out. */
    private fun mountScroll(content: View) {
        val scroll = passiveScroll(content)
        activeScroll = scroll
        body.addView(scroll)
        afterLayout(scroll) { ensureSelectionVisible() }
    }

    private fun ensureSelectionVisible() {
        val scroll = activeScroll ?: return
        val sel = selectedView ?: return
        val y = scroll.scrollY
        val h = scroll.height
        if (h <= 0) return
        val content = scroll.getChildAt(0) ?: return
        val max = (content.height - h).coerceAtLeast(0)
        when {
            sel.top < y -> scroll.smoothScrollTo(0, (sel.top - px(6)).coerceIn(0, max))
            sel.bottom > y + h -> scroll.smoothScrollTo(0, (sel.bottom - h + px(6)).coerceIn(0, max))
        }
    }

    /** Expanded single-message reader: full text in a passive scrollable view. */
    fun renderDetail(headerText: String, fullText: String, hintText: String) {
        header.text = headerText
        hint.text = hintText
        resetBody()
        val text = mono(15f, COLOR_PRIMARY).apply {
            this.text = fullText
            setPadding(px(6), px(4), px(6), px(4))
        }
        mountScroll(text)
    }

    /** Scroll the open detail message by ~80% of its height (dir: +1 down, -1 up),
     *  clamped so it never engages the overscroll edge effect. */
    fun scrollDetailBy(dir: Int) {
        val scroll = activeScroll ?: return
        val content = scroll.getChildAt(0) ?: return
        val amount = (scroll.height * 0.8f).toInt().coerceAtLeast(px(40))
        val max = (content.height - scroll.height).coerceAtLeast(0)
        val target = (scroll.scrollY + dir * amount).coerceIn(0, max)
        if (target != scroll.scrollY) scroll.smoothScrollTo(0, target)
    }

    /** Full-screen image view for a photo message, with an optional caption. */
    fun renderImage(headerText: String, bitmap: Bitmap, caption: String, hintText: String) {
        header.text = headerText
        hint.text = hintText
        resetBody()
        body.gravity = Gravity.CENTER
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
        header.text = headerText
        hint.text = hintText
        resetBody()
        if (big) body.gravity = Gravity.CENTER
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
        resetBody()
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

    private inline fun afterLayout(view: View, crossinline action: () -> Unit) {
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                action()
            }
        })
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

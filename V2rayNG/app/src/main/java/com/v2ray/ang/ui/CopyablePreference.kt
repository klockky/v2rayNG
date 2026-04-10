package com.v2ray.ang.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * A [Preference] whose row is long-clickable. Used in the Proxy-info block of
 * the settings screen so that a short tap can copy the field to the clipboard
 * while a long press opens an editor.
 */
class CopyablePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
) : Preference(context, attrs, defStyleAttr) {

    /** Fired on long-press of the row. */
    var onLongClick: (() -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isLongClickable = true
        holder.itemView.setOnLongClickListener {
            val handler = onLongClick ?: return@setOnLongClickListener false
            handler()
            true
        }
    }
}

package com.unrealstar.live

import androidx.recyclerview.widget.RecyclerView

/**
 * Granular replacement for `notifyDataSetChanged()` after an adapter's
 * backing list has been swapped from [oldSize] to [newSize] rows.
 *
 * Rebind notifications are scoped to the rows that already existed and the
 * size difference is reported as an insert/remove range, so RecyclerView
 * keeps item animations, scroll anchoring, and the layout cache intact
 * instead of invalidating every holder.
 */
fun RecyclerView.Adapter<*>.notifyListReplaced(oldSize: Int, newSize: Int) {
    when {
        newSize > oldSize -> {
            if (oldSize > 0) notifyItemRangeChanged(0, oldSize)
            notifyItemRangeInserted(oldSize, newSize - oldSize)
        }
        newSize < oldSize -> {
            notifyItemRangeRemoved(newSize, oldSize - newSize)
            if (newSize > 0) notifyItemRangeChanged(0, newSize)
        }
        newSize > 0 -> notifyItemRangeChanged(0, newSize)
    }
}

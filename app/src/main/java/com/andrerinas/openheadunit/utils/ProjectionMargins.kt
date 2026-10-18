package com.andrerinas.openheadunit.utils

/** GAL's legacy totals and UiConfig must place the same pixel at each edge. */
internal object ProjectionMargins {
    // The sender splits odd totals with the spare pixel on the right and top.
    // Subtraction avoids overflowing a total when rounding its upper half.
    fun left(total: Int) = total / 2
    fun right(total: Int) = total - left(total)
    fun bottom(total: Int) = total / 2
    fun top(total: Int) = total - bottom(total)
}

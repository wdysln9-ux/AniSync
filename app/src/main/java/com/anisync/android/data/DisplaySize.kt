package com.anisync.android.data

/** App-wide UI scale, adjustable from 10% to 200%. */
data class DisplaySize(val scale: Float) {
    init {
        require(scale in MIN_SCALE..MAX_SCALE) { "Display scale must be between 10% and 200%" }
    }

    val name: String
        get() = when (this) {
            SMALL -> "SMALL"
            DEFAULT -> "DEFAULT"
            LARGE -> "LARGE"
            LARGER -> "LARGER"
            else -> "${(scale * 100).toInt()}%"
        }

    companion object {
        const val MIN_SCALE = 0.3f
        const val MAX_SCALE = 2f
        val SMALL = DisplaySize(0.9f)
        val DEFAULT = DisplaySize(1f)
        val LARGE = DisplaySize(1.1f)
        val LARGER = DisplaySize(1.2f)
        val entries = listOf(SMALL, DEFAULT, LARGE, LARGER)

        /** Supports both old named values and new stored percentages. */
        fun valueOf(value: String): DisplaySize = when (value) {
            "SMALL" -> SMALL
            "DEFAULT" -> DEFAULT
            "LARGE" -> LARGE
            "LARGER" -> LARGER
            else -> DisplaySize((value.removeSuffix("%").toFloatOrNull()
                ?: throw IllegalArgumentException("Unknown display size: $value")) / 100f)
        }
    }
}

package de.pyryco.mobile.data.preferences

enum class Model { OPUS_4_7, SONNET_4_6, HAIKU_4_5 }

fun Model.label(): String =
    when (this) {
        Model.OPUS_4_7 -> "Opus 4.7"
        Model.SONNET_4_6 -> "Sonnet 4.6"
        Model.HAIKU_4_5 -> "Haiku 4.5"
    }

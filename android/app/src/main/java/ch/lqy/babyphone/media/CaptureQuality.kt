package ch.lqy.babyphone.media

/**
 * How much picture the phone that films sends.
 *
 * Decided per call by the phone that is watching, and changeable while the call is up: what is
 * worth the battery at three in the morning is not what is worth it when somebody is actually
 * looking. Retuning a running capturer needs no renegotiation, so the picture never drops.
 */
enum class CaptureQuality(val label: String, val detail: String, val width: Int, val height: Int, val fps: Int) {
    Low("Low", "320x240, 10fps — for a long night on a small battery", 320, 240, 10),
    Standard("Standard", "640x480, 15fps", 640, 480, 15),
    High("High", "1280x720, 24fps — sharp, and the first thing to drain the phone", 1280, 720, 24);

    companion object {
        fun named(value: String?): CaptureQuality =
            entries.firstOrNull { it.name == value } ?: Standard
    }
}

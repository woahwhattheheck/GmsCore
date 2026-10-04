package android.os

/** Host-only SDK constants for exercising the production API guard; this is not Android execution. */
object Build {
    object VERSION {
        @JvmField
        val SDK_INT: Int = Integer.getInteger("cast.timer.sdk", 21)
    }

    object VERSION_CODES {
        const val LOLLIPOP: Int = 21
    }
}

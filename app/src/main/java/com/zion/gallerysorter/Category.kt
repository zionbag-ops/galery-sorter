package com.zion.gallerysorter

/** Folders of phase 1. Events, trips, duplicates and blurry photos arrive in phase 2. */
enum class Category(val he: String) {
    SCREENSHOT("צילומי מסך"),
    GREETING("ברכות ובוקר טוב"),
    DOCUMENT("קבלות ומסמכים"),
    PEOPLE("משפחה ואנשים"),
    FOOD("אוכל"),
    WORK("עבודה"),
    OTHER("אחר");

    companion object {
        fun parse(value: String?): Category? =
            if (value == null) null else entries.firstOrNull { it.name == value }
    }
}

/** Below this confidence a photo is "not sure" and will go to Gemini in phase 3. */
const val UNSURE_BELOW = 0.65

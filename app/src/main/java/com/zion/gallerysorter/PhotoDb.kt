package com.zion.gallerysorter

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore

data class PhotoRow(
    val id: Long,
    val name: String,
    val path: String,
    val dateTaken: Long,
    val category: Category,
    val confidence: Double,
    val labels: String,
    val faces: Int,
    val textLines: Int,
    val userCategory: Category?,
    val ms: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    val effective: Category get() = userCategory ?: category
    val reviewed: Boolean get() = userCategory != null
}

data class Stats(
    val processed: Int,
    val avgMs: Double,
    val reviewed: Int,
    val reviewedCorrect: Int,
)

/** Local record of every scanned photo. Only this app's own database: no photo file is touched. */
class PhotoDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "photos.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photos (
                id INTEGER PRIMARY KEY,
                name TEXT,
                path TEXT,
                date_taken INTEGER,
                category TEXT NOT NULL,
                confidence REAL NOT NULL,
                labels TEXT,
                faces INTEGER,
                text_lines INTEGER,
                user_category TEXT,
                ms INTEGER,
                processed_at INTEGER
            )
            """.trimIndent()
        )
    }

    /** New rules: forget automatic results so they are sorted again; keep the user's corrections. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DELETE FROM photos WHERE user_category IS NULL")
        // Download/ and Documents/ are working files, not the gallery: no longer scanned.
        db.execSQL("DELETE FROM photos WHERE path LIKE 'Download/%' OR path LIKE 'Documents/%'")
    }

    /** Each wrong guess the user corrected: "sorted to X -> belongs in Y". */
    fun confusion(): List<Triple<Category, Category, Int>> {
        val out = ArrayList<Triple<Category, Category, Int>>()
        readableDatabase.rawQuery(
            "SELECT category, user_category, COUNT(*) AS n FROM photos " +
                "WHERE user_category IS NOT NULL AND user_category != category " +
                "GROUP BY category, user_category ORDER BY n DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val a = Category.parse(c.getString(0)) ?: Category.OTHER
                val b = Category.parse(c.getString(1)) ?: Category.OTHER
                out.add(Triple(a, b, c.getInt(2)))
            }
        }
        return out
    }

    /** Where the photos come from (Camera, WhatsApp, Screenshots...). */
    fun folderFrequency(limit: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT path, COUNT(*) AS n FROM photos GROUP BY path ORDER BY n DESC LIMIT $limit", null
        ).use { c -> while (c.moveToNext()) out.add((c.getString(0) ?: "") to c.getInt(1)) }
        return out
    }

    /** For "not sure" photos: what the rules leaned towards, and the top label. */
    fun unsureBreakdown(): List<Pair<String, Int>> {
        val freq = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT category, labels, faces, text_lines FROM photos " +
                "WHERE user_category IS NULL AND confidence < ?",
            arrayOf(UNSURE_BELOW.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val cat = Category.parse(c.getString(0))?.he ?: "?"
                val top = (c.getString(1) ?: "").substringBefore("|").substringBefore(":")
                val key = "$cat · ${top.ifBlank { "בלי תווית" }} · פנים ${c.getInt(2)} · טקסט ${c.getInt(3)}"
                freq[key] = (freq[key] ?: 0) + 1
            }
        }
        return freq.entries.sortedByDescending { it.value }.take(40).map { it.key to it.value }
    }

    fun processedIds(): Set<Long> {
        val ids = HashSet<Long>()
        readableDatabase.rawQuery("SELECT id FROM photos", null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    fun insert(
        item: MediaItem,
        result: Classification,
        ms: Long,
    ) {
        val v = ContentValues().apply {
            put("id", item.id)
            put("name", item.name)
            put("path", item.path)
            put("date_taken", item.dateTaken)
            put("category", result.category.name)
            put("confidence", result.confidence)
            put("labels", result.labels.joinToString("|") { "${it.first}:${String.format(java.util.Locale.US, "%.2f", it.second)}" })
            put("faces", result.faces)
            put("text_lines", result.textLines)
            put("ms", ms)
            put("processed_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("photos", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Count per folder, by the user's correction when there is one. Unsure photos are counted apart. */
    fun counts(): Map<Category, Int> {
        val out = LinkedHashMap<Category, Int>()
        readableDatabase.rawQuery(
            "SELECT COALESCE(user_category, category) AS c, COUNT(*) FROM photos " +
                "WHERE user_category IS NOT NULL OR confidence >= ? GROUP BY c",
            arrayOf(UNSURE_BELOW.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val cat = Category.parse(c.getString(0)) ?: Category.OTHER
                out[cat] = (out[cat] ?: 0) + c.getInt(1)
            }
        }
        return out
    }

    fun unsureCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM photos WHERE user_category IS NULL AND confidence < ?",
            arrayOf(UNSURE_BELOW.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** category == null && unsure == true -> the "not sure" bucket. */
    fun list(category: Category?, unsure: Boolean): List<PhotoRow> {
        val (where, args) = if (unsure) {
            "user_category IS NULL AND confidence < ?" to arrayOf(UNSURE_BELOW.toString())
        } else {
            "COALESCE(user_category, category) = ? AND (user_category IS NOT NULL OR confidence >= ?)" to
                arrayOf(category?.name ?: "", UNSURE_BELOW.toString())
        }
        val rows = ArrayList<PhotoRow>()
        readableDatabase.rawQuery(
            "SELECT id, name, path, date_taken, category, confidence, labels, faces, text_lines, " +
                "user_category, ms FROM photos WHERE $where ORDER BY date_taken DESC",
            args
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    PhotoRow(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        path = c.getString(2) ?: "",
                        dateTaken = c.getLong(3),
                        category = Category.parse(c.getString(4)) ?: Category.OTHER,
                        confidence = c.getDouble(5),
                        labels = c.getString(6) ?: "",
                        faces = c.getInt(7),
                        textLines = c.getInt(8),
                        userCategory = Category.parse(c.getString(9)),
                        ms = c.getLong(10),
                    )
                )
            }
        }
        return rows
    }

    fun setUserCategory(id: Long, category: Category) {
        val v = ContentValues().apply { put("user_category", category.name) }
        writableDatabase.update("photos", v, "id = ?", arrayOf(id.toString()))
    }

    fun stats(): Stats =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), AVG(ms), " +
                "SUM(CASE WHEN user_category IS NOT NULL THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN user_category IS NOT NULL AND user_category = category THEN 1 ELSE 0 END) " +
                "FROM photos",
            null
        ).use { c ->
            if (c.moveToFirst()) Stats(c.getInt(0), c.getDouble(1), c.getInt(2), c.getInt(3))
            else Stats(0, 0.0, 0, 0)
        }

    /** How often each ML Kit label appears: used to tune the rules. */
    fun labelFrequency(limit: Int): List<Pair<String, Int>> {
        val freq = HashMap<String, Int>()
        readableDatabase.rawQuery("SELECT labels FROM photos", null).use { c ->
            while (c.moveToNext()) {
                val s = c.getString(0) ?: continue
                if (s.isBlank()) continue
                s.split("|").forEach { part ->
                    val label = part.substringBefore(":")
                    if (label.isNotBlank()) freq[label] = (freq[label] ?: 0) + 1
                }
            }
        }
        return freq.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    /** Correct vs. wrong per folder, from the photos the user reviewed. */
    fun reviewByCategory(): List<Triple<Category, Int, Int>> {
        val out = ArrayList<Triple<Category, Int, Int>>()
        readableDatabase.rawQuery(
            "SELECT category, COUNT(*), SUM(CASE WHEN user_category = category THEN 1 ELSE 0 END) " +
                "FROM photos WHERE user_category IS NOT NULL GROUP BY category",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val cat = Category.parse(c.getString(0)) ?: Category.OTHER
                out.add(Triple(cat, c.getInt(1), c.getInt(2)))
            }
        }
        return out
    }

    companion object {
        @Volatile
        private var instance: PhotoDb? = null

        fun get(context: Context): PhotoDb =
            instance ?: synchronized(this) {
                instance ?: PhotoDb(context.applicationContext).also { instance = it }
            }
    }
}

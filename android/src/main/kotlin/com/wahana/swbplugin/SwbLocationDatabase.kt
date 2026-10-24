package com.wahana.swbplugin

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import org.json.JSONObject

data class LocationRecordEntity(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val altitude: Double?,
    val altitudeAccuracy: Double?,
    val heading: Double?,
    val speed: Double?,
    val timestamp: Long,
    val isMock: Boolean,
    val provider: String,
    val metadata: String?,
    val uploaded: Boolean,
    val uploadedAt: Long?,
    val attempts: Int
) {
    fun toJSObject(): JSObject {
        val obj = JSObject()
        obj.put("id", id)
        obj.put("isMock", isMock)
        obj.put("uploaded", uploaded)
        if (uploadedAt != null && uploadedAt > 0) {
            obj.put("uploadedAt", uploadedAt)
        }
        obj.put("attempts", attempts)

        val pos = JSObject()
        pos.put("timestamp", timestamp)

        val coords = JSObject()
        coords.put("latitude", latitude)
        coords.put("longitude", longitude)
        coords.put("accuracy", accuracy)
        coords.put("altitude", altitude)
        coords.put("altitudeAccuracy", altitudeAccuracy)
        coords.put("heading", heading)
        coords.put("speed", speed)
        coords.put("isMock", isMock)
        pos.put("coords", coords)

        obj.put("position", pos)
        return obj
    }

    fun toUploadPayloadJson(): String {
        val root = JSONObject()
        root.put("id", id)
        root.put("latitude", latitude)
        root.put("longitude", longitude)
        root.put("accuracy", accuracy)
        if (altitude != null) root.put("altitude", altitude) else root.put("altitude", JSONObject.NULL)
        if (speed != null) root.put("speed", speed) else root.put("speed", 0.0)
        if (heading != null) root.put("heading", heading) else root.put("heading", 0.0)
        root.put("timestamp", timestamp)
        root.put("isMock", isMock)
        root.put("provider", provider)
        if (metadata != null && metadata.isNotEmpty()) {
            try {
                root.put("metadata", JSONObject(metadata))
            } catch (_: Exception) {
                root.put("metadata", JSONObject())
            }
        } else {
            root.put("metadata", JSONObject())
        }
        return root.toString()
    }
}

class SwbLocationDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "swb_locations.db"
        const val DATABASE_VERSION = 1
        const val TABLE_LOCATIONS = "locations"

        const val COL_ID = "id"
        const val COL_LAT = "latitude"
        const val COL_LNG = "longitude"
        const val COL_ACCURACY = "accuracy"
        const val COL_ALTITUDE = "altitude"
        const val COL_ALTITUDE_ACC = "altitudeAccuracy"
        const val COL_HEADING = "heading"
        const val COL_SPEED = "speed"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_IS_MOCK = "isMock"
        const val COL_PROVIDER = "provider"
        const val COL_METADATA = "metadata"
        const val COL_UPLOADED = "uploaded"
        const val COL_UPLOADED_AT = "uploadedAt"
        const val COL_ATTEMPTS = "attempts"

        @Volatile
        private var instance: SwbLocationDatabase? = null

        fun getInstance(context: Context): SwbLocationDatabase {
            return instance ?: synchronized(this) {
                instance ?: SwbLocationDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createQuery = """
            CREATE TABLE IF NOT EXISTS $TABLE_LOCATIONS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_LAT REAL NOT NULL,
                $COL_LNG REAL NOT NULL,
                $COL_ACCURACY REAL NOT NULL,
                $COL_ALTITUDE REAL,
                $COL_ALTITUDE_ACC REAL,
                $COL_HEADING REAL,
                $COL_SPEED REAL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_IS_MOCK INTEGER NOT NULL DEFAULT 0,
                $COL_PROVIDER TEXT,
                $COL_METADATA TEXT,
                $COL_UPLOADED INTEGER NOT NULL DEFAULT 0,
                $COL_UPLOADED_AT INTEGER,
                $COL_ATTEMPTS INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
        db.execSQL(createQuery)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_swb_timestamp ON $TABLE_LOCATIONS ($COL_TIMESTAMP)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_swb_uploaded ON $TABLE_LOCATIONS ($COL_UPLOADED)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOCATIONS")
        onCreate(db)
    }

    @Synchronized
    fun insertLocation(record: LocationRecordEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, record.id)
            put(COL_LAT, record.latitude)
            put(COL_LNG, record.longitude)
            put(COL_ACCURACY, record.accuracy)
            if (record.altitude != null) put(COL_ALTITUDE, record.altitude) else putNull(COL_ALTITUDE)
            if (record.altitudeAccuracy != null) put(COL_ALTITUDE_ACC, record.altitudeAccuracy) else putNull(COL_ALTITUDE_ACC)
            if (record.heading != null) put(COL_HEADING, record.heading) else putNull(COL_HEADING)
            if (record.speed != null) put(COL_SPEED, record.speed) else putNull(COL_SPEED)
            put(COL_TIMESTAMP, record.timestamp)
            put(COL_IS_MOCK, if (record.isMock) 1 else 0)
            put(COL_PROVIDER, record.provider)
            put(COL_METADATA, record.metadata)
            put(COL_UPLOADED, if (record.uploaded) 1 else 0)
            if (record.uploadedAt != null) put(COL_UPLOADED_AT, record.uploadedAt) else putNull(COL_UPLOADED_AT)
            put(COL_ATTEMPTS, record.attempts)
        }
        db.insertWithOnConflict(TABLE_LOCATIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun getHistory(
        limit: Int = 100,
        offset: Int = 0,
        from: Long? = null,
        to: Long? = null
    ): Triple<List<LocationRecordEntity>, Int, Int> {
        val db = readableDatabase

        val whereClauses = mutableListOf<String>()
        val whereArgs = mutableListOf<String>()

        if (from != null) {
            whereClauses.add("$COL_TIMESTAMP >= ?")
            whereArgs.add(from.toString())
        }
        if (to != null) {
            whereClauses.add("$COL_TIMESTAMP <= ?")
            whereArgs.add(to.toString())
        }

        val whereStr = if (whereClauses.isNotEmpty()) whereClauses.joinToString(" AND ") else null
        val argsArr = if (whereArgs.isNotEmpty()) whereArgs.toTypedArray() else null

        // Count total
        var total = 0
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_LOCATIONS" + (if (whereStr != null) " WHERE $whereStr" else ""), argsArr).use { cursor ->
            if (cursor.moveToFirst()) {
                total = cursor.getInt(0)
            }
        }

        // Count pending
        var pending = 0
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_LOCATIONS WHERE $COL_UPLOADED = 0", null).use { cursor ->
            if (cursor.moveToFirst()) {
                pending = cursor.getInt(0)
            }
        }

        // Fetch records
        val records = mutableListOf<LocationRecordEntity>()
        val query = "SELECT * FROM $TABLE_LOCATIONS" +
                (if (whereStr != null) " WHERE $whereStr" else "") +
                " ORDER BY $COL_TIMESTAMP DESC LIMIT $limit OFFSET $offset"

        db.rawQuery(query, argsArr).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(cursorToEntity(cursor))
            }
        }

        return Triple(records, total, pending)
    }

    @Synchronized
    fun getPendingUploads(limit: Int = 50): List<LocationRecordEntity> {
        val db = readableDatabase
        val records = mutableListOf<LocationRecordEntity>()
        val query = "SELECT * FROM $TABLE_LOCATIONS WHERE $COL_UPLOADED = 0 ORDER BY $COL_TIMESTAMP ASC LIMIT $limit"
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(cursorToEntity(cursor))
            }
        }
        return records
    }

    @Synchronized
    fun markUploaded(id: String, uploadedAt: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_UPLOADED, 1)
            put(COL_UPLOADED_AT, uploadedAt)
        }
        db.update(TABLE_LOCATIONS, values, "$COL_ID = ?", arrayOf(id))
    }

    @Synchronized
    fun incrementAttempts(id: String) {
        val db = writableDatabase
        db.execSQL("UPDATE $TABLE_LOCATIONS SET $COL_ATTEMPTS = $COL_ATTEMPTS + 1 WHERE $COL_ID = ?", arrayOf(id))
    }

    @Synchronized
    fun clearHistory() {
        val db = writableDatabase
        db.delete(TABLE_LOCATIONS, null, null)
    }

    @Synchronized
    fun pruneOldRecords(retentionDays: Int, maxRecords: Int) {
        try {
            val db = writableDatabase
            if (retentionDays > 0) {
                val cutoff = System.currentTimeMillis() - (retentionDays * 86400000L)
                db.delete(TABLE_LOCATIONS, "$COL_TIMESTAMP < ? AND $COL_UPLOADED = 1", arrayOf(cutoff.toString()))
            }
            if (maxRecords > 0) {
                val query = """
                    DELETE FROM $TABLE_LOCATIONS WHERE $COL_ID NOT IN (
                        SELECT $COL_ID FROM $TABLE_LOCATIONS ORDER BY $COL_TIMESTAMP DESC LIMIT $maxRecords
                    )
                """.trimIndent()
                db.execSQL(query)
            }
        } catch (_: Exception) {
        }
    }

    private fun cursorToEntity(cursor: Cursor): LocationRecordEntity {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID))
        val lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LAT))
        val lng = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LNG))
        val acc = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_ACCURACY))

        val altCol = cursor.getColumnIndexOrThrow(COL_ALTITUDE)
        val altitude = if (cursor.isNull(altCol)) null else cursor.getDouble(altCol)

        val altAccCol = cursor.getColumnIndexOrThrow(COL_ALTITUDE_ACC)
        val altAcc = if (cursor.isNull(altAccCol)) null else cursor.getDouble(altAccCol)

        val headCol = cursor.getColumnIndexOrThrow(COL_HEADING)
        val heading = if (cursor.isNull(headCol)) null else cursor.getDouble(headCol)

        val spdCol = cursor.getColumnIndexOrThrow(COL_SPEED)
        val speed = if (cursor.isNull(spdCol)) null else cursor.getDouble(spdCol)

        val time = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
        val isMock = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_MOCK)) == 1
        val provider = cursor.getString(cursor.getColumnIndexOrThrow(COL_PROVIDER)) ?: "fused"
        val metadata = cursor.getString(cursor.getColumnIndexOrThrow(COL_METADATA))
        val uploaded = cursor.getInt(cursor.getColumnIndexOrThrow(COL_UPLOADED)) == 1

        val upAtCol = cursor.getColumnIndexOrThrow(COL_UPLOADED_AT)
        val uploadedAt = if (cursor.isNull(upAtCol)) null else cursor.getLong(upAtCol)
        val attempts = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ATTEMPTS))

        return LocationRecordEntity(
            id = id,
            latitude = lat,
            longitude = lng,
            accuracy = acc,
            altitude = altitude,
            altitudeAccuracy = altAcc,
            heading = heading,
            speed = speed,
            timestamp = time,
            isMock = isMock,
            provider = provider,
            metadata = metadata,
            uploaded = uploaded,
            uploadedAt = uploadedAt,
            attempts = attempts
        )
    }
}


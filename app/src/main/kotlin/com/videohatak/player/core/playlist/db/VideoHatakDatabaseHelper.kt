package com.videohatak.player.core.playlist.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem

class VideoHatakDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "videohatak_playlists.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_PLAYLISTS = "playlists"
        const val TABLE_PLAYLIST_ITEMS = "playlist_items"

        // Playlists Columns
        const val COL_P_ID = "id"
        const val COL_P_TITLE = "title"
        const val COL_P_CREATED_AT = "created_at"
        const val COL_P_UPDATED_AT = "updated_at"

        // Playlist Items Columns
        const val COL_I_ID = "id"
        const val COL_I_PLAYLIST_ID = "playlist_id"
        const val COL_I_VIDEO_ID = "video_id"
        const val COL_I_ORDER_INDEX = "order_index"
        const val COL_I_TITLE = "title"
        const val COL_I_DURATION = "duration_ms"
        const val COL_I_SIZE = "size_bytes"
        const val COL_I_DATE_ADDED = "date_added_sec"
        const val COL_I_URI = "content_uri"
        const val COL_I_IS_AVAILABLE = "is_available"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PLAYLISTS (
                $COL_P_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_P_TITLE TEXT NOT NULL,
                $COL_P_CREATED_AT INTEGER NOT NULL,
                $COL_P_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_PLAYLIST_ITEMS (
                $COL_I_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_I_PLAYLIST_ID INTEGER NOT NULL,
                $COL_I_VIDEO_ID INTEGER NOT NULL,
                $COL_I_ORDER_INDEX INTEGER NOT NULL,
                $COL_I_TITLE TEXT NOT NULL,
                $COL_I_DURATION INTEGER NOT NULL,
                $COL_I_SIZE INTEGER NOT NULL,
                $COL_I_DATE_ADDED INTEGER NOT NULL,
                $COL_I_URI TEXT NOT NULL,
                $COL_I_IS_AVAILABLE INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY ($COL_I_PLAYLIST_ID) REFERENCES $TABLE_PLAYLISTS($COL_P_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PLAYLIST_ITEMS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PLAYLISTS")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun insertPlaylist(title: String): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_P_TITLE, title)
            put(COL_P_CREATED_AT, now)
            put(COL_P_UPDATED_AT, now)
        }
        return db.insert(TABLE_PLAYLISTS, null, values)
    }

    fun updatePlaylist(id: Long, title: String): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_P_TITLE, title)
            put(COL_P_UPDATED_AT, System.currentTimeMillis())
        }
        return db.update(TABLE_PLAYLISTS, values, "$COL_P_ID = ?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_PLAYLISTS, "$COL_P_ID = ?", arrayOf(id.toString()))
    }

    fun getPlaylists(): List<Playlist> {
        val list = mutableListOf<Playlist>()
        val db = readableDatabase
        val query = """
            SELECT p.$COL_P_ID, p.$COL_P_TITLE, p.$COL_P_CREATED_AT, p.$COL_P_UPDATED_AT,
                   COUNT(i.$COL_I_ID) as item_count,
                   TOTAL(i.$COL_I_DURATION) as total_duration,
                   (SELECT $COL_I_URI FROM $TABLE_PLAYLIST_ITEMS WHERE $COL_I_PLAYLIST_ID = p.$COL_P_ID ORDER BY $COL_I_ORDER_INDEX ASC LIMIT 1) as first_uri
            FROM $TABLE_PLAYLISTS p
            LEFT JOIN $TABLE_PLAYLIST_ITEMS i ON p.$COL_P_ID = i.$COL_I_PLAYLIST_ID
            GROUP BY p.$COL_P_ID
            ORDER BY p.$COL_P_UPDATED_AT DESC
        """.trimIndent()

        val cursor = db.rawQuery(query, null)
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_P_ID)
            val titleIdx = it.getColumnIndexOrThrow(COL_P_TITLE)
            val createdIdx = it.getColumnIndexOrThrow(COL_P_CREATED_AT)
            val updatedIdx = it.getColumnIndexOrThrow(COL_P_UPDATED_AT)
            val countIdx = it.getColumnIndexOrThrow("item_count")
            val durIdx = it.getColumnIndexOrThrow("total_duration")
            val uriIdx = it.getColumnIndexOrThrow("first_uri")

            while (it.moveToNext()) {
                list.add(
                    Playlist(
                        id = it.getLong(idIdx),
                        title = it.getString(titleIdx),
                        createdAt = it.getLong(createdIdx),
                        updatedAt = it.getLong(updatedIdx),
                        itemCount = it.getInt(countIdx),
                        totalDurationMs = it.getLong(durIdx),
                        thumbnailUri = it.getString(uriIdx)
                    )
                )
            }
        }
        return list
    }

    fun insertItem(item: PlaylistItem): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_PLAYLIST_ID, item.playlistId)
            put(COL_I_VIDEO_ID, item.videoId)
            put(COL_I_ORDER_INDEX, item.orderIndex)
            put(COL_I_TITLE, item.videoTitle)
            put(COL_I_DURATION, item.durationMs)
            put(COL_I_SIZE, item.sizeBytes)
            put(COL_I_DATE_ADDED, item.dateAddedSec)
            put(COL_I_URI, item.contentUri)
            put(COL_I_IS_AVAILABLE, if (item.isAvailable) 1 else 0)
        }
        val res = db.insert(TABLE_PLAYLIST_ITEMS, null, values)
        touchPlaylist(item.playlistId)
        return res
    }

    fun deleteItem(itemId: Long, playlistId: Long): Int {
        val db = writableDatabase
        val rows = db.delete(TABLE_PLAYLIST_ITEMS, "$COL_I_ID = ?", arrayOf(itemId.toString()))
        touchPlaylist(playlistId)
        return rows
    }

    fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem> {
        val list = mutableListOf<PlaylistItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_PLAYLIST_ITEMS,
            null,
            "$COL_I_PLAYLIST_ID = ?",
            arrayOf(playlistId.toString()),
            null,
            null,
            "$COL_I_ORDER_INDEX ASC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_I_ID)
            val pIdIdx = it.getColumnIndexOrThrow(COL_I_PLAYLIST_ID)
            val vIdIdx = it.getColumnIndexOrThrow(COL_I_VIDEO_ID)
            val ordIdx = it.getColumnIndexOrThrow(COL_I_ORDER_INDEX)
            val titleIdx = it.getColumnIndexOrThrow(COL_I_TITLE)
            val durIdx = it.getColumnIndexOrThrow(COL_I_DURATION)
            val sizeIdx = it.getColumnIndexOrThrow(COL_I_SIZE)
            val dateIdx = it.getColumnIndexOrThrow(COL_I_DATE_ADDED)
            val uriIdx = it.getColumnIndexOrThrow(COL_I_URI)
            val availIdx = it.getColumnIndexOrThrow(COL_I_IS_AVAILABLE)

            while (it.moveToNext()) {
                list.add(
                    PlaylistItem(
                        id = it.getLong(idIdx),
                        playlistId = it.getLong(pIdIdx),
                        videoId = it.getLong(vIdIdx),
                        orderIndex = it.getInt(ordIdx),
                        videoTitle = it.getString(titleIdx),
                        durationMs = it.getLong(durIdx),
                        sizeBytes = it.getLong(sizeIdx),
                        dateAddedSec = it.getLong(dateIdx),
                        contentUri = it.getString(uriIdx),
                        isAvailable = it.getInt(availIdx) == 1
                    )
                )
            }
        }
        return list
    }

    fun updateItemOrder(itemId: Long, newIndex: Int) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_ORDER_INDEX, newIndex)
        }
        db.update(TABLE_PLAYLIST_ITEMS, values, "$COL_I_ID = ?", arrayOf(itemId.toString()))
    }

    fun updateItemAvailability(itemId: Long, isAvailable: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_IS_AVAILABLE, if (isAvailable) 1 else 0)
        }
        db.update(TABLE_PLAYLIST_ITEMS, values, "$COL_I_ID = ?", arrayOf(itemId.toString()))
    }

    fun updateItemUri(itemId: Long, newUri: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_I_URI, newUri)
            put(COL_I_IS_AVAILABLE, 1)
        }
        db.update(TABLE_PLAYLIST_ITEMS, values, "$COL_I_ID = ?", arrayOf(itemId.toString()))
    }

    private fun touchPlaylist(playlistId: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_P_UPDATED_AT, System.currentTimeMillis())
        }
        db.update(TABLE_PLAYLISTS, values, "$COL_P_ID = ?", arrayOf(playlistId.toString()))
    }
}

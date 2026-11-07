package com.littlechin.qrscanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/** 單一條碼的解碼結果，含在原圖上的框選範圍。 */
data class CodeItem(
    val value: String,
    val format: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", value)
        .put("f", format)
        .put("l", left)
        .put("t", top)
        .put("r", right)
        .put("b", bottom)

    companion object {
        fun listToJson(items: List<CodeItem>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(json: String): List<CodeItem> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CodeItem(
                    value = o.getString("v"),
                    format = o.getString("f"),
                    left = o.optInt("l"),
                    top = o.optInt("t"),
                    right = o.optInt("r"),
                    bottom = o.optInt("b"),
                )
            }
        }
    }
}

@Entity(tableName = "scans")
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val imagePath: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val codesJson: String,
) {
    val codes: List<CodeItem>
        get() = CodeItem.listFromJson(codesJson)
}

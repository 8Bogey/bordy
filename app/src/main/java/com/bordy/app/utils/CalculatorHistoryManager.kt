package com.bordy.app.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val expression: String, val result: String)

class CalculatorHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("calc_history", Context.MODE_PRIVATE)
    private val key = "history_list"

    fun save(expression: String, result: String) {
        if (expression.isBlank() || result.isBlank()) return
        val list = getHistory().toMutableList()
        list.add(0, HistoryItem(expression, result))
        if (list.size > 50) list.removeAt(list.size - 1) // نكتفي بآخر 50 عملية

        val jsonArray = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("expr", it.expression)
            obj.put("res", it.result)
            jsonArray.put(obj)
        }
        prefs.edit().putString(key, jsonArray.toString()).apply()
    }

    fun getHistory(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        val jsonString = prefs.getString(key, null) ?: return list
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(HistoryItem(obj.getString("expr"), obj.getString("res")))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }
}
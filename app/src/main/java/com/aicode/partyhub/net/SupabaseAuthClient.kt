package com.aicode.partyhub.net

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

class SupabaseAuthClient(context: Context) {
    companion object { const val URL = "https://beasxxexggnjcocdbtdl.supabase.co"; const val KEY = "sb_publishable_85hufMmFQvmLQt2Rz1Hcrw_S4t7BjAO" }
    private val prefs = context.getSharedPreferences("supabase-auth", Context.MODE_PRIVATE)
    private val http = OkHttpClient(); private val worker = Executors.newSingleThreadExecutor()
    fun email(): String? = prefs.getString("email", null)
    fun signIn(email: String, password: String, done: (Boolean, String) -> Unit) = auth("/auth/v1/token?grant_type=password", email, password, done)
    fun signUp(email: String, password: String, done: (Boolean, String) -> Unit) = auth("/auth/v1/signup", email, password, done)
    private fun auth(path: String, email: String, password: String, done: (Boolean, String) -> Unit) { worker.execute { try { val body = JSONObject().put("email", email).put("password", password).toString().toRequestBody("application/json".toMediaType()); val r = http.newCall(Request.Builder().url(URL + path).header("apikey", KEY).post(body).build()).execute(); val text = r.body?.string().orEmpty(); if (!r.isSuccessful) done(false, JSONObject(text).optString("msg", JSONObject(text).optString("message", "请求失败"))) else { val j = JSONObject(text); prefs.edit().putString("access", j.optString("access_token")).putString("refresh", j.optString("refresh_token")).putString("email", email).apply(); done(true, "登录成功") } } catch (e: Exception) { done(false, e.message ?: "网络错误") } } }
    fun signOut() { prefs.edit().clear().apply() }
}

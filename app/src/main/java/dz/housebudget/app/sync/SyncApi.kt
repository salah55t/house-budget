package dz.housebudget.app.sync

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/** واجهة نقاط الاتصال (HTTP API) لسيرفر المزامنة */
interface SyncApi {

    // مزامنة كاملة: رفع البيانات المحلية واستقبال الحالة الموحدة
    @POST("api/{code}/sync")
    suspend fun sync(@Path("code") code: String, @Body body: SyncRequest): SyncResponse

    // مسار لحظي: جلب المراسلات الجديدة فقط منذ طابع زمني للسيرفر (حمولة صغيرة)
    @GET("api/{code}/messages")
    suspend fun pullMessages(@Path("code") code: String, @Query("since") since: Long): MessagesResponse

    // مسار لحظي: رفع مراسلات جديدة/معدّلة واستقبال الوارد الجديد في نفس الاستجابة
    @POST("api/{code}/messages")
    suspend fun pushMessages(
        @Path("code") code: String,
        @Query("since") since: Long,
        @Body body: MessagesPushRequest
    ): MessagesResponse

    // جلب الحالة فقط دون رفع شيء
    @GET("api/{code}/state")
    suspend fun pullState(@Path("code") code: String): SyncResponse

    // فحص وجود السيرفر وصحة الرمز
    @GET("api/{code}/ping")
    suspend fun ping(@Path("code") code: String): PingResponse
}

/** مصنع عميل HTTP مع توحيد رابط السيرفر */
object ApiClient {

    fun build(baseUrl: String): SyncApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(normalize(baseUrl))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SyncApi::class.java)
    }

    /**
     * توحيد رابط السيرفر:
     * "house-sync.onrender.com" يصبح "https://house-sync.onrender.com/"
     */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        if (!url.endsWith("/")) url = "$url/"
        return url
    }
}

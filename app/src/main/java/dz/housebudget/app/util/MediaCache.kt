package dz.housebudget.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dz.housebudget.app.data.HouseMessage
import dz.housebudget.app.data.MessageMedia
import dz.housebudget.app.sync.ApiClient
import dz.housebudget.app.sync.FileUploadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * ذاكرة الوسائط المحلية (الإصدار 1.8 — رسائل صوتية وصور مثل واتساب):
 *
 *  - كل ملف وسائط يُخزَّن داخل مساحة التطبيق الداخلية (بلا أي صلاحيات تخزين):
 *    filesDir/media/<رمز المنزل>/<معرّف الملف>.jpg أو .m4a
 *  - الإرسال: ضغط الصورة (تصغير + JPEG 75%) أو تسجيل صوتي مضغوط ثم رفع
 *    base64 إلى السيرفر الذي يُرجع معرّفاً فريداً تشير إليه المراسلة.
 *  - الاستقبال: عند فتح المحادثة يُحمَّل الملف من السيرفر مرة واحدة فقط
 *    (تحميل ذري: كتابة في ملف مؤقت ثم إعادة تسمية، مع منع التحميل المزدوج).
 */
object MediaCache {

    // أقصى بُعد للصورة المرسلة: تكفي لعرض واضح بحجم صغير للرفع
    private const val IMAGE_MAX_DIM = 1280
    private const val IMAGE_QUALITY = 75

    // أقصى بُعد لعرض الصورة في الفقاعة (نقرتها مسبقاً لتفادي Bitmap كبير بالذاكرة)
    private const val DISPLAY_MAX_DIM = 1024

    /** الملف المحلي لمعرّف وسائط معين (قد لا يكون محمّلاً بعد) */
    fun localFile(context: Context, houseCode: String, fileId: String, mediaType: String): File {
        val dir = File(context.filesDir, "media/${houseCode.trim()}")
        dir.mkdirs()
        val ext = if (mediaType == MessageMedia.IMAGE) "jpg" else "m4a"
        return File(dir, "${fileId}.$ext")
    }

    /** هل الملف محمّل محلياً وجاهز للعرض؟ */
    fun isDownloaded(context: Context, houseCode: String, msg: HouseMessage): Boolean =
        msg.mediaFile.isNotBlank() &&
            localFile(context, houseCode, msg.mediaFile, msg.mediaType).exists()

    // منع التحميل المزدوج لنفس الملف من عدة خيوط في نفس اللحظة
    private val inProgress = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * تحميل ملف وسائط من السيرفر إن لم يكن محمّلاً بعد.
     * يُرجع true إذا كان الملف جاهزاً محلياً بعد هذه المحاولة.
     * (كتابة الملف على خيط IO — تُستدعى أحياناً من نطاق الخيط الرئيسي)
     */
    suspend fun download(
        context: Context,
        houseCode: String,
        serverUrl: String,
        msg: HouseMessage
    ): Boolean = withContext(Dispatchers.IO) {
        if (msg.mediaFile.isBlank()) return@withContext false
        val target = localFile(context, houseCode, msg.mediaFile, msg.mediaType)
        if (target.exists()) return@withContext true
        if (!inProgress.add(msg.mediaFile)) return@withContext false // جارٍ بالفعل من خيط آخر

        try {
            val api = ApiClient.build(serverUrl)
            val body = api.downloadFile(houseCode.trim(), msg.mediaFile)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            body.byteStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            // تحميل ذري: الملف لا يظهر جاهزاً إلا بعد اكتماله كاملاً
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext false
            }
            return@withContext true
        } catch (_: Exception) {
            // فشل الشبكة: سيُعاد التحميل عند فتح المحادثة لاحقاً
            false
        } finally {
            inProgress.remove(msg.mediaFile)
        }
    }

    /**
     * ضغط صورة مختارة (من المعرض أو الكاميرا) وتحويلها لملف JPEG جاهز للرفع:
     * تصغير إلى 1280px كحد أقصى + جودة 75% → حجم نموذجي 100-300 كيلوبايت.
     * يراعي دوران الصورة من بيانات EXIF (صور الكاميرا خاصة).
     */
    suspend fun compressImage(context: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver

            // 1) قراءة الأبعاد فقط أولاً لتحديد معامل التصغير
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: return@withContext null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            var maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / (sample * 2) >= IMAGE_MAX_DIM) sample *= 2

            // 2) فك الترميز الحقيقي مصغّراً
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@withContext null

            // 3) تصحيح دوران EXIF إن وجد
            val rotation = try {
                resolver.openInputStream(uri)?.use { stream ->
                    when (ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) {
                0f
            }

            val bitmap = if (rotation != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            } else raw

            // 4) حفظ JPEG مضغوط في ملف مؤقت داخل ذاكرة التخزين المؤقت
            val out = File(context.cacheDir, "img_${System.currentTimeMillis()}.jpg")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, it) }
            if (bitmap !== raw) bitmap.recycle()
            raw.recycle()
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * رفع ملف وسائط إلى السيرفر (base64 داخل JSON) وإرجاع معرّف الملف
     * الذي ستشير إليه المراسلة في حقل mediaFile — أو null عند الفشل.
     */
    suspend fun upload(
        context: Context,
        houseCode: String,
        serverUrl: String,
        file: File,
        mime: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = ByteArray(file.length().toInt())
            FileInputStream(file).use { it.read(bytes) }
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

            val api = ApiClient.build(serverUrl)
            val response = api.uploadFile(
                houseCode.trim(),
                FileUploadRequest(name = file.name, mime = mime, data = base64)
            )
            if (response.ok && response.id.isNotBlank()) response.id else null
        } catch (_: Exception) {
            null
        }
    }

    /** تحميل صورة جاهزة للعرض بحجم مناسب للذاكرة (بلا تحميل بحجم كامل) */
    fun displayBitmap(file: File, maxDim: Int = DISPLAY_MAX_DIM): Bitmap? {
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    /** مدة تسجيل صوتي بالميلي ثانية (لعرضها في الفقاعة) أو 0 عند الفشل */
    fun audioDurationMs(file: File): Long {
        if (!file.exists()) return 0
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val d = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            d
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** تنسيق مدة (ميلي ثانية) إلى 0:00 / 1:23 للعرض في فقاعة الصوت */
    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** حجم ملف مكتوب بصيغة مفهومة (كب/ميغابايت) */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f ميغابايت".format(bytes / (1024f * 1024f))
        else -> "${bytes / 1024} كيلوبايت"
    }
}

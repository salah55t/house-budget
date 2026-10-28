package dz.housebudget.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dz.housebudget.app.HouseBudgetApp
import dz.housebudget.app.R
import dz.housebudget.app.chat.AudioPlayer
import dz.housebudget.app.chat.ChatHeadManager
import dz.housebudget.app.data.HouseMessage
import dz.housebudget.app.data.MessageMedia
import dz.housebudget.app.data.MessageStatus
import dz.housebudget.app.data.MessageTypes
import dz.housebudget.app.databinding.FragmentMessagesBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.sync.SyncForegroundService
import dz.housebudget.app.util.FormatUtils
import dz.housebudget.app.util.MediaCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * شاشة المراسلات: قسمان عبر مفتاح تبديل علوي —
 * 1) طلبات الشراء: قائمة انتظار الشراء بين الهاتفين؛ تحديد الطلب يُظهر خانة
 *    الثمن، وتأكيده يُنشئ مصروفاً فيُخصم من الميزانية تلقائياً.
 * 2) المحادثة: رسائل نصية + رسائل صوتية (تسجيل بالمايك مثل واتساب)
 *    + صور من المعرض (تُضغط قبل الإرسال) — من الإصدار 1.8.
 * من الإصدار 1.5: الشاشة تجعل الخدمة تستقصي كل ثانيتين (محادثة لحظية)،
 * وعند فتحها تجلب الجديد فوراً — فتظهر رسائل الطرف الآخر خلال ثوانٍ،
 * ومرفقات الوسائط تُحمّل تلقائياً فور ظهورها في القائمة.
 */
class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!
    private lateinit var requestsAdapter: PurchaseRequestsAdapter
    private lateinit var chatAdapter: ChatAdapter

    // ---------- التسجيل الصوتي (الإصدار 1.8) ----------
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStartedAt: Long = 0
    private var recTimerJob: Job? = null
    private var recording = false

    // اختيار صورة من المعرض (بلا أي صلاحية تخزين — عبر موفّر النظام)
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onImagePicked(uri)
        }

    // طلب صلاحية المايك (أندرويد 6+ زمنية) — عند المنح يبدأ التسجيل مباشرة
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else {
                Toast.makeText(
                    requireContext(), R.string.media_mic_permission_denied, Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requestsAdapter = PurchaseRequestsAdapter(
            onConfirm = { msg, price -> onConfirmPurchase(msg, price) },
            onReject = { msg -> onRejectRequest(msg) }
        )
        binding.rvRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequests.adapter = requestsAdapter

        chatAdapter = ChatAdapter()
        chatAdapter.onImageClick = { msg -> showImageViewer(msg) }
        binding.rvChat.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChat.adapter = chatAdapter

        // التبديل بين القسمين: طلبات الشراء / المحادثة
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val requestsTab = checkedId == R.id.btnTabRequests
            binding.layoutRequests.visibility = if (requestsTab) View.VISIBLE else View.GONE
            binding.layoutTexts.visibility = if (requestsTab) View.GONE else View.VISIBLE
        }

        binding.fabNewRequest.setOnClickListener { showNewRequestDialog() }
        binding.btnSend.setOnClickListener { sendText() }
        binding.btnImage.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnMic.setOnClickListener { onMicClicked() }
        binding.btnRecCancel.setOnClickListener { cancelRecording() }

        // تدفق حي لكل المراسلات: يُقسم إلى طلبات ومحادثة ويحدّث القسمين تلقائياً
        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.messagesFlow.collect { all ->
                val active = all.filter { !it.deleted }

                val requests = active.filter { it.type == MessageTypes.REQUEST }
                    .sortedWith(compareBy({ statusRank(it.status) }, { -it.createdAt }))
                val texts = active.filter { it.type == MessageTypes.TEXT }
                    .sortedBy { it.createdAt }

                requestsAdapter.submitList(requests)
                chatAdapter.submitList(texts) {
                    // المحادثة تبقى منزلقة إلى أحدث رسالة
                    if (texts.isNotEmpty()) {
                        binding.rvChat.scrollToPosition(texts.size - 1)
                    }
                }

                // مرفقات الوسائط (صور/صوتيات) لم تُحمّل بعد: تحميل تلقائي فوري
                for (msg in texts) {
                    if (msg.mediaType != MessageMedia.NONE && msg.mediaFile.isNotBlank()) {
                        ensureMedia(msg)
                    }
                }

                val pending = requests.count { it.status == MessageStatus.PENDING }
                binding.txtRequestsSummary.visibility =
                    if (requests.isEmpty()) View.GONE else View.VISIBLE
                if (requests.isNotEmpty()) {
                    binding.txtRequestsSummary.text =
                        getString(R.string.requests_count, pending, requests.size)
                }
                binding.emptyRequests.visibility =
                    if (requests.isEmpty()) View.VISIBLE else View.GONE
                binding.rvRequests.visibility =
                    if (requests.isEmpty()) View.GONE else View.VISIBLE

                binding.emptyTexts.visibility = if (texts.isEmpty()) View.VISIBLE else View.GONE
                binding.rvChat.visibility = if (texts.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    /** ترتيب الطلبات: قيد الانتظار أولاً ثم المشتراة ثم المرفوضة */
    private fun statusRank(status: String): Int = when (status) {
        MessageStatus.PENDING -> 0
        MessageStatus.PURCHASED -> 1
        else -> 2
    }

    // ---------- المرفقات: تحميل تلقائي ----------

    // الملفات قيد التحميل الآن (تفادي طلبات مكررة لنفس المرفق)
    private val mediaJobs = HashMap<String, Job>()

    private fun ensureMedia(msg: HouseMessage) {
        if (mediaJobs.containsKey(msg.mediaFile)) return
        mediaJobs[msg.mediaFile] = (requireActivity().application as HouseBudgetApp)
            .appScope.launch {
                val ok = BudgetRepository.ensureMediaDownloaded(msg)
                if (!ok) {
                    // فشل مؤقت (شبكة مثلاً): نسمح بمحاولة لاحقة عند التحديث القادم
                    delay(3000)
                }
                mediaJobs.remove(msg.mediaFile)
            }
    }

    /** عارض صورة بحجم كامل (نقرة على صورة داخل فقاعة) */
    private fun showImageViewer(msg: HouseMessage) {
        val settings = BudgetRepository.cachedHouseCode()
        val file = MediaCache.localFile(requireContext(), settings, msg.mediaFile, msg.mediaType)
        val bmp = MediaCache.displayBitmap(file, maxDim = 2048) ?: return

        val img = android.widget.ImageView(requireContext())
        img.setImageBitmap(bmp)
        img.adjustViewBounds = true

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (msg.text.isNotBlank()) msg.text else R.string.media_image_desc)
            .setView(img)
            .setPositiveButton(R.string.btn_cancel, null)
            .show()
    }

    // ---------- الصور: اختيار → ضغط → رفع → إرسال ----------

    private fun onImagePicked(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            // 1) ضغط الصورة (تصغير + JPEG) حتى يكون الرفع سريعاً
            val compressed = MediaCache.compressImage(requireContext(), uri)
            if (compressed == null || compressed.length() == 0L) {
                Toast.makeText(requireContext(), R.string.media_send_failed, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }

            // 2) رفع إلى السيرفر ثم إرسال المراسلة بمعرّف الملف
            val fileId = uploadMediaFile(compressed, "image/jpeg")
            if (fileId == null) {
                Toast.makeText(requireContext(), R.string.media_send_failed, Toast.LENGTH_SHORT)
                    .show()
                compressed.delete()
                return@launch
            }

            BudgetRepository.sendMediaMessage(MessageMedia.IMAGE, fileId)
            Toast.makeText(requireContext(), R.string.media_sent, Toast.LENGTH_SHORT).show()
            syncInBackground()
        }
    }

    /** رفع ملف وسائط إلى السيرفر (يُرجع معرّف الملف أو null عند الفشل) */
    private suspend fun uploadMediaFile(file: File, mime: String): String? {
        val settings = BudgetRepository.settingsSnapshot()
        if (settings.houseCode.isBlank() || settings.serverUrl.isBlank()) return null
        return MediaCache.upload(
            requireContext(), settings.houseCode, settings.serverUrl, file, mime
        )
    }

    // ---------- الرسائل الصوتية: تسجيل → إيقاف → رفع → إرسال ----------

    private fun onMicClicked() {
        if (recording) {
            // زر المايك أصبح زر إيقاف: نوقف التسجيل ونرسله
            stopRecordingAndSend()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startRecording() else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startRecording() {
        if (recording) return
        try {
            // ملف تسجيل مؤقت في ذاكرة التخزين المؤقت (يُرفع ثم يُحذف)
            val file = File(requireContext().cacheDir, "aud_${System.currentTimeMillis()}.m4a")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(requireContext())
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            // AAC داخل MPEG4 بجودة مضغوطة واضحة: ~30 ثانية ≈ 120 كيلوبايت فقط
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(32000)
            rec.setAudioChannels(1)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()

            recorder = rec
            recFile = file
            recStartedAt = System.currentTimeMillis()
            recording = true

            // واجهة التسجيل: شريط أحمر + مؤقّت، وزر المايك يتحول لإيقاف
            binding.recBar.visibility = View.VISIBLE
            binding.btnMic.setImageResource(R.drawable.ic_pause)
            binding.inputLayoutMessage.isEnabled = false

            recTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive && recording) {
                    val sec = (System.currentTimeMillis() - recStartedAt) / 1000
                    binding.txtRecTimer.text = MediaCache.formatDuration(sec * 1000)
                    // حد أقصى احترازي: 5 دقائق للتسجيل الواحد ثم إرسال تلقائي
                    if (sec >= 300) {
                        stopRecordingAndSend()
                        break
                    }
                    delay(500)
                }
            }
        } catch (_: Exception) {
            cleanupRecorder()
            Toast.makeText(requireContext(), R.string.media_record_failed, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun stopRecordingAndSend() {
        if (!recording) return
        val file = recFile
        val durationMs = System.currentTimeMillis() - recStartedAt
        cleanupRecorder()

        if (file == null || !file.exists() || file.length() == 0L || durationMs < 500) {
            // تسجيل فارغ أو قصير جداً: نتجاهله بهدوء
            file?.delete()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val fileId = uploadMediaFile(file, "audio/mp4")
            file.delete()
            if (fileId == null) {
                Toast.makeText(requireContext(), R.string.media_send_failed, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            BudgetRepository.sendMediaMessage(MessageMedia.AUDIO, fileId)
            Toast.makeText(requireContext(), R.string.media_sent, Toast.LENGTH_SHORT).show()
            syncInBackground()
        }
    }

    /** إلغاء التسجيل: حذف الملف وعودة الواجهة لطبيعتها */
    private fun cancelRecording() {
        val wasRecording = recording
        cleanupRecorder()
        if (wasRecording) {
            Toast.makeText(requireContext(), R.string.media_recording_cancelled, Toast.LENGTH_SHORT)
                .show()
        }
    }

    /** تنظيف كائن التسجيل وإخفاء شريط التسجيل (بلا رفع) */
    private fun cleanupRecorder() {
        recTimerJob?.cancel()
        recTimerJob = null
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        recFile?.delete()
        recFile = null
        recording = false

        if (_binding != null) {
            binding.recBar.visibility = View.GONE
            binding.btnMic.setImageResource(R.drawable.ic_mic)
            binding.inputLayoutMessage.isEnabled = true
        }
    }

    // ---------- الطلبات والرسائل النصية (كما كانت) ----------

    /** تأكيد شراء طلب: يُنشأ المصروف بالثمن الفعلي فيُخصم من الميزانية تلقائياً */
    private fun onConfirmPurchase(msg: HouseMessage, price: Double) {
        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.purchaseRequest(msg, price)
            Toast.makeText(requireContext(), R.string.purchase_done, Toast.LENGTH_SHORT).show()
            syncInBackground()
        }
    }

    /** رفض طلب شراء: تنتقل الحالة للهاتف الآخر عند المزامنة */
    private fun onRejectRequest(msg: HouseMessage) {
        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.rejectRequest(msg)
            Toast.makeText(requireContext(), R.string.reject_done, Toast.LENGTH_SHORT).show()
            syncInBackground()
        }
    }

    /** إرسال رسالة نصية عادية إلى الهاتف الآخر */
    private fun sendText() {
        val text = binding.inputMessage.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.inputMessage.setText("")

        viewLifecycleOwner.lifecycleScope.launch {
            BudgetRepository.sendTextMessage(text)
            syncInBackground()
        }
    }

    /** نافذة إرسال طلب شراء جديد: اسم المنتج + سعر مقترح اختياري */
    private fun showNewRequestDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_request, null)
        val inputName = dialogView.findViewById<TextInputEditText>(R.id.inputName)
        val inputPrice = dialogView.findViewById<TextInputEditText>(R.id.inputPrice)
        val layoutName = dialogView.findViewById<TextInputLayout>(R.id.inputLayoutName)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fab_new_request)
            .setView(dialogView)
            .setPositiveButton(R.string.send, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        // نعترض زر الإرسال حتى نتحقق من الاسم دون إغلاق النافذة عند الخطأ
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = inputName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    layoutName.error = getString(R.string.err_request_name)
                    return@setOnClickListener
                }
                val price = FormatUtils.parseAmount(inputPrice.text?.toString().orEmpty()) ?: 0.0

                dialog.dismiss()
                viewLifecycleOwner.lifecycleScope.launch {
                    BudgetRepository.sendPurchaseRequest(name, price)
                    Toast.makeText(requireContext(), R.string.request_sent, Toast.LENGTH_SHORT)
                        .show()
                    syncInBackground()
                }
            }
        }
        dialog.show()
    }

    /** مزامنة فورية صامتة في الخلفية: تصل المراسلة للهاتف الآخر خلال ثوانٍ */
    private fun syncInBackground() {
        (requireActivity().application as HouseBudgetApp).appScope.launch {
            // رفع لحظي للمراسلة المرسلة + مزامنة كاملة (مصروف الطلب المشترى وغيره)
            BudgetRepository.pushDirtyMessages()
            BudgetRepository.syncNow()
        }
    }

    override fun onResume() {
        super.onResume()
        // المحادثة مفتوحة: استقصاء كل ثانيتين + بلا إشعارات مزدوجة (الرسائل أمامه)
        SyncForegroundService.chatOpen = true
        // كل المراسلات الظاهرة الآن مقروءة: شارة الفقاعة العائمة تُصفّر وتختفي
        ChatHeadManager.onMessagesScreenVisibility(true)
        // جلب فوري لأي رسائل وصلت قبل فتح الشاشة
        (requireActivity().application as HouseBudgetApp).appScope.launch {
            BudgetRepository.pullNewMessages()
        }
    }

    override fun onPause() {
        super.onPause()
        SyncForegroundService.chatOpen = false
        // الخروج من شاشة المراسلات: ما رآه المستخدم مقروء والفقاعة تعود للظهور
        ChatHeadManager.onMessagesScreenVisibility(false)
        // لا رسالة صوتية تسجَّل ولا تعمل أثناء مغادرة الشاشة
        if (recording) cancelRecording()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (recording) cancelRecording()
        AudioPlayer.stop()
        mediaJobs.clear()
        _binding = null
    }
}

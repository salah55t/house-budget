package dz.housebudget.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dz.housebudget.app.HouseBudgetApp
import dz.housebudget.app.R
import dz.housebudget.app.data.HouseMessage
import dz.housebudget.app.data.MessageStatus
import dz.housebudget.app.data.MessageTypes
import dz.housebudget.app.databinding.FragmentMessagesBinding
import dz.housebudget.app.repo.BudgetRepository
import dz.housebudget.app.util.FormatUtils
import kotlinx.coroutines.launch

/**
 * شاشة المراسلات: قسمان عبر مفتاح تبديل علوي —
 * 1) طلبات الشراء: قائمة انتظار الشراء بين الهاتفين؛ تحديد الطلب يُظهر خانة
 *    الثمن، وتأكيده يُنشئ مصروفاً فيُخصم من الميزانية تلقائياً.
 * 2) الرسائل النصية: محادثة نصية بسيطة بين الهاتفين.
 * كل شيء ينتقل عبر سيرفر المزامنة بنفس دورة الدقيقة الواحدة (ومزامنة فورية عند الإرسال).
 */
class MessagesFragment : Fragment() {

    private var _binding: FragmentMessagesBinding? = null
    private val binding get() = _binding!!
    private lateinit var requestsAdapter: PurchaseRequestsAdapter
    private lateinit var chatAdapter: ChatAdapter

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
        binding.rvChat.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChat.adapter = chatAdapter

        // التبديل بين القسمين: طلبات الشراء / الرسائل النصية
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val requestsTab = checkedId == R.id.btnTabRequests
            binding.layoutRequests.visibility = if (requestsTab) View.VISIBLE else View.GONE
            binding.layoutTexts.visibility = if (requestsTab) View.GONE else View.VISIBLE
        }

        binding.fabNewRequest.setOnClickListener { showNewRequestDialog() }
        binding.btnSend.setOnClickListener { sendText() }

        // تدفق حي لكل المراسلات: يُقسم إلى طلبات ورسائل ويحدّث القسمين تلقائياً
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
            BudgetRepository.syncNow()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

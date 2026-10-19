package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemFollowRequestBinding
import com.unrealstar.live.databinding.SheetFollowRequestsBinding
import com.unrealstar.live.models.OfficialActor
import kotlinx.coroutines.launch

/**
 * The page the Official tab's "Follow requests" summary row opens: every
 * pending requester with Confirm / Delete, exactly like the reference
 * notifications page. Resolutions are stored server-side, so a request the
 * user handles here can never come back on a refresh.
 */
class FollowRequestsBottomSheet(
    private val pending: List<OfficialActor> = emptyList(),
    /** Fired after a resolution so the feed behind the sheet refreshes. */
    private val onChanged: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetFollowRequestsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: RequestAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetFollowRequestsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnFollowRequestsClose.setOnClickListener { dismiss() }

        adapter = RequestAdapter(
            onResolve = { actor, confirm -> resolve(actor, confirm) }
        )
        binding.rvFollowRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFollowRequests.adapter = adapter
        adapter.submit(pending)
    }

    private fun resolve(actor: OfficialActor, confirm: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (confirm) UnrealStarApiClient.confirmFollowRequest(actor.user_id)
            else UnrealStarApiClient.deleteFollowRequest(actor.user_id)
            if (_binding == null) return@launch
            result.fold(
                onSuccess = {
                    adapter.remove(actor.user_id)
                    onChanged?.invoke()
                    if (adapter.itemCount == 0) dismiss()
                },
                onFailure = {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.inbox_follow_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                },
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.rvFollowRequests.adapter = null
        _binding = null
    }

    companion object {
        const val TAG = "FollowRequestsBottomSheet"
    }

    private class RequestAdapter(
        private val onResolve: (OfficialActor, Boolean) -> Unit
    ) : RecyclerView.Adapter<RequestAdapter.RequestViewHolder>() {

        private val items = mutableListOf<OfficialActor>()

        fun submit(next: List<OfficialActor>) {
            val oldSize = items.size
            items.clear()
            items.addAll(next)
            notifyListReplaced(oldSize, items.size)
        }

        fun remove(userId: Long) {
            val index = items.indexOfFirst { it.user_id == userId }
            if (index >= 0) {
                items.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RequestViewHolder(
            ItemFollowRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onResolve
        )

        override fun onBindViewHolder(holder: RequestViewHolder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount() = items.size

        class RequestViewHolder(
            private val binding: ItemFollowRequestBinding,
            private val onResolve: (OfficialActor, Boolean) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(actor: OfficialActor) {
                binding.tvRequestName.text = actor.nickname
                Glide.with(binding.ivRequestAvatar)
                    .load(UnrealStarApiClient.localise(actor.avatar_url))
                    .circleCrop()
                    .placeholder(R.drawable.bg_live_avatar)
                    .into(binding.ivRequestAvatar)
                binding.btnRequestConfirm.setOnClickListener { onResolve(actor, true) }
                binding.btnRequestDelete.setOnClickListener { onResolve(actor, false) }
            }
        }
    }
}

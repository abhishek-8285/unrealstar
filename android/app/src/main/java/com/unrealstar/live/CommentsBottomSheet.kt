package com.unrealstar.live

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.toColorInt
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemCommentBinding
import com.unrealstar.live.databinding.SheetCommentsBinding
import com.unrealstar.live.models.CommentRow
import com.unrealstar.live.models.MomentComment
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/**
 * Comment thread for one moment. One level of replies, likes on comments, and
 * real server state throughout.
 */
class CommentsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCommentsBinding? = null
    private val binding get() = _binding!!
    private val adapter = CommentAdapter(
        onReply = ::startReply,
        onToggleLike = ::toggleCommentLike,
        onOpenProfile = { comment ->
            ProfileBottomSheet(
                userId = comment.author_id,
                fallbackNickname = comment.author_nickname
            ).show(parentFragmentManager, ProfileBottomSheet.TAG)
        }
    )

    private val momentId: String get() = requireArguments().getString(ARG_MOMENT_ID).orEmpty()

    /** Non-null while composing a reply to that comment. */
    private var replyParent: MomentComment? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCommentsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvComments.layoutManager = LinearLayoutManager(requireContext())
        binding.rvComments.adapter = adapter
        binding.btnSendComment.setOnClickListener { submit() }
        binding.btnCancelReply.setOnClickListener { clearReply() }

        load()
    }

    private fun startReply(parent: MomentComment) {
        replyParent = parent
        binding.tvReplyingTo.text = getString(R.string.replying_to, parent.author_nickname)
        binding.replyBar.isVisible = true
        binding.etComment.requestFocus()
    }

    private fun clearReply() {
        replyParent = null
        binding.replyBar.isVisible = false
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = UnrealStarApiClient.fetchComments(momentId)
            val current = _binding ?: return@launch
            adapter.submit(buildRows(list.comments))
            current.tvEmpty.isVisible = list.comments.isEmpty()
            current.rvComments.isVisible = list.comments.isNotEmpty()
        }
    }

    /**
     * Flattens a one-level thread into display order: each top-level comment
     * immediately followed by its replies, with the reply count on the parent.
     */
    private fun buildRows(comments: List<MomentComment>): List<CommentRow> {
        val repliesByParent = comments
            .filter { it.parent_id != null }
            .groupBy { it.parent_id }

        val rows = mutableListOf<CommentRow>()
        comments.filter { it.parent_id == null }.forEach { parent ->
            val replies = repliesByParent[parent.comment_id].orEmpty()
            rows += CommentRow(parent, isReply = false, replyCount = replies.size)
            replies.forEach { rows += CommentRow(it, isReply = true) }
        }
        return rows
    }

    private fun toggleCommentLike(comment: MomentComment) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (comment.liked_by_me) {
                UnrealStarApiClient.unlikeComment(comment.comment_id)
            } else {
                UnrealStarApiClient.likeComment(comment.comment_id)
            }
            result
                .onSuccess { updated ->
                    // Patch in place rather than refetching, so the list does
                    // not jump under the reader.
                    adapter.patchLike(updated.comment_id, updated.likes_count)
                }
                .onFailure {
                    Toast.makeText(requireContext(), R.string.action_failed, Toast.LENGTH_SHORT)
                        .show()
                }
        }
    }

    private fun submit() {
        val body = binding.etComment.text?.toString()?.trim().orEmpty()
        if (body.isEmpty()) return
        binding.btnSendComment.isEnabled = false

        val parent = replyParent
        viewLifecycleOwner.lifecycleScope.launch {
            UnrealStarApiClient.postComment(momentId, body, parent?.comment_id)
                .onSuccess { engagement ->
                    _binding?.etComment?.setText("")
                    clearReply()
                    load()
                    parentFragmentManager.setFragmentResult(
                        RESULT_COMMENTED,
                        bundleOf(
                            RESULT_MOMENT_ID to momentId,
                            RESULT_COMMENT_COUNT to engagement.comment_count
                        )
                    )
                }
                .onFailure {
                    Toast.makeText(requireContext(), R.string.action_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            _binding?.btnSendComment?.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "comments"
        const val RESULT_COMMENTED = "comment_posted"
        const val RESULT_MOMENT_ID = "moment_id"
        const val RESULT_COMMENT_COUNT = "comment_count"
        private const val ARG_MOMENT_ID = "moment_id"

        fun newInstance(momentId: String, @Suppress("UNUSED_PARAMETER") initialCount: Long) =
            CommentsBottomSheet().apply { arguments = bundleOf(ARG_MOMENT_ID to momentId) }
    }
}

private class CommentAdapter(
    private val onReply: (MomentComment) -> Unit,
    private val onToggleLike: (MomentComment) -> Unit,
    private val onOpenProfile: (MomentComment) -> Unit
) : RecyclerView.Adapter<CommentAdapter.CommentViewHolder>() {

    private val rows = mutableListOf<CommentRow>()

    fun submit(next: List<CommentRow>) {
        val oldSize = rows.size
        rows.clear()
        rows.addAll(next)
        notifyListReplaced(oldSize, rows.size)
    }

    fun patchLike(commentId: String, likes: Long) {
        val index = rows.indexOfFirst { it.comment.comment_id == commentId }
        if (index == -1) return
        val row = rows[index]
        rows[index] = row.copy(
            comment = row.comment.copy(
                likes_count = likes,
                liked_by_me = likes > 0 && !row.comment.liked_by_me
            )
        )
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CommentViewHolder(binding, onReply, onToggleLike, onOpenProfile)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount() = rows.size

    class CommentViewHolder(
        private val binding: ItemCommentBinding,
        private val onReply: (MomentComment) -> Unit,
        private val onToggleLike: (MomentComment) -> Unit,
        private val onOpenProfile: (MomentComment) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: CommentRow) {
            val comment = row.comment

            // Load author avatar
            Glide.with(binding.ivAvatar)
                .load(UnrealStarApiClient.localise(comment.author_avatar_url.orEmpty()))
                .circleCrop()
                .placeholder(R.drawable.bg_live_avatar)
                .error(R.drawable.bg_live_avatar)
                .into(binding.ivAvatar)

            binding.ivAvatar.setOnClickListener { onOpenProfile(comment) }
            binding.tvAuthor.setOnClickListener { onOpenProfile(comment) }

            // Replies are inset rather than nested, matching a flat RecyclerView.
            val indent = if (row.isReply) 34 else 0
            (binding.replyIndent.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
                ?.let { lp ->
                    lp.width = indent
                    binding.replyIndent.layoutParams = lp
                }

            binding.tvAuthor.text = comment.author_nickname
            binding.tvBody.text = comment.body

            binding.tvReplyCount.isVisible = row.replyCount > 0
            if (row.replyCount > 0) {
                binding.tvReplyCount.text = itemView.context.resources.getQuantityString(
                    R.plurals.reply_count, row.replyCount, row.replyCount
                )
            }

            binding.tvCommentLikes.text = itemView.context.getString(R.string.number_plain, comment.likes_count)
            binding.tvCommentLikes.isVisible = comment.likes_count > 0
            binding.btnCommentLike.setImageResource(
                if (comment.liked_by_me) R.drawable.ic_feed_heart_filled
                else R.drawable.ic_feed_heart
            )
            binding.btnCommentLike.imageTintList = android.content.res.ColorStateList.valueOf(
                if (comment.liked_by_me) "#FF0055".toColorInt() else Color.WHITE
            )

            // Replying to a reply would make a third level; the API refuses it.
            binding.btnReply.isVisible = !row.isReply
            binding.btnReply.setOnClickListener { onReply(comment) }
            binding.btnCommentLike.setOnClickListener { onToggleLike(comment) }
        }
    }
}
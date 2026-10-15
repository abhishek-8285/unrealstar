package com.unrealstar.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.unrealstar.live.api.UnrealStarApiClient
import com.unrealstar.live.databinding.ItemDailyTaskBinding
import com.unrealstar.live.databinding.SheetDailyTasksBinding
import com.unrealstar.live.models.DailyTaskItem
import kotlinx.coroutines.launch

class DailyTasksBottomSheet(
    private val onRewardClaimed: (() -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: SheetDailyTasksBinding? = null
    private val binding get() = _binding!!
    private val taskList = mutableListOf<DailyTaskItem>()
    private lateinit var adapter: TasksAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetDailyTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        adapter = TasksAdapter()
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter

        loadTasks()
    }

    private fun loadTasks() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.rvTasks.visibility = View.GONE

            val res = UnrealStarApiClient.fetchDailyTasks()
            binding.progressBar.visibility = View.GONE
            res.onSuccess { tasks ->
                taskList.clear()
                taskList.addAll(tasks)
                binding.rvTasks.visibility = View.VISIBLE
                adapter.notifyDataSetChanged()
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Failed to load tasks: ${err.message}", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    private fun claimTask(task: DailyTaskItem, pos: Int) {
        lifecycleScope.launch {
            val res = UnrealStarApiClient.claimDailyTask(task.task_id)
            res.onSuccess { coins ->
                Toast.makeText(requireContext(), "🎉 Claimed +$coins Coins!", Toast.LENGTH_SHORT).show()
                taskList[pos] = task.copy(is_claimed = true)
                adapter.notifyItemChanged(pos)
                onRewardClaimed?.invoke()
            }.onFailure { err ->
                Toast.makeText(requireContext(), "Claim note: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class TasksAdapter : RecyclerView.Adapter<TasksAdapter.VH>() {
        inner class VH(val b: ItemDailyTaskBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemDailyTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val task = taskList[position]
            holder.b.tvTaskTitle.text = task.title
            holder.b.tvTaskReward.text = "+${task.reward_coins} Coins (${task.progress}/${task.target})"
            holder.b.pbTask.max = task.target
            holder.b.pbTask.progress = task.progress

            when {
                task.is_claimed -> {
                    holder.b.btnClaim.text = "Claimed"
                    holder.b.btnClaim.alpha = 0.4f
                    holder.b.btnClaim.setOnClickListener(null)
                }
                task.progress >= task.target -> {
                    holder.b.btnClaim.text = "Claim"
                    holder.b.btnClaim.alpha = 1.0f
                    holder.b.btnClaim.setOnClickListener {
                        val p = holder.bindingAdapterPosition
                        if (p != RecyclerView.NO_POSITION) {
                            claimTask(taskList[p], p)
                        }
                    }
                }
                else -> {
                    holder.b.btnClaim.text = "In Progress"
                    holder.b.btnClaim.alpha = 0.5f
                    holder.b.btnClaim.setOnClickListener(null)
                }
            }
        }

        override fun getItemCount() = taskList.size
    }

    companion object {
        const val TAG = "DailyTasksBottomSheet"
    }
}

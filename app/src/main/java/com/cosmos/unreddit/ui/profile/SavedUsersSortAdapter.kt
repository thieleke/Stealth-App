package com.cosmos.unreddit.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.UserSortMode
import com.cosmos.unreddit.databinding.ItemSavedUsersSortBinding

/**
 * Single item adapter meant to be used as a [androidx.recyclerview.widget.ConcatAdapter] header,
 * so that the users list keeps `item_list_content` as its fragment root.
 */
class SavedUsersSortAdapter(
    private val onSortModeChanged: (UserSortMode) -> Unit
) : RecyclerView.Adapter<SavedUsersSortAdapter.ViewHolder>() {

    var sortMode: UserSortMode = UserSortMode.ALPHABETICAL
        set(value) {
            if (field != value) {
                field = value
                notifyItemChanged(0)
            }
        }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemSavedUsersSortBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(sortMode)
    }

    inner class ViewHolder(
        private val binding: ItemSavedUsersSortBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(sortMode: UserSortMode) {
            // Clear before the programmatic check to avoid a feedback loop when the persisted
            // mode is re-emitted by the preferences flow
            binding.sortGroup.clearOnButtonCheckedListeners()

            val checkedId = when (sortMode) {
                UserSortMode.ALPHABETICAL -> R.id.sort_alphabetical
                UserSortMode.CHRONOLOGICAL -> R.id.sort_chronological
            }

            if (binding.sortGroup.checkedButtonId != checkedId) {
                binding.sortGroup.check(checkedId)
            }

            binding.sortGroup.addOnButtonCheckedListener { _, buttonId, isChecked ->
                if (isChecked) {
                    onSortModeChanged(
                        when (buttonId) {
                            R.id.sort_chronological -> UserSortMode.CHRONOLOGICAL
                            else -> UserSortMode.ALPHABETICAL
                        }
                    )
                }
            }
        }
    }
}

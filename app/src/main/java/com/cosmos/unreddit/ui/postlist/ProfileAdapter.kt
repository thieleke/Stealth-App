package com.cosmos.unreddit.ui.postlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.data.model.ProfileItem
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.databinding.ItemNewProfileHomeBinding
import com.cosmos.unreddit.databinding.ItemProfileHomeBinding

class ProfileAdapter(
    val onClickListener: (Profile) -> Unit,
    val onLongClickListener: (Profile) -> Unit,
    val onNewProfileClickListener: () -> Unit
) : ListAdapter<ProfileItem, RecyclerView.ViewHolder>(PROFILE_COMPARATOR) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            Type.PROFILE.value -> {
                ViewHolder(ItemProfileHomeBinding.inflate(inflater, parent, false))
            }
            Type.NEW_PROFILE.value -> {
                NewProfileViewHolder(ItemNewProfileHomeBinding.inflate(inflater, parent, false))
            }
            else -> throw IllegalArgumentException("Unknown type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ProfileItem.UserProfile -> (holder as ViewHolder).bind(item.profile)
            is ProfileItem.NewProfile -> {
                // Nothing to bind, the click listener is set once in the view holder
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ProfileItem.UserProfile -> Type.PROFILE.value
            is ProfileItem.NewProfile -> Type.NEW_PROFILE.value
        }
    }

    inner class ViewHolder(
        private val binding: ItemProfileHomeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: Profile) {
            binding.profile = profile
            binding.profileAvatar.setOnClickListener { onClickListener.invoke(profile) }
            binding.profileAvatar.setOnLongClickListener {
                onLongClickListener.invoke(profile)
                // Consume the event so the long press never falls through to the click listener,
                // which would switch to the profile the user is trying to delete.
                true
            }
        }
    }

    inner class NewProfileViewHolder(
        binding: ItemNewProfileHomeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.imageAdd.setOnClickListener { onNewProfileClickListener.invoke() }
        }
    }

    private enum class Type(val value: Int) {
        PROFILE(0), NEW_PROFILE(1)
    }

    companion object {
        private val PROFILE_COMPARATOR = object : DiffUtil.ItemCallback<ProfileItem>() {
            override fun areItemsTheSame(oldItem: ProfileItem, newItem: ProfileItem): Boolean {
                return if (oldItem is ProfileItem.UserProfile &&
                    newItem is ProfileItem.UserProfile
                ) {
                    oldItem.profile.id == newItem.profile.id
                } else {
                    oldItem is ProfileItem.NewProfile && newItem is ProfileItem.NewProfile
                }
            }

            override fun areContentsTheSame(oldItem: ProfileItem, newItem: ProfileItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}

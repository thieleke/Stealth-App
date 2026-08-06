package com.cosmos.unreddit.ui.profile

import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import com.cosmos.unreddit.R
import com.cosmos.unreddit.ui.common.fragment.ListFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileUsersFragment : ListFragment<ConcatAdapter>() {

    override val viewModel: ProfileViewModel by hiltNavGraphViewModels(R.id.profile)

    override val enablePullToRefresh: Boolean
        get() = true

    private lateinit var sortAdapter: SavedUsersSortAdapter
    private lateinit var usersAdapter: ProfileUsersAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateContentView()
        bindViewModel()
    }

    private fun updateContentView() {
        binding.loadingState.textEmptyData.setText(R.string.empty_data_users)

        // Update empty data view to be higher than usual
        val contentMargin = resources.getDimension(R.dimen.profile_content_margin).toInt()

        binding.loadingState.run {
            ConstraintSet().apply {
                clone(root)
                clear(textEmptyData.id, ConstraintSet.BOTTOM)
                applyTo(root)
            }

            emptyData.updateLayoutParams<MarginLayoutParams> { topMargin = contentMargin }
        }
    }

    private fun bindViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.savedUsers,
                viewModel.contentPreferences,
                viewModel.userSortMode
            ) { users, preferences, sortMode ->
                sortAdapter.sortMode = sortMode
                usersAdapter.run {
                    contentPreferences = preferences
                    submitList(users)
                }
                binding.loadingState.run {
                    emptyData.isVisible = users.isEmpty()
                    textEmptyData.isVisible = users.isEmpty()
                }
            }.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usersLoading
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { isLoading ->
                    binding.pullRefresh.setRefreshing(isLoading)
                    if (!isLoading) {
                        setRefreshTime(System.currentTimeMillis())
                    }
                }
        }
    }

    override fun onRefresh() {
        viewModel.refreshUsers()
    }

    override fun createAdapter(): ConcatAdapter {
        sortAdapter = SavedUsersSortAdapter { viewModel.setUserSortMode(it) }
        usersAdapter = ProfileUsersAdapter(this, this)

        return ConcatAdapter(sortAdapter, usersAdapter)
    }
}

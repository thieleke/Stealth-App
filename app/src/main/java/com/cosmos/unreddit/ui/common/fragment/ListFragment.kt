package com.cosmos.unreddit.ui.common.fragment

import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.Adapter
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.cosmos.unreddit.R
import com.cosmos.unreddit.databinding.ItemListContentBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.ui.common.PostDividerItemDecoration
import com.cosmos.unreddit.ui.common.widget.PullToRefreshLayout
import com.cosmos.unreddit.ui.common.widget.PullToRefreshView
import com.cosmos.unreddit.util.DateUtil
import com.cosmos.unreddit.util.extension.applyWindowInsets

abstract class ListFragment<T : Adapter<out ViewHolder>> : BaseFragment(),
    PullToRefreshLayout.OnRefreshListener {

    private var _binding: ItemListContentBinding? = null
    protected val binding get() = _binding!!

    protected lateinit var adapter: T

    protected open val showItemDecoration: Boolean = false

    protected open val enablePullToRefresh: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ItemListContentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun applyInsets(view: View) {
        // Ignore
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initRecyclerView()
        binding.pullRefresh.enablePullToRefresh = enablePullToRefresh
    }

    protected open fun initRecyclerView() {
        adapter = createAdapter().apply {
            // ConcatAdapter infers its policy from its children and throws on the setter
            if (this !is ConcatAdapter) {
                stateRestorationPolicy = PREVENT_WHEN_EMPTY
            }
        }

        binding.listContent.apply {
            applyWindowInsets(left = false, top = false, right = false)
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ListFragment.adapter
            if (showItemDecoration) {
                addItemDecoration(PostDividerItemDecoration(context))
            }
        }

        binding.pullRefresh.setOnRefreshListener(this)
    }

    /**
     * @return the scroll state of the list, to give back to [restoreListState] once the list is
     * populated again
     */
    protected fun saveListState(): Parcelable? {
        return _binding?.listContent?.layoutManager?.onSaveInstanceState()
    }

    /**
     * Restores the scroll state of the list, provided it has content to scroll.
     *
     * @return true when the state was restored and can be discarded
     */
    protected fun restoreListState(state: Parcelable?): Boolean {
        val layoutManager = _binding?.listContent?.layoutManager ?: return false

        if (state == null || adapter.itemCount == 0) return false

        layoutManager.onRestoreInstanceState(state)

        return true
    }

    /**
     * Makes sure the pending layout of the list actually gets a traversal.
     *
     * The list of a tab lives inside a ViewPager2, which holds its pages in a RecyclerView of its
     * own. A RecyclerView drops the `requestLayout()` calls it receives while it is laying out or
     * scrolling: it only notes them and then runs `dispatchLayout()`, which does not re-measure
     * its children. When that happens the page keeps a layout request that nothing will honour,
     * and the items submitted afterwards stay invisible until a touch forces a scroll pass —
     * every later `requestLayout()` stops at the first ancestor already waiting for one.
     *
     * Re-requesting from the first ancestor that is *not* waiting rebuilds the chain up to the
     * ViewRootImpl, which schedules the traversal.
     */
    protected fun ensureLayoutPass() {
        val list = _binding?.listContent ?: return

        if (!list.isLayoutRequested) return

        var parent: View? = list.parent as? View
        while (parent != null && parent.isLayoutRequested) {
            parent = parent.parent as? View
        }

        parent?.requestLayout()
    }

    protected fun setRefreshTime(timeInMillis: Long) {
        val time = getString(R.string.last_refresh, DateUtil.getLocalizedTime(timeInMillis))
        (binding.pullRefresh.refreshView as? PullToRefreshView)?.setLastRefresh(time)
    }

    protected abstract fun createAdapter(): T

    protected fun showRetryBar() {
        if (!binding.loadingState.infoRetry.isVisible) {
            binding.loadingState.infoRetry.show()
        }
    }

    override fun onRefresh() {
        // Not implemented
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

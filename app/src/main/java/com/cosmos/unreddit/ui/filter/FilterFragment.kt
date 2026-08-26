package com.cosmos.unreddit.ui.filter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.cosmos.unreddit.data.model.PostTypeFilter
import com.cosmos.unreddit.databinding.FragmentFilterBinding
import com.cosmos.unreddit.util.extension.serializable
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class FilterFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentFilterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initChoices()
    }

    private fun initChoices() {
        val filter = arguments?.serializable<PostTypeFilter>(BUNDLE_KEY_FILTER)
            ?: PostTypeFilter.ALL
        when (filter) {
            PostTypeFilter.ALL -> binding.chipAll.isChecked = true
            PostTypeFilter.TEXT -> binding.chipText.isChecked = true
            PostTypeFilter.MEDIA -> binding.chipMedia.isChecked = true
            PostTypeFilter.IMAGE -> binding.chipImages.isChecked = true
            PostTypeFilter.VIDEO -> binding.chipVideos.isChecked = true
        }

        binding.groupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                setChoice()
            }
        }
    }

    private fun getChoice(): PostTypeFilter {
        return when (binding.groupFilter.checkedChipId) {
            binding.chipAll.id -> PostTypeFilter.ALL
            binding.chipText.id -> PostTypeFilter.TEXT
            binding.chipMedia.id -> PostTypeFilter.MEDIA
            binding.chipImages.id -> PostTypeFilter.IMAGE
            binding.chipVideos.id -> PostTypeFilter.VIDEO
            else -> PostTypeFilter.ALL
        }
    }

    private fun setChoice() {
        setFragmentResult(
            REQUEST_KEY_FILTER,
            bundleOf(BUNDLE_KEY_FILTER to getChoice())
        )
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "FilterFragment"

        const val REQUEST_KEY_FILTER = "REQUEST_KEY_FILTER"

        const val BUNDLE_KEY_FILTER = "BUNDLE_KEY_FILTER"

        fun show(fragmentManager: FragmentManager, filter: PostTypeFilter) {
            FilterFragment().apply {
                arguments = bundleOf(BUNDLE_KEY_FILTER to filter)
            }.show(fragmentManager, TAG)
        }
    }
}

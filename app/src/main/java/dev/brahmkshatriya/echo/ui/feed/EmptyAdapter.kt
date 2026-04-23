package dev.brahmkshatriya.echo.ui.feed

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import dev.brahmkshatriya.echo.databinding.ItemShelfEmptyBinding
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimLoadStateAdapter
import dev.brahmkshatriya.echo.utils.ui.scrolling.ScrollAnimViewHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EmptyAdapter : ScrollAnimLoadStateAdapter<EmptyAdapter.ViewHolder>(), GridAdapter {
    class ViewHolder(val binding: ItemShelfEmptyBinding) : ScrollAnimViewHolder(binding.root) {
        private var showEmptyJob: Job? = null

        fun bind(loadState: LoadState) {
            showEmptyJob?.cancel()
            if (loadState is LoadState.Loading) {
                binding.emptyLayout.isVisible = false
                binding.loadingIndicator.isVisible = true
                showEmptyJob = itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                    delay(3000)
                    if (binding.loadingIndicator.isVisible) {
                        binding.loadingIndicator.isVisible = false
                        binding.emptyLayout.isVisible = true
                    }
                }
            } else {
                binding.emptyLayout.isVisible = false
                binding.loadingIndicator.isVisible = false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemShelfEmptyBinding.inflate(inflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, loadState: LoadState) {
        super.onBindViewHolder(holder, loadState)
        holder.bind(loadState)
    }

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
}
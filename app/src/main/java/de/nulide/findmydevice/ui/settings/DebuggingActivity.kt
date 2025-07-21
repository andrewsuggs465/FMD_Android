package de.nulide.findmydevice.ui.settings

import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.nulide.findmydevice.R
import de.nulide.findmydevice.databinding.ActivityDebuggingBinding
import de.nulide.findmydevice.ui.FmdActivity
import de.nulide.findmydevice.ui.UiUtil.Companion.setupEdgeToEdgeAppBar
import de.nulide.findmydevice.ui.UiUtil.Companion.setupEdgeToEdgeScrollView
import de.nulide.findmydevice.utils.Utils
import kotlinx.coroutines.launch


class DebuggingActivity : FmdActivity() {

    private lateinit var viewBinding: ActivityDebuggingBinding

    private val viewModel: DebuggingViewModel by viewModels { DebuggingViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewBinding = ActivityDebuggingBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        setupEdgeToEdgeAppBar(findViewById(R.id.appBar))
        setupEdgeToEdgeScrollView(findViewById(R.id.scrollView))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentJobs.collect {
                    setupJobs(it, viewBinding.textViewCurrentJobs)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recentJobs.collect {
                    setupJobs(it, viewBinding.textViewRecentJobs)
                }
            }
        }
    }

    private fun setupJobs(jobs: List<JobInfoExt>, view: TextView) {
        val jobInfoString = jobs.joinToString("\n\n") { it.toInfoString() }

        // XXX: A RecyclerView would be cleaner, but a TextView is quicker and good enough for debugging
        view.text = jobInfoString

        view.setOnLongClickListener {
            Utils.copyToClipboard(it.context, "", jobInfoString)
            return@setOnLongClickListener true
        }
    }
}

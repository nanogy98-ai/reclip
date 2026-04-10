package io.nanogy.reclip

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import io.nanogy.reclip.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel by viewModels<MainViewModel>()
    private val clipAdapter by lazy {
        ClipAdapter(
            onAction = ::handleClipAction,
            onFormatSelected = viewModel::selectFormat,
        )
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStorageBanner() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        bindState()
        consumeIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStorageBanner()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingIntent(intent)
    }

    private fun setupUi() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = clipAdapter
        }

        applyHeroGradient()

        binding.modeToggle.check(binding.videoButton.id)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == binding.audioButton.id) {
                DownloadMode.AUDIO
            } else {
                DownloadMode.VIDEO
            }
            viewModel.setMode(mode)
        }

        binding.fetchButton.setOnClickListener {
            viewModel.fetchUrls(binding.urlInput.text?.toString().orEmpty())
        }

        binding.pasteButton.setOnClickListener {
            pasteFromClipboard()
        }

        binding.downloadAllButton.setOnClickListener {
            if (ensureDownloadAccess()) {
                viewModel.downloadAll()
            }
        }

        binding.permissionButton.setOnClickListener {
            requestStorageAccess()
        }
    }

    private fun bindState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val hasClips = state.clips.isNotEmpty()

                    clipAdapter.mode = state.mode
                    clipAdapter.submitList(state.clips)

                    binding.fetchProgress.isVisible = state.isFetching
                    binding.emptyStateContainer.isVisible = !state.isFetching && !hasClips
                    binding.recentHeaderRow.isVisible = hasClips
                    binding.recyclerView.isVisible = hasClips
                    binding.downloadAllButton.isVisible =
                        state.clips.count { it.status == ClipStatus.READY || it.status == ClipStatus.ERROR } > 1
                }
            }
        }
    }

    private fun handleClipAction(item: ClipItem) {
        when (item.status) {
            ClipStatus.LOADING_INFO -> Unit
            ClipStatus.READY,
            ClipStatus.ERROR,
            -> {
                if (ensureDownloadAccess()) {
                    viewModel.downloadClip(item.id)
                }
            }

            ClipStatus.INFO_ERROR -> viewModel.retryFetch(item.id)
            ClipStatus.DOWNLOADING -> viewModel.cancelDownload(item.id)
            ClipStatus.DONE -> openDownloadedFile(item.downloadedPath)
        }
    }

    private fun openDownloadedFile(path: String?) {
        if (path.isNullOrBlank()) {
            Toast.makeText(this, R.string.status_finished, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this, "File no longer exists at $path", Toast.LENGTH_LONG).show()
                return
            }
            FileProvider.getUriForFile(this, "$packageName.provider", file)
        }

        val extension = if (path.startsWith("content://")) {
            contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.lowercase()
                .orEmpty()
        } else {
            File(path).extension.lowercase()
        }
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: contentResolver.getType(uri)
            ?: "application/octet-stream"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, path, Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureDownloadAccess(): Boolean {
        refreshStorageBanner()
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return false
        }

        maybeRequestNotificationPermission()
        return true
    }

    private fun hasStorageAccess(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
            }

            else -> true
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M until Build.VERSION_CODES.Q) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshStorageBanner() {
        val needsAccess = !hasStorageAccess()
        binding.permissionCard.isVisible = needsAccess
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val pastedText = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()

        if (pastedText.isNullOrBlank()) {
            Toast.makeText(this, R.string.paste_empty, Toast.LENGTH_SHORT).show()
            return
        }

        binding.urlInput.setText(pastedText)
        binding.urlInput.setSelection(binding.urlInput.text?.length ?: 0)
    }

    private fun applyHeroGradient() {
        binding.mainTitle.doOnLayout {
            binding.mainTitle.paint.shader = LinearGradient(
                0f,
                0f,
                0f,
                binding.mainTitle.height.toFloat(),
                intArrayOf(
                    ContextCompat.getColor(this, R.color.reclip_purple_accent),
                    ContextCompat.getColor(this, R.color.reclip_cyan_bright),
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            binding.mainTitle.invalidate()
        }
    }

    private fun consumeIncomingIntent(intent: Intent?) {
        val incomingUrl = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.trim()

        if (!incomingUrl.isNullOrBlank() && binding.urlInput.text.isNullOrBlank()) {
            binding.urlInput.setText(incomingUrl)
        }
    }
}

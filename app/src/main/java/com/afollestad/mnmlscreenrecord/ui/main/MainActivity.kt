/**
 * Designed and developed by Aidan Follestad (@afollestad)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.afollestad.mnmlscreenrecord.ui.main

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.assent.Permission.WRITE_EXTERNAL_STORAGE
import com.afollestad.assent.askForPermissions
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.afollestad.mnmlscreenrecord.R
import com.afollestad.mnmlscreenrecord.common.misc.toUri
import com.afollestad.mnmlscreenrecord.common.misc.toast
import com.afollestad.mnmlscreenrecord.common.rx.attachLifecycle
import com.afollestad.mnmlscreenrecord.common.view.onDebouncedClick
import com.afollestad.mnmlscreenrecord.common.view.onScroll
import com.afollestad.mnmlscreenrecord.donate.DonateClient
import com.afollestad.mnmlscreenrecord.engine.permission.OverlayExplanationCallback
import com.afollestad.mnmlscreenrecord.engine.permission.OverlayExplanationDialog
import com.afollestad.mnmlscreenrecord.engine.permission.StorageExplanationCallback
import com.afollestad.mnmlscreenrecord.engine.permission.StorageExplanationDialog
import com.afollestad.mnmlscreenrecord.engine.recordings.Recording
import com.afollestad.mnmlscreenrecord.engine.service.BackgroundService.Companion.PERMISSION_DENIED
import com.afollestad.mnmlscreenrecord.theming.DarkModeSwitchActivity
import com.afollestad.mnmlscreenrecord.ui.about.AboutDialog
import com.afollestad.mnmlscreenrecord.ui.settings.SettingsActivity
import com.afollestad.mnmlscreenrecord.views.asBackgroundTint
import com.afollestad.mnmlscreenrecord.views.asEnabled
import com.afollestad.mnmlscreenrecord.views.asIcon
import com.afollestad.mnmlscreenrecord.views.asText
import com.afollestad.mnmlscreenrecord.views.asVisibility
import kotlinx.android.synthetic.main.activity_main.fab
import kotlinx.android.synthetic.main.activity_main.list
import kotlinx.android.synthetic.main.include_appbar.toolbar
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlinx.android.synthetic.main.activity_main.empty_view as emptyView
import kotlinx.android.synthetic.main.include_appbar.app_toolbar as appToolbar

/** @author Aidan Follestad (afollestad) */
class MainActivity : DarkModeSwitchActivity(),
    StorageExplanationCallback,
    OverlayExplanationCallback {
  companion object {
    private const val DRAW_OVER_OTHER_APP_PERMISSION = 68
    private const val STORAGE_PERMISSION = 64
  }

  private val viewModel by viewModel<MainViewModel>()
  private val donateClient by inject<DonateClient>()

  private lateinit var adapter: RecordingAdapter

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setupToolbar()
    setupGrid()

    fab.onDebouncedClick { viewModel.fabClicked() }

    lifecycle.run {
      addObserver(viewModel)
      addObserver(donateClient)
    }

    viewModel.onRecordings()
        .observe(this, Observer { adapter.set(it) })
    viewModel.onEmptyViewVisibility()
        .asVisibility(this, emptyView)
    viewModel.onFabColorRes()
        .asBackgroundTint(this, fab)
    viewModel.onFabIconRes()
        .asIcon(this, fab)
    viewModel.onFabTextRes()
        .asText(this, fab)
    viewModel.onFabEnabled()
        .asEnabled(this, fab)

    viewModel.onNeedOverlayPermission()
        .subscribe { OverlayExplanationDialog.show(this@MainActivity) }
        .attachLifecycle(this)
    viewModel.onNeedStoragePermission()
        .subscribe { StorageExplanationDialog.show(this@MainActivity) }
        .attachLifecycle(this)

    checkForMediaProjectionAvailability()
  }

  override fun onShouldAskForStoragePermission() {
    askForPermissions(WRITE_EXTERNAL_STORAGE, requestCode = STORAGE_PERMISSION) { res ->
      if (!res.isAllGranted(WRITE_EXTERNAL_STORAGE)) {
        sendBroadcast(Intent(PERMISSION_DENIED))
        toast(R.string.permission_denied_note)
      } else {
        viewModel.permissionGranted()
      }
    }
  }

  override fun onShouldAskForOverlayPermission() {
    val intent = Intent(
        ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:$packageName".toUri()
    )
    startActivityForResult(
        intent,
        DRAW_OVER_OTHER_APP_PERMISSION
    )
  }

  private fun setupToolbar() = toolbar.run {
    inflateMenu(R.menu.main)

    setOnMenuItemClickListener { item ->
      when (item.itemId) {
        R.id.support_me -> {
          supportMe()
        }
        R.id.about -> {
          AboutDialog.show(this@MainActivity)
        }
        R.id.settings -> {
          startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
      }
      true
    }
  }

  private fun setupGrid() {
    adapter = RecordingAdapter { recording, longClick ->
      if (longClick) {
        showRecordingOptions(recording)
      } else {
        openRecording(recording)
      }
    }

    list.layoutManager = LinearLayoutManager(this)
    list.adapter = adapter
    list.onScroll { invalidateToolbarElevation(it) }
  }

  private fun invalidateToolbarElevation(scrollY: Int) {
    if (scrollY > (toolbar.measuredHeight / 2)) {
      appToolbar.elevation = resources.getDimension(R.dimen.raised_toolbar_elevation)
    } else {
      appToolbar.elevation = 0f
    }
  }

  override fun onResume() {
    super.onResume()
    invalidateToolbarElevation(list.computeVerticalScrollOffset())
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?
  ) {
    super.onActivityResult(requestCode, resultCode, data)
    viewModel.permissionGranted()
  }

  private fun openRecording(recording: Recording) {
    startActivity(Intent(ACTION_VIEW).apply {
      setDataAndType(recording.toUri(), "video/*")
    })
  }

  private fun shareRecording(recording: Recording) {
    startActivity(Intent(Intent.ACTION_SEND).apply {
      setDataAndType(recording.toUri(), "video/*")
    })
  }

  private fun showRecordingOptions(recording: Recording) {
    MaterialDialog(this).show {
      title(text = recording.name)
      listItems(R.array.recording_options_dialog) { _, index, _ ->
        when (index) {
          0 -> shareRecording(recording)
          1 -> viewModel.deleteRecording(recording)
        }
      }
    }
  }

  private fun checkForMediaProjectionAvailability() {
    try {
      Class.forName("android.media.projection.MediaProjectionManager")
    } catch (e: ClassNotFoundException) {
      MaterialDialog(this).show {
        title(text = "Device Unsupported")
        message(
            text = "Your device lacks support for MediaProjectionManager. Either the manufacturer " +
                "of your device left it out, or you are using an emulator."
        )
        positiveButton(android.R.string.ok) { finish() }
        cancelOnTouchOutside(false)
        cancelable(false)
      }
    }
  }

  private fun supportMe() {
    donateClient.onError()
        .subscribe {
          MaterialDialog(this).show {
            title(R.string.support_me_failed)
            message(text = it.message)
            positiveButton(android.R.string.ok)
            cancelOnTouchOutside(false)
            cancelable(false)
          }
        }
        .attachLifecycle(this)
    donateClient.onReady()
        .subscribe { options ->
          val optionNames = options.map {
            it.title.replace(" (MNML Screen Recorder)", "")
          }
          MaterialDialog(this).show {
            title(R.string.support_me)
            message(R.string.support_me_message, html = true, lineHeightMultiplier = 1.4f)
            listItemsSingleChoice(items = optionNames) { _, index, _ ->
              val selection = options[index]
              if (donateClient.makePurchase(this@MainActivity, selection)) {
                toast(R.string.thank_you)
              }
            }
            positiveButton(R.string.next)
          }
        }
        .attachLifecycle(this)
  }
}

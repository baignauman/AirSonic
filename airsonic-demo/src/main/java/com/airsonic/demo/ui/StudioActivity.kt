// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.media.projection.MediaProjectionConfig
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.airsonic.demo.MainActivity
import com.airsonic.demo.ui.theme.AirSonicTheme
import com.airsonic.demo.ui.theme.AuroraBackground

/** 产品 UI 入口（Compose / Aurora）：托管导航 + 桥接系统能力（投屏授权/文件选择）。 */
class StudioActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val recordPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchProjection()
            else {
                CastEngine.phase.value = CastPhase.ERROR
                CastEngine.statusLine.value = if (L10n.lang.value == Lang.EN) "Microphone permission denied" else "未授予录音权限"
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CastEngine.startSystemAudioCast(this, result.resultCode, result.data!!)
            } else {
                CastEngine.statusLine.value = if (L10n.lang.value == Lang.EN) "Screen-capture canceled" else "已取消投屏授权"
            }
        }

    /** 应用内屏幕镜像（DLNA）：音轨需要 RECORD_AUDIO（拒绝则降级纯画面镜像）。 */
    private val mirrorRecordPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 不管是否授予都继续发起录屏授权；有无权限决定 TS 里带不带 AAC 音轨
            mirrorProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

    /** 应用内屏幕镜像（DLNA）：录屏+系统声音（AudioPlaybackCapture→AAC 音轨，声画同投）。 */
    private val mirrorProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CastEngine.startScreenMirrorCast(this, result.resultCode, result.data!!)
            } else {
                CastEngine.statusLine.value = if (L10n.lang.value == Lang.EN) "Screen-capture canceled" else "已取消投屏授权"
            }
        }

    /** HTTP 流输出：同一套 录音权限→录屏授权 链，只是不推设备、暴露直播地址。 */
    private val httpStreamRecordPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) httpStreamProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            else {
                CastEngine.phase.value = CastPhase.ERROR
                CastEngine.statusLine.value = if (L10n.lang.value == Lang.EN) "Microphone permission denied" else "未授予录音权限"
            }
        }

    private val httpStreamProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CastEngine.startHttpStreamCast(this, result.resultCode, result.data!!)
            } else {
                CastEngine.statusLine.value = if (L10n.lang.value == Lang.EN) "Screen-capture canceled" else "已取消投屏授权"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L10n.load(this)   // 载入已保存语言
        CastEngine.loadPrefs(this)   // 载入「强制 ALAC」等开关
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val actions = CastActions(
            requestSystemAudioCast = { chooseAudioApp() },
            requestScreenMirrorCast = {
                // 已有录音权限直接录屏；没有先补权限（拒绝也继续，降级纯画面）
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) mirrorProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                else mirrorRecordPerm.launch(Manifest.permission.RECORD_AUDIO)
            },
            openDebug = { startActivity(Intent(this, MainActivity::class.java)) },
            requestHttpStream = {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) httpStreamProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                else httpStreamRecordPerm.launch(Manifest.permission.RECORD_AUDIO)
            },
        )

        setContent {
            AirSonicTheme {
                AuroraBackground {
                    AppNav(actions)
                }
            }
        }
    }

    private fun chooseAudioApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        if (apps.isEmpty()) {
            CastEngine.statusLine.value = "No apps found"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose app audio")
            .setItems(labels) { _, index ->
                CastEngine.selectedAudioAppUid.value = apps[index].activityInfo.applicationInfo.uid
                CastEngine.selectedAudioAppName.value = labels[index]
                ensurePermThenProjection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensurePermThenProjection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) launchProjection()
        else recordPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchProjection() {
        // Audio-only capture needs the user's consent, but does not need Android's
        // second app/window chooser. Request the default display on Android 14+.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { CastEngine.stopDiscovery() }
    }
}
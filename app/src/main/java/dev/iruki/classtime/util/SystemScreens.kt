package dev.iruki.classtime.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import dev.iruki.classtime.R
import dev.iruki.classtime.audio.RecordingStorage
import java.io.File

/**
 * 설정 화면의 각 줄이 실제로 여는 시스템 화면. 열 수 있는 것부터 차례로 시도하고,
 * 기기(제조사 파일 앱 등)가 받아 주지 않으면 다음 후보로 넘어간다.
 */
object SystemScreens {

    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /**
     * 파일 앱에서 녹음 폴더(Music/ClassTime)를 연다. 어느 것도 받아 주지 않으면 폴더 위치를 알린다.
     *
     * 예전에는 남의 uri(파일 앱의 문서 uri)에 읽기 권한 부여 플래그를 붙여, 시작할 때마다
     * SecurityException 이 나서 아무것도 열리지 않았다. 권한을 넘길 uri 가 아니므로 붙이지 않는다.
     */
    fun openRecordingsFolder(context: Context): Boolean {
        val opened = folderIntents().any { launch(context, it) }
        if (!opened) {
            val path = "${Environment.DIRECTORY_MUSIC}/${RecordingStorage.ROOT}"
            Toast.makeText(context, context.getString(R.string.folder_open_failed, path), Toast.LENGTH_LONG).show()
        }
        return opened
    }

    /** 녹음 폴더를 여는 후보들. 앞에서부터 시도한다. */
    internal fun folderIntents(): List<Intent> {
        val folderId = "primary:${Environment.DIRECTORY_MUSIC}/${RecordingStorage.ROOT}"
        return buildList {
            // 1) 삼성 내 파일: 경로를 바로 연다(국내 기기 대부분).
            @Suppress("DEPRECATION")
            val path = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), RecordingStorage.ROOT)
            add(
                Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
                    .setPackage("com.sec.android.app.myfiles")
                    .putExtra("samsung.myfiles.intent.extra.START_PATH", path.absolutePath)
            )
            // 2) 폴더 자체를 보여 달라고 한다. Files by Google·AOSP 파일 앱이 받는다.
            add(
                Intent(Intent.ACTION_VIEW).setDataAndType(
                    DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, folderId),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                )
            )
            // 3) 일부 제조사 앱은 트리 uri 만 받는다.
            add(
                Intent(Intent.ACTION_VIEW).setDataAndType(
                    DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE, folderId),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                )
            )
            // 4) 내장 저장소 첫 화면이라도.
            add(
                Intent(Intent.ACTION_VIEW).setDataAndType(
                    DocumentsContract.buildRootUri(EXTERNAL_STORAGE, "primary"),
                    DocumentsContract.Root.MIME_TYPE_ITEM,
                )
            )
            // 5) 기본 파일 앱을 그냥 연다.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES))
            }
        }
    }

    fun openAppDetails(context: Context): Boolean = launch(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    /** 앱 알림 설정. 채널을 주면 그 채널 화면으로. */
    fun openNotificationSettings(context: Context, channelId: String? = null): Boolean {
        val intent = if (channelId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return launch(context, intent) || openAppDetails(context)
    }

    /** 이미 해결된 권한 줄을 눌렀을 때: 그 권한을 바꾸는 화면. */
    fun openFor(context: Context, id: SetupId): Boolean = when (id) {
        SetupId.NOTIFICATIONS -> openNotificationSettings(context)
        SetupId.MICROPHONE -> openAppDetails(context)
        SetupId.BATTERY -> launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
            openAppDetails(context)
        else -> AppPermissions.settingsIntent(context, id)?.let { launch(context, it) } == true ||
            launch(context, AppPermissions.fallbackIntent(context, id))
    }

    fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

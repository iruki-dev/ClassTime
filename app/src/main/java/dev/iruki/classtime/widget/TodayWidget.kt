package dev.iruki.classtime.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.iruki.classtime.MainActivity
import dev.iruki.classtime.R
import dev.iruki.classtime.data.ClassTimeRepository
import dev.iruki.classtime.data.Session
import dev.iruki.classtime.service.RecordingService
import dev.iruki.classtime.ui.theme.DarkScheme
import dev.iruki.classtime.ui.theme.LightScheme
import dev.iruki.classtime.util.AppLog
import dev.iruki.classtime.util.TimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): ClassTimeRepository
}

/**
 * 홈 화면 ‘오늘’ 위젯. 지금·다음 수업과 녹음 상태를 보여 주고, 버튼 하나로 녹음을 시작·중지한다.
 *
 * 위젯 버튼으로 시작한 녹음은 앱이 백그라운드여도 마이크가 열린다(안드로이드가 위젯 상호작용을
 * ‘사용 중’ 예외로 인정한다). 그래서 대기 모드가 준비되지 않았을 때 가장 확실한 수동 녹음 경로다.
 */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = runCatching {
            val repo = EntryPointAccessors
                .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                .repository()
            TodayModel.of(repo.sessionsOn(), TimeUtils.nowMinuteOfDay(), repo.status.value)
        }.getOrElse {
            AppLog.w(TAG, "위젯 데이터를 읽지 못했습니다", it)
            TodayModel.Empty
        }
        provideContent {
            GlanceTheme(colors = WidgetColors) { Content(model) }
        }
    }

    companion object {
        private const val TAG = "TodayWidget"
        private val SMALL = DpSize(110.dp, 110.dp)
        private val WIDE = DpSize(250.dp, 110.dp)
        private val TALL = DpSize(250.dp, 220.dp)

        private val WidgetColors = ColorProviders(light = LightScheme, dark = DarkScheme)
        private val Record = ColorProvider(day = Color(0xFFB91C13), night = Color(0xFFFFB4A8))

        /** 녹음 상태나 시간표가 바뀌었을 때 부른다. 위젯이 없으면 아무 일도 하지 않는다. */
        suspend fun refresh(context: Context) {
            runCatching { TodayWidget().updateAll(context) }
                .onFailure { AppLog.w(TAG, "위젯 갱신 실패", it) }
        }

        @Composable
        private fun Content(model: TodayModel) {
            val context = LocalContext.current
            val size = LocalSize.current
            val c = GlanceTheme.colors
            Column(
                GlanceModifier
                    .fillMaxSize()
                    .appWidgetBackground()
                    .background(c.surface)
                    .cornerRadius(24.dp)
                    .padding(16.dp)
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        context.getString(
                            R.string.widget_today,
                            LocalDate.now().format(DateTimeFormatter.ofPattern(context.getString(R.string.widget_date_pattern))),
                        ),
                        style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    RecordButton(model.recording.active)
                }
                Spacer(GlanceModifier.height(8.dp))
                when {
                    model.recording.active -> RecordingBlock(model)
                    model.current != null -> SessionBlock(context.getString(R.string.widget_now), model.current)
                    model.next != null -> SessionBlock(
                        context.getString(R.string.widget_next, TimeUtils.minuteToText(model.next.startMinute)),
                        model.next,
                    )
                    else -> Text(
                        context.getString(
                            if (model.totalToday == 0) R.string.widget_none_today else R.string.widget_done_today
                        ),
                        style = TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                    )
                }
                val showList = size.height >= TALL.height && size.width >= WIDE.width
                val rest = if (model.recording.active || model.current != null) {
                    listOfNotNull(model.next) + model.later
                } else model.later
                if (showList && rest.isNotEmpty()) {
                    Spacer(GlanceModifier.height(12.dp))
                    rest.take(3).forEach { s -> LaterRow(s) }
                }
            }
        }

        @Composable
        private fun RecordButton(recording: Boolean) {
            val context = LocalContext.current
            val c = GlanceTheme.colors
            val intent = if (recording) RecordingService.stopIntentForWidget(context)
            else RecordingService.manualIntent(context)
            Box(
                GlanceModifier
                    .size(40.dp)
                    .background(if (recording) Record else c.secondaryContainer)
                    .cornerRadius(20.dp)
                    .clickable(actionStartService(intent, isForegroundService = true)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(if (recording) R.drawable.ic_widget_stop else R.drawable.ic_widget_mic),
                    contentDescription = context.getString(
                        if (recording) R.string.widget_cd_stop else R.string.widget_cd_record
                    ),
                    colorFilter = ColorFilter.tint(if (recording) c.onPrimary else c.onSecondaryContainer),
                    modifier = GlanceModifier.size(20.dp),
                )
            }
        }

        @Composable
        private fun RecordingBlock(model: TodayModel) {
            val context = LocalContext.current
            val c = GlanceTheme.colors
            Text(
                context.getString(R.string.widget_recording),
                style = TextStyle(color = Record, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                model.recording.subject,
                style = TextStyle(color = c.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            val end = model.recording.plannedEndAt
            if (end > 0L) {
                Text(
                    context.getString(R.string.widget_until, TimeUtils.clockText(end)),
                    style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp),
                )
            }
        }

        @Composable
        private fun SessionBlock(label: String, s: Session) {
            val context = LocalContext.current
            val c = GlanceTheme.colors
            Text(label, style = TextStyle(color = c.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Text(
                s.subject.ifBlank { context.getString(R.string.subject_makeup) },
                style = TextStyle(color = c.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            val meta = listOf(
                TimeUtils.minuteToText(s.startMinute) + " – " + TimeUtils.minuteToText(s.endMinute),
                s.room,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp), maxLines = 1)
        }

        @Composable
        private fun LaterRow(s: Session) {
            val context = LocalContext.current
            val c = GlanceTheme.colors
            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TimeUtils.minuteToText(s.startMinute),
                    style = TextStyle(color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    s.subject.ifBlank { context.getString(R.string.subject_makeup) },
                    style = TextStyle(color = c.onSurface, fontSize = 14.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

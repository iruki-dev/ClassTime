package dev.iruki.classtime.util

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 브로드캐스트 리시버가 백그라운드 작업을 안전하게 끝내도록 돕는다.
 *
 * 직접 `goAsync()` + `CoroutineScope(Dispatchers.IO).launch` 를 쓰면 두 가지가 샌다.
 * - 수신할 때마다 **새 스코프가 생기고 아무도 취소하지 않는다.** 작업이 멈추면 그대로 누수된다.
 * - 시스템은 `goAsync()` 로 연장한 시간도 **약 10초**만 허용한다. 그 안에 `finish()` 를
 *   부르지 못하면 프로세스가 ANR 로 죽는다. 타임아웃이 없으면 DB 나 알람 API 가 느려지는
 *   드문 순간에 앱 전체가 함께 죽는다.
 *
 * 그래서 스코프는 하나만 두고, 시스템 한도보다 짧은 [timeoutMs] 안에 반드시 끝내며,
 * 성공하든 실패하든 `finish()` 를 부른다.
 */
object ReceiverWork {

    /** 모든 리시버가 공유하는 단일 스코프. SupervisorJob 이라 한 건의 실패가 옆을 죽이지 않는다. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 시스템의 ~10초 한도보다 넉넉히 짧게 잡아, 한도에 닿기 전에 우리가 먼저 포기한다. */
    const val TIMEOUT_MS = 8_000L

    fun BroadcastReceiver.runAsync(
        tag: String,
        what: String,
        timeoutMs: Long = TIMEOUT_MS,
        block: suspend () -> Unit,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(timeoutMs) { block() }
            } catch (e: TimeoutCancellationException) {
                AppLog.e(tag, "$what 이(가) ${timeoutMs}ms 안에 끝나지 않아 중단했습니다", e)
            } catch (e: Exception) {
                AppLog.e(tag, "$what 실패", e)
            } finally {
                // 이걸 놓치면 시스템이 프로세스를 ANR 로 처리한다. 무슨 일이 있어도 부른다.
                runCatching { pending.finish() }
            }
        }
    }
}

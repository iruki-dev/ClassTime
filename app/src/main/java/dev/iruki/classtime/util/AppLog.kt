package dev.iruki.classtime.util

import android.util.Log
import dev.iruki.classtime.BuildConfig

/**
 * 앱 전역 로깅 진입점. `android.util.Log` 를 직접 쓰지 않는 이유가 둘 있다.
 *
 * 1. **사용자 데이터가 릴리스 빌드의 logcat 에 남지 않게 한다.** 과목명·파일명 같은 값은
 *    진단에는 유용하지만 기기에 설치된 다른 앱이 읽을 수 있는 곳에 남아서는 안 된다.
 *    [d] / [i] 는 [BuildConfig.DEBUG] 가 컴파일 상수라 R8 이 릴리스에서 호출 자체를 지운다.
 *    릴리스에도 남겨야 하는 [w] / [e] 에는 식별자(id)만 싣고 이름은 싣지 않는다.
 *
 * 2. **크래시 리포팅을 나중에 붙일 자리를 하나로 모아 둔다.** 도입 시 [e] 한 곳만 고치면
 *    호출부 24군데를 건드릴 필요가 없다.
 */
object AppLog {

    /** 상세 추적용. 릴리스에서는 제거된다. */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    /** 정상 흐름의 이정표. 릴리스에서는 제거되므로 사용자 데이터를 실어도 안전하다. */
    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    /** 복구 가능한 이상. 릴리스에도 남으므로 사용자 데이터를 싣지 않는다. */
    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
    }

    /** 기능이 실패한 지점. 릴리스에도 남으므로 사용자 데이터를 싣지 않는다. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        // 크래시 리포팅 도입 시: crashReporter.recordException(error ?: Exception(message))
        // 호출부를 바꿀 필요 없이 여기 한 줄이면 된다. 계획은 DEPLOY.md 의 "향후 과제" 참고.
    }
}

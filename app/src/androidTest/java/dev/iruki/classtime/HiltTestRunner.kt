package dev.iruki.classtime

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * 계측 테스트에서 [ClassTimeApp] 대신 [HiltTestApplication] 을 띄운다.
 * `testInstrumentationRunner` 가 이 클래스를 가리킨다.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}

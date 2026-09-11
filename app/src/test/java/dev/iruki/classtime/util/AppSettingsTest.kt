package dev.iruki.classtime.util

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.iruki.classtime.data.StandbyState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppSettingsTest {

    private fun settings() = AppSettings(ApplicationProvider.getApplicationContext())

    @Test
    fun standby_isOnByDefault() {
        // 기본값이 꺼짐이면 사용자가 아무 것도 안 했을 때 자동 녹음이 전부 무음이 된다.
        assertThat(settings().standbyEnabled).isTrue()
    }

    @Test
    fun standby_persistsAcrossInstances() {
        settings().standbyEnabled = false
        assertThat(settings().standbyEnabled).isFalse()
        settings().standbyEnabled = true
        assertThat(settings().standbyEnabled).isTrue()
    }

    @Test
    fun standbyState_isOnlyReadyWhenServiceHoldsTheMic() {
        // 서비스가 떠 있어도 마이크를 못 쥐었으면 무음 녹음이 된다 = 준비된 게 아니다.
        assertThat(StandbyState(active = true, micReady = true).readyForSilentFreeRecording).isTrue()
        assertThat(StandbyState(active = true, micReady = false).readyForSilentFreeRecording).isFalse()
        assertThat(StandbyState(active = false, micReady = true).readyForSilentFreeRecording).isFalse()
        assertThat(StandbyState().readyForSilentFreeRecording).isFalse()
    }
}

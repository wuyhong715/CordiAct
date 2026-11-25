package com.cordiact.app.deepseek

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowLoopbackTlsInstrumentationTest {
    @Test fun exactPinAndAlpnCarryTheReadOnlyProjection() {
        assumeTrue("requires Android 10 TLS and ALPN", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        FollowLoopbackTlsFixture.acceptExactPinAndAlpn()
    }

    @Test fun wrongPinOrMissingAlpnNeverReleaseApplicationBytes() {
        assumeTrue("requires Android 10 TLS and ALPN", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        FollowLoopbackTlsFixture.rejectWrongPinAndMissingAlpn()
    }
}

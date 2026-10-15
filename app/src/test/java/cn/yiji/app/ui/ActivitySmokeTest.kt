package cn.yiji.app.ui

import android.os.Looper
import cn.yiji.app.MainActivity
import cn.yiji.app.YijiApplication
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = YijiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ActivitySmokeTest {
    @Test fun appOpensWithoutExistingData() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(controller.get().findViewById<android.view.View>(android.R.id.content))
        controller.pause().stop().destroy()
    }
}

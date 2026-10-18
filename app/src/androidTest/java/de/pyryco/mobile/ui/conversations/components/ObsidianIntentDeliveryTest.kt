package de.pyryco.mobile.ui.conversations.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android dispatch, rather than an intent stub or a dependency on an installed Obsidian app. */
@RunWith(AndroidJUnit4::class)
class ObsidianIntentDeliveryTest {
    @Test
    fun actionView_reachesControlledHandler_withUnchangedUri() {
        Log.i("ObsidianIntentDelivery", "sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} fingerprint=${Build.FINGERPRINT}")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testPackage = instrumentation.context.packageName
        val target = "obsidian://open?vault=Second%20brain&file=%F0%9F%93%8B%20Projects%2fPlan.MD&paneType=tab"
        val delivered = CountDownLatch(1)
        var receivedAction: String? = null
        var receivedUri: String? = null
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    receivedAction = intent.getStringExtra("action")
                    receivedUri = intent.getStringExtra("uri")
                    delivered.countDown()
                }
            }
        targetContext.registerReceiver(receiver, IntentFilter(ObsidianTestHandlerActivity.DELIVERY_ACTION), Context.RECEIVER_EXPORTED)
        try {
            // Pin only this test dispatch to the test APK so another installed handler cannot take it.
            val dispatchContext =
                object : ContextWrapper(targetContext) {
                    override fun startActivity(intent: Intent) {
                        assertEquals(Intent.ACTION_VIEW, intent.action)
                        assertEquals(target, intent.dataString)
                        assertNull(intent.`package`)
                        assertNull(intent.component)
                        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
                        super.startActivity(Intent(intent).setPackage(testPackage))
                    }
                }
            instrumentation.runOnMainSync { assertNull(openObsidianLink(dispatchContext, target)) }
            assertTrue("controlled handler must receive ACTION_VIEW", delivered.await(10, TimeUnit.SECONDS))
            assertEquals(Intent.ACTION_VIEW, receivedAction)
            assertEquals(target, receivedUri)
        } finally {
            targetContext.unregisterReceiver(receiver)
        }
    }
}

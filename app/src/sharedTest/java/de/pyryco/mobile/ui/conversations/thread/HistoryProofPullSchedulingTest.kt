package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.e2e.pullHistoryPage
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Exercise queued composition work rather than the default unconfined test effects. */
@RunWith(AndroidJUnit4::class)
class HistoryProofPullSchedulingTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val rule = createComposeRule(effectContext = StandardTestDispatcher())

    @Test
    fun proofPullRequestsOnceWithQueuedGestureProcessing() {
        val previousSink = RelayLog.sink
        val previousEnabled = RelayLog.enabled
        val events = CopyOnWriteArrayList<String>()
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> if (message.startsWith("event=older_history_input")) events += message }
        try {
            var demands = 0
            val list = LazyListState(firstVisibleItemIndex = 27)
            rule.setContent {
                PyrycodeMobileTheme(darkTheme = true) {
                    val gesture =
                        remember {
                            OlderHistoryGesture(
                                nearOldestEnd = { list.layoutInfo.isNearOldestEnd(29, list.layoutInfo.viewportSize.height * 2f) },
                                onDemand = { demands++ },
                            )
                        }
                    LazyColumn(
                        state = list,
                        reverseLayout = true,
                        modifier =
                            Modifier
                                .width(200.dp)
                                .height(200.dp)
                                .testTag("queued-proof-pull")
                                .olderHistoryPull(gesture),
                    ) {
                        items(30, key = { it }) { index -> Text("Row $index", Modifier.height(50.dp)) }
                    }
                }
            }
            rule.onNodeWithTag("queued-proof-pull").performTouchInput { pullHistoryPage() }
            rule.runOnIdle { assertEquals(events.joinToString("\n"), 1, demands) }
            println(events.joinToString("\n"))
        } finally {
            RelayLog.sink = previousSink
            RelayLog.enabled = previousEnabled
        }
    }
}

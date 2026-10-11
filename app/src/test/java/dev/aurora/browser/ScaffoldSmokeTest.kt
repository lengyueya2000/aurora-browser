package dev.aurora.browser

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaffoldSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun composeRendersOnJvm() {
        compose.setContent { Text("Aurora") }
        compose.onNodeWithText("Aurora").assertExists()
    }
}

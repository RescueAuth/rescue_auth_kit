package com.rescueauth.v2.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.RescueAuthTextField
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Input chrome must preserve editing, IME callbacks and protected-field semantics. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RescueAuthTextFieldTest {
    @get:Rule val rule = createComposeRule()

    @Test fun typingAndImeActionStillReachTheCaller() {
        val value = mutableStateOf("")
        var done = 0
        rule.setContent { RescueAuthTheme {
            RescueAuthTextField(value.value, { value.value = it }, Modifier.testTag("field"),
                label = { Text("Account") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done++ }))
        } }
        rule.onNodeWithTag("field").performTextInput("Demo account")
        rule.onNodeWithTag("field").performImeAction()
        rule.runOnIdle { assertEquals("Demo account", value.value); assertEquals(1, done) }
    }

    @Test fun passwordTransformationAndNumericKeyboardStayProtected() {
        rule.setContent { RescueAuthTheme {
            RescueAuthTextField("", {}, Modifier.testTag("field"), label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        } }
        rule.onNodeWithTag("field").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test fun errorMessageAndDisabledStateRemainAccessible() {
        rule.setContent { RescueAuthTheme {
            RescueAuthTextField("", {}, Modifier.testTag("field"), enabled = false, isError = true,
                label = { Text("Name") }, supportingText = { Text("Name is required") })
        } }
        rule.onNodeWithTag("field").assertIsNotEnabled()
        rule.onNodeWithTag("field").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        rule.onNodeWithText("Name is required", useUnmergedTree = true).assertExists()
    }

    @Test fun multilineInputRetainsLineBreaks() {
        val value = mutableStateOf("")
        rule.setContent { RescueAuthTheme {
            RescueAuthTextField(value.value, { value.value = it }, Modifier.testTag("field"),
                label = { Text("Notes") }, minLines = 3)
        } }
        rule.onNodeWithTag("field").performTextInput("First line\nSecond line")
        rule.runOnIdle { assertEquals("First line\nSecond line", value.value) }
    }

    @Test fun labelStartsInsideAndFloatsOntoTheBorderWithoutMovingTheInput() {
        val value = mutableStateOf("")
        rule.setContent { RescueAuthTheme {
            RescueAuthTextField(value.value, { value.value = it }, Modifier.testTag("field"),
                label = { Text("Service", Modifier.testTag("field_label")) }, singleLine = true)
        } }
        val slot = rule.onNodeWithTag("input_slot", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val emptyLabel = rule.onNodeWithTag("field_label", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Empty label belongs inside the input", emptyLabel.top >= slot.top && emptyLabel.bottom <= slot.bottom)
        rule.onNodeWithTag("field").performTextInput("Demo")
        rule.mainClock.advanceTimeBy(300)
        val floatingLabel = rule.onNodeWithTag("field_label", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Filled label should straddle the top border", floatingLabel.top < slot.top && floatingLabel.bottom > slot.top)
        val filledSlot = rule.onNodeWithTag("input_slot", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(slot.top, filledSlot.top)
        assertEquals(slot.left, filledSlot.left)
        assertEquals(slot.right, filledSlot.right)
        // Font metrics can round the intrinsic text height by one pixel in Robolectric.
        assertTrue(kotlin.math.abs(filledSlot.bottom.value - slot.bottom.value) <= 1f)
    }
}

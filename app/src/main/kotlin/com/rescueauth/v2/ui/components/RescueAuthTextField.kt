package com.rescueauth.v2.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.CardTokens

/** Rounded search-style input with Material floating labels and aligned trailing actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueAuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val labelInset = if (label == null) 0.dp else with(LocalDensity.current) { labelLineHeight.toDp() / 2 }
    val textColor = if (textStyle.color.isSpecified) textStyle.color
        else scheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = CardTokens.inputContainerColor(),
        unfocusedContainerColor = CardTokens.inputContainerColor(),
        disabledContainerColor = CardTokens.inputContainerColor().copy(alpha = .5f),
        errorContainerColor = CardTokens.inputContainerColor(),
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = CardTokens.inputOutlineColor(),
    )
    val errorMessage = stringResource(R.string.input_error)
    BasicTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.semantics { if (isError) error(errorMessage) },
        enabled = enabled, readOnly = readOnly,
        textStyle = textStyle.copy(color = textColor),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        interactionSource = interactions,
        cursorBrush = SolidColor(if (isError) scheme.error else scheme.primary),
        decorationBox = { innerTextField ->
            // Reserve the floating label's half-height, so focus and value changes
            // never move the input itself. Icons remain in the bordered container.
            Box(Modifier.fillMaxWidth().padding(top = labelInset)
                .defaultMinSize(minHeight = CardTokens.inputMinHeight), propagateMinConstraints = true) {
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value, innerTextField = innerTextField, enabled = enabled,
                    singleLine = singleLine, visualTransformation = visualTransformation,
                    interactionSource = interactions, isError = isError, label = label,
                    placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
                    supportingText = supportingText, colors = colors,
                    container = {
                        OutlinedTextFieldDefaults.Container(enabled, isError, interactions,
                            modifier = Modifier.testTag("input_slot"),
                            colors = colors, shape = CardTokens.rowShape,
                            focusedBorderThickness = CardTokens.inputFocusBorderWidth,
                            unfocusedBorderThickness = CardTokens.inputRestBorderWidth)
                    },
                )
            }
        },
    )
}

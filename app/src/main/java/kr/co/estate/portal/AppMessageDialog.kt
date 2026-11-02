package kr.co.estate.portal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Fixed content height keeps action buttons stationary.
 * Message text scrolls inside the bounded region, never resizes the buttons.
 */
@Composable
fun AppMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth().height(180.dp)) {
                Text(
                    text = message,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("확인") }
        },
        dismissButton = {
            if (onRetry != null) TextButton(onClick = onRetry) { Text("다시 조회") }
        }
    )
}

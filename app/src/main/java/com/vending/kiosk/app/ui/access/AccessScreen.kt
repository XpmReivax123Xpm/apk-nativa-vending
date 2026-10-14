package com.vending.kiosk.app.ui.access

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vending.kiosk.R

/**
 * Pantalla de acceso al kiosko: pide el PIN de la maquina y, si el kiosko todavia no sabe en que equipo
 * corre, tambien su identificador —una sola vez—.
 */
@Composable
fun AccessScreen(
    state: AccessUiState,
    onDireccionMacChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onEnterClick: () -> Unit,
    onCambiarEquipoClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val primaryBlue = colorResource(R.color.bp_primary_blue)
    val backgroundBlue = colorResource(R.color.bp_bg_blue)
    val cyan = colorResource(R.color.bp_cyan)
    val orange = colorResource(R.color.bp_orange)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(primaryBlue, backgroundBlue)))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.boxipago_logo),
                contentDescription = "BoxiPago",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 250.dp, height = 170.dp)
            )

            Spacer(Modifier.height(12.dp))

            AccessForm(
                state = state,
                onDireccionMacChange = onDireccionMacChange,
                onPinChange = onPinChange,
                onEnterClick = onEnterClick,
                onCambiarEquipoClick = onCambiarEquipoClick,
                cyan = cyan,
                orange = orange
            )

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onBackClick, enabled = !state.isAuthenticating) {
                Text(text = "Volver al menu", color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun AccessForm(
    state: AccessUiState,
    onDireccionMacChange: (String) -> Unit,
    onPinChange: (String) -> Unit,
    onEnterClick: () -> Unit,
    onCambiarEquipoClick: () -> Unit,
    cyan: Color,
    orange: Color
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        disabledTextColor = Color.White.copy(alpha = 0.6f),
        focusedBorderColor = cyan,
        unfocusedBorderColor = Color.White.copy(alpha = 0.45f),
        disabledBorderColor = Color.White.copy(alpha = 0.25f),
        focusedLabelColor = cyan,
        unfocusedLabelColor = Color.White.copy(alpha = 0.8f),
        disabledLabelColor = Color.White.copy(alpha = 0.5f),
        cursorColor = cyan
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC1E4FA9)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Acceso al kiosko",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            if (!state.needsEquipo) {
                Text(
                    text = state.machineCode.ifBlank { "Equipo configurado" } +
                        state.machineLocation.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (state.needsEquipo) {
                OutlinedTextField(
                    value = state.direccionMac,
                    onValueChange = onDireccionMacChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isAuthenticating,
                    label = { Text("Identificador del equipo") },
                    placeholder = { Text("MAC o serial de la tablet") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    colors = fieldColors
                )

                Text(
                    text = "Se carga una sola vez y despues el kiosko solo pide el PIN.",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedTextField(
                value = state.pin,
                onValueChange = onPinChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isAuthenticating,
                label = { Text("PIN del kiosko") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done
                ),
                colors = fieldColors
            )

            if (state.isAuthenticating) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = orange,
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(text = "Ingresando...", color = Color.White)
                }
            } else if (state.error != null) {
                Text(
                    text = state.error,
                    color = Color(0xFFFFD6A5),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = onEnterClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp)),
                enabled = !state.isAuthenticating,
                contentPadding = PaddingValues(vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = orange,
                    disabledContainerColor = Color(0xFFBC8D56),
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text(text = "Ingresar", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            if (!state.needsEquipo) {
                TextButton(
                    onClick = onCambiarEquipoClick,
                    enabled = !state.isAuthenticating,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(text = "Cambiar equipo", color = cyan)
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun AccessScreenEquipoPendientePreview() {
    AccessScreen(
        state = AccessUiState(needsEquipo = true),
        onDireccionMacChange = {},
        onPinChange = {},
        onEnterClick = {},
        onCambiarEquipoClick = {},
        onBackClick = {}
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun AccessScreenSoloPinPreview() {
    AccessScreen(
        state = AccessUiState(
            needsEquipo = false,
            machineCode = "MQ-1001",
            machineLocation = "Sucursal Sur"
        ),
        onDireccionMacChange = {},
        onPinChange = {},
        onEnterClick = {},
        onCambiarEquipoClick = {},
        onBackClick = {}
    )
}

package com.vending.kiosk.app.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.Image
import com.vending.kiosk.R
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit
) {
    var showLogo by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        showLogo = true
        delay(180)
        showForm = true
    }

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
            AnimatedVisibility(
                visible = showLogo,
                enter = fadeIn(tween(520)) + slideInVertically(tween(520)) { it / 5 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 8 }
            ) {
                Image(
                    painter = painterResource(R.drawable.boxipago_logo),
                    contentDescription = "BoxiPago",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 250.dp, height = 190.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            AnimatedVisibility(
                visible = showForm,
                enter = fadeIn(tween(420)) + slideInVertically(tween(420)) { it / 6 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 10 }
            ) {
                LoginForm(
                    state = state,
                    onEmailChange = onEmailChange,
                    onPasswordChange = onPasswordChange,
                    onLoginClick = onLoginClick,
                    cyan = cyan,
                    orange = orange
                )
            }
        }
    }
}

@Composable
private fun LoginForm(
    state: LoginUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
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
                text = "Iniciar sesión",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
                label = { Text("Correo") },
                placeholder = { Text("usuario@dominio.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                colors = fieldColors
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
                label = { Text("Contraseña") },
                placeholder = { Text("Ingresa tu contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                colors = fieldColors
            )

            if (state.isLoading) {
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
            } else if (state.message != null) {
                Text(
                    text = state.message,
                    color = Color(0xFFFFD6A5),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = onLoginClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp)),
                enabled = !state.isLoading,
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
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun LoginScreenPreview() {
    LoginScreen(
        state = LoginUiState(email = "usuario@dominio.com"),
        onEmailChange = {},
        onPasswordChange = {},
        onLoginClick = {}
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun LoginScreenLoadingPreview() {
    LoginScreen(
        state = LoginUiState(
            email = "usuario@dominio.com",
            password = "password",
            isLoading = true
        ),
        onEmailChange = {},
        onPasswordChange = {},
        onLoginClick = {}
    )
}

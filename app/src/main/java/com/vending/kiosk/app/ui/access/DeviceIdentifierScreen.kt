package com.vending.kiosk.app.ui.access

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vending.kiosk.R

/**
 * Identificador del equipo: se muestra UNA sola vez, cuando la tablet entrega su MAC por primera vez,
 * para que quien instala el kiosko la anote y la registre como identificador de esa maquina.
 *
 * Despues de esta pantalla el acceso al kiosko vuelve a pedir solo el PIN: la MAC no se muestra ni se
 * teclea nunca mas (si se perdiera el dato, hay que borrar los datos de la app, ver
 * docs/ACCESO_E_IDENTIFICADOR.md).
 */
@Composable
fun DeviceIdentifierScreen(
    macAddress: String,
    onContinuarClick: () -> Unit
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
                .widthIn(max = 520.dp)
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC1E4FA9)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Identificador de este dispositivo",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "ATENCION: anota esta MAC. Es el identificador de esta tablet y " +
                            "NO se volvera a mostrar por seguridad.",
                        color = Color(0xFFFFD6A5),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0B1E3A))
                            .padding(horizontal = 12.dp, vertical = 26.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = macAddress,
                            color = cyan,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Text(
                        text = "Con este dato se registra la maquina en el sistema. Despues de " +
                            "continuar, el kiosko solo va a pedir el PIN.",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = onContinuarClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp)),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = orange,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Ya la anote, continuar",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun DeviceIdentifierScreenPreview() {
    DeviceIdentifierScreen(
        macAddress = "AC:1F:6B:2C:44:9E",
        onContinuarClick = {}
    )
}

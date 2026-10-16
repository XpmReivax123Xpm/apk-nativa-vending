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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vending.kiosk.R
import com.vending.kiosk.app.device.DeviceIdentifierFailure

/**
 * El equipo no entrego su identificador: se dice por que y se ofrece reintentar.
 *
 * No hay salida por ingreso manual —el kiosko no pide identificador nunca—, asi que esta pantalla es
 * la unica respuesta: o el equipo entrega la MAC, o el kiosko no inicia sesion.
 */
@Composable
fun DeviceIdentifierUnavailableScreen(
    failure: DeviceIdentifierFailure,
    onReintentarClick: () -> Unit,
    onVolverAlMenuClick: () -> Unit
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
                        text = "No se pudo obtener el identificador de esta tablet",
                        color = Color(0xFFFFD6A5),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = failure.explicacion(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "El kiosko no inicia sin el identificador del equipo: no se pide a mano.",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = onReintentarClick,
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
                        Text(text = "Reintentar", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    TextButton(onClick = onVolverAlMenuClick) {
                        Text(text = "Volver al menu", color = cyan)
                    }
                }
            }
        }
    }
}

private fun DeviceIdentifierFailure.explicacion(): String = when (this) {
    is DeviceIdentifierFailure.NotAvailable ->
        "El equipo no expone ninguna interfaz de red cableada: no se pudo leer su MAC de fabrica " +
            "(/sys/class/net/eth0/address)."
    is DeviceIdentifierFailure.Error ->
        "Hubo un error al leerla: $detail"
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun DeviceIdentifierUnavailablePreview() {
    DeviceIdentifierUnavailableScreen(
        failure = DeviceIdentifierFailure.NotAvailable,
        onReintentarClick = {},
        onVolverAlMenuClick = {}
    )
}

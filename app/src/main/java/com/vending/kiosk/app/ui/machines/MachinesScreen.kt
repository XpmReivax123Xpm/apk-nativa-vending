package com.vending.kiosk.app.ui.machines

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vending.kiosk.R
import com.vending.kiosk.app.data.backend.Machine

@Composable
fun MachinesScreen(
    state: MachinesUiState,
    onMachineClick: (Machine) -> Unit,
    onPinChange: (String) -> Unit,
    onAuthenticateClick: () -> Unit,
    onDismissPin: () -> Unit,
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
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MachinesHeader(cyan = cyan)

            AnimatedContent(
                targetState = machinesContent(state),
                transitionSpec = {
                    fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 12 } togetherWith
                        fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 12 }
                },
                label = "machinesContent"
            ) { content ->
                when (content) {
                    MachinesContent.Loading -> LoadingMachines(orange)
                    MachinesContent.Error -> StatusMessage(state.machinesError.orEmpty())
                    MachinesContent.Empty -> StatusMessage("Sin maquinas asignadas")
                    MachinesContent.List -> MachinesList(
                        machines = state.machines,
                        onMachineClick = onMachineClick,
                        cyan = cyan
                    )
                }
            }

            Button(
                onClick = onBackClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryBlue,
                    contentColor = Color.White
                )
            ) {
                Text("Atras", fontWeight = FontWeight.Bold)
            }
        }
    }

    state.selectedMachine?.let { machine ->
        MachinePinDialog(
            machine = machine,
            state = state,
            onPinChange = onPinChange,
            onAuthenticateClick = onAuthenticateClick,
            onDismissPin = onDismissPin,
            primaryBlue = primaryBlue,
            cyan = cyan,
            orange = orange
        )
    }
}

@Composable
private fun MachinesHeader(cyan: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xCC1E4FA9)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Maquinas Asignadas",
                color = cyan,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Selecciona una maquina para continuar",
                color = Color(0xFFD6E9FF),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LoadingMachines(orange: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = orange, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
            Text("Cargando maquinas...", color = Color.White)
        }
    }
}

@Composable
private fun StatusMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = Color(0xFFDFF0FF),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun MachinesList(
    machines: List<Machine>,
    onMachineClick: (Machine) -> Unit,
    cyan: Color
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items = machines, key = { it.id }) { machine ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 10 }
            ) {
                MachineCard(machine = machine, onClick = { onMachineClick(machine) }, cyan = cyan)
            }
        }
    }
}

@Composable
private fun MachineCard(machine: Machine, onClick: () -> Unit, cyan: Color) {
    val isActive = machine.estado == 1
    val statusBackground = if (isActive) Color(0xFFE8F7EE) else Color(0xFFFDECEC)
    val statusText = if (isActive) Color(0xFF1E7A3F) else Color(0xFFB42318)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2857A8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = machine.codigo,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = if (isActive) "Activa" else "Inactiva",
                    modifier = Modifier
                        .background(statusBackground, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    color = statusText,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = machine.locacion,
                color = Color(0xFFD6E9FF),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Toca para ingresar",
                color = cyan,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun MachinePinDialog(
    machine: Machine,
    state: MachinesUiState,
    onPinChange: (String) -> Unit,
    onAuthenticateClick: () -> Unit,
    onDismissPin: () -> Unit,
    primaryBlue: Color,
    cyan: Color,
    orange: Color
) {
    val isAuthenticating = state.isAuthenticating
    Dialog(
        onDismissRequest = { if (!isAuthenticating) onDismissPin() },
        properties = DialogProperties(
            dismissOnBackPress = !isAuthenticating,
            dismissOnClickOutside = !isAuthenticating
        )
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 12 },
            exit = fadeOut(tween(120))
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Ingresar PIN de maquina",
                        color = Color(0xFF0E2A47),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "Maquina: ${machine.codigo}\n${machine.locacion}",
                        color = Color(0xFF365574),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = state.pin,
                        onValueChange = { onPinChange(it.take(10)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isAuthenticating,
                        label = { Text("PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryBlue,
                            focusedLabelColor = primaryBlue,
                            cursorColor = cyan
                        )
                    )
                    state.pinError?.let { error ->
                        Text(error, color = Color(0xFFD62828), style = MaterialTheme.typography.bodySmall)
                    }
                    if (isAuthenticating) {
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
                            Text("Ingresando...", color = Color(0xFF365574))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { if (!isAuthenticating) onDismissPin() },
                            enabled = !isAuthenticating,
                            colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
                        ) {
                            Text("Cancelar")
                        }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = { if (!isAuthenticating) onAuthenticateClick() },
                            enabled = !isAuthenticating,
                            colors = ButtonDefaults.buttonColors(containerColor = orange)
                        ) {
                            Text("Ingresar")
                        }
                    }
                }
            }
        }
    }
}

private fun machinesContent(state: MachinesUiState): MachinesContent = when {
    state.machinesError != null -> MachinesContent.Error
    state.isLoadingMachines -> MachinesContent.Loading
    state.machines.isEmpty() -> MachinesContent.Empty
    else -> MachinesContent.List
}

private enum class MachinesContent { Loading, Error, Empty, List }

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun MachinesScreenPreview() {
    MachinesScreen(
        state = MachinesUiState(
            machines = listOf(
                Machine(id = 1, codigo = "MQ-0001", locacion = "Sucursal Central", estado = 1),
                Machine(id = 2, codigo = "MQ-0002", locacion = "Sucursal Norte", estado = 0),
                Machine(id = 3, codigo = "MQ-0003", locacion = "Sucursal Sur", estado = 1)
            )
        ),
        onMachineClick = {},
        onPinChange = {},
        onAuthenticateClick = {},
        onDismissPin = {},
        onBackClick = {}
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun MachinesScreenLoadingPreview() {
    MachinesScreen(
        state = MachinesUiState(isLoadingMachines = true),
        onMachineClick = {},
        onPinChange = {},
        onAuthenticateClick = {},
        onDismissPin = {},
        onBackClick = {}
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun MachinesScreenPinDialogPreview() {
    MachinesScreen(
        state = MachinesUiState(
            selectedMachine = Machine(
                id = 1,
                codigo = "MQ-0001",
                locacion = "Sucursal Central",
                estado = 1
            ),
            pinError = "Ingresa el PIN de la maquina"
        ),
        onMachineClick = {},
        onPinChange = {},
        onAuthenticateClick = {},
        onDismissPin = {},
        onBackClick = {}
    )
}

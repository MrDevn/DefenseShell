package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomConnection
import com.example.data.model.FreePresets
import com.example.ui.theme.ClaudeTerracotta
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomConnectionDialog(
    connections: List<CustomConnection>,
    activeConnection: CustomConnection?,
    onSelectConnection: (CustomConnection) -> Unit,
    onSaveConnection: (CustomConnection) -> Unit,
    onDeleteConnection: (String) -> Unit,
    onTestConnection: suspend (CustomConnection) -> Result<String>,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // Вкладки списка: 0 = Мои подключения, 1 = Free (бесплатные модели для всех)
    var selectedTab by remember { mutableStateOf(if (connections.isEmpty()) 1 else 0) }

    // Screen mode: 0 = Connection list, 1 = Add / Edit Connection Form
    var isEditingForm by remember { mutableStateOf(false) }
    var editingConnectionId by remember { mutableStateOf<String?>(null) }

    // Form fields
    var providerIdInput by remember { mutableStateOf("") }
    var baseUrlInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var modelIdInput by remember { mutableStateOf("") }
    var authHeaderFormatInput by remember { mutableStateOf("Bearer %s") }
    var customHeadersInput by remember { mutableStateOf("") }
    var bodyTemplateInput by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    // Test result state
    var isTesting by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testIsSuccess by remember { mutableStateOf(false) }

    fun populateFormForEdit(conn: CustomConnection?) {
        if (conn != null) {
            editingConnectionId = conn.id
            providerIdInput = conn.providerId
            baseUrlInput = conn.baseUrl
            apiKeyInput = conn.apiKey
            modelIdInput = conn.modelId
            authHeaderFormatInput = conn.authHeaderFormat
            customHeadersInput = conn.customHeadersJson.takeIf { it != "{}" } ?: ""
            bodyTemplateInput = conn.requestBodyTemplate
        } else {
            editingConnectionId = null
            providerIdInput = "Пользовательский API"
            baseUrlInput = "https://api.openai.com/v1"
            apiKeyInput = ""
            modelIdInput = "gpt-4o"
            authHeaderFormatInput = "Bearer %s"
            customHeadersInput = ""
            bodyTemplateInput = ""
        }
        testResultText = null
        isEditingForm = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEditingForm) {
                                if (editingConnectionId != null) "Редактирование подключения" else "Новое подключение"
                            } else if (selectedTab == 1) {
                                "Бесплатные модели (Free)"
                            } else {
                                "Пользовательские подключения"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (!isEditingForm && selectedTab == 1) {
                                "Доступны всем • работают сразу, без ключей"
                            } else {
                                "Тип подключения: Пользовательский (Custom)"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = ClaudeTerracotta)
                        )
                    }

                    if (isEditingForm) {
                        IconButton(onClick = { isEditingForm = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Назад к списку")
                        }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!isEditingForm) {
                    // Вкладки: Мои подключения / Free
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ConnectionTabPill(label = "Мои", selected = selectedTab == 0) { selectedTab = 0 }
                        ConnectionTabPill(label = "Free 🎁", selected = selectedTab == 1) { selectedTab = 1 }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedTab == 1) {
                        // Вкладка Free: встроенные бесплатные модели, доступны всем без настройки
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Text(
                                    text = "Эти модели работают сразу и бесплатно для всех — нажмите на модель, чтобы активировать её.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            items(FreePresets.freeModelIds) { modelId ->
                                val isActive = activeConnection?.id == FreePresets.connectionId(modelId)
                                val shape = RoundedCornerShape(12.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(shape)
                                        .border(
                                            width = if (isActive) 1.5.dp else 1.dp,
                                            color = if (isActive) ClaudeTerracotta else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                            shape = shape
                                        )
                                        .background(if (isActive) ClaudeTerracotta.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            onSaveConnection(FreePresets.toConnection(modelId))
                                            onDismiss()
                                        }
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = modelId,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Бесплатно • без настройки и ключей",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                ),
                                                maxLines = 1
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        if (isActive) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = ClaudeTerracotta,
                                                contentColor = Color.White
                                            ) {
                                                Text(
                                                    text = "АКТИВНО",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        } else {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFF4E9E67),
                                                contentColor = Color.White
                                            ) {
                                                Text(
                                                    text = "FREE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Вкладка «Мои»: list of existing Custom connections
                        if (connections.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Нет сохраненных подключений.\nДобавьте своё API или выберите бесплатную модель во вкладке Free 🎁",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(connections) { conn ->
                                    val isActive = conn.id == activeConnection?.id

                                    val shape = RoundedCornerShape(12.dp)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(shape)
                                            .border(
                                                width = if (isActive) 1.5.dp else 1.dp,
                                                color = if (isActive) ClaudeTerracotta else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                                shape = shape
                                            )
                                            .background(if (isActive) ClaudeTerracotta.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
                                            .clickable { onSelectConnection(conn) }
                                            .padding(12.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = conn.providerId.ifBlank { "Без названия" },
                                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                                    )
                                                    if (isActive) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = ClaudeTerracotta,
                                                            contentColor = Color.White
                                                        ) {
                                                            Text(
                                                                text = "АКТИВНО",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(2.dp))

                                                Text(
                                                    text = "Модель: ${conn.modelId}",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                )

                                                Text(
                                                    text = conn.baseUrl,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                    ),
                                                    maxLines = 1
                                                )
                                            }

                                            Row {
                                                IconButton(
                                                    onClick = { populateFormForEdit(conn) },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Edit,
                                                        contentDescription = "Редактировать",
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { onDeleteConnection(conn.id) },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "Удалить",
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { populateFormForEdit(null) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Добавить подключение")
                        }
                    }
                } else {
                    // Form for Custom Connection
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            OutlinedTextField(
                                value = providerIdInput,
                                onValueChange = { providerIdInput = it },
                                label = { Text("provider-id (Название подключения)") },
                                placeholder = { Text("Например: My OpenRouter, Local LM Studio") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = baseUrlInput,
                                onValueChange = { baseUrlInput = it },
                                label = { Text("base-url (URL эндпоинта API)") },
                                placeholder = { Text("https://api.openai.com/v1 или http://IP:11434/v1") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("api-key (Ключ авторизации)") },
                                placeholder = { Text("sk-...") },
                                singleLine = true,
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Скрыть/показать ключ"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = modelIdInput,
                                onValueChange = { modelIdInput = it },
                                label = { Text("model-id (Имя модели)") },
                                placeholder = { Text("gpt-4o, deepseek-chat, claude-3-5-sonnet") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdvanced = !showAdvanced }
                                    .padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = if (showAdvanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Дополнительные параметры (заголовки, шаблон)",
                                    style = MaterialTheme.typography.bodySmall.copy(color = ClaudeTerracotta, fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }

                        if (showAdvanced) {
                            item {
                                OutlinedTextField(
                                    value = authHeaderFormatInput,
                                    onValueChange = { authHeaderFormatInput = it },
                                    label = { Text("Формат заголовка авторизации") },
                                    placeholder = { Text("Bearer %s") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            item {
                                OutlinedTextField(
                                    value = customHeadersInput,
                                    onValueChange = { customHeadersInput = it },
                                    label = { Text("Кастомные HTTP-заголовки (JSON)") },
                                    placeholder = { Text("{\"HTTP-Referer\": \"https://app\", \"X-Title\": \"ClaudeShell\"}") },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            item {
                                OutlinedTextField(
                                    value = bodyTemplateInput,
                                    onValueChange = { bodyTemplateInput = it },
                                    label = { Text("Шаблон тела запроса JSON (опционально)") },
                                    placeholder = { Text("Оставьте пустым для стандартного OpenAI формата") },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Test connection button & result
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = {
                                    val tempConn = CustomConnection(
                                        id = editingConnectionId ?: "temp",
                                        providerId = providerIdInput.trim(),
                                        baseUrl = baseUrlInput.trim(),
                                        apiKey = apiKeyInput.trim(),
                                        modelId = modelIdInput.trim(),
                                        authHeaderFormat = authHeaderFormatInput.trim(),
                                        customHeadersJson = customHeadersInput.trim().ifBlank { "{}" },
                                        requestBodyTemplate = bodyTemplateInput.trim()
                                    )
                                    isTesting = true
                                    testResultText = null
                                    coroutineScope.launch {
                                        val result = onTestConnection(tempConn)
                                        isTesting = false
                                        if (result.isSuccess) {
                                            testIsSuccess = true
                                            testResultText = result.getOrNull()
                                        } else {
                                            testIsSuccess = false
                                            testResultText = result.exceptionOrNull()?.message ?: "Ошибка подключения"
                                        }
                                    }
                                },
                                enabled = !isTesting && baseUrlInput.isNotBlank() && modelIdInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Проверка...")
                                } else {
                                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Проверить подключение")
                                }
                            }
                        }

                        if (testResultText != null) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (testIsSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = testResultText!!,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = if (testIsSuccess) Color(0xFF2E7D32) else Color(0xFFC62828)
                                        ),
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { isEditingForm = false },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Отмена")
                                }

                                Button(
                                    onClick = {
                                        if (providerIdInput.isBlank() || baseUrlInput.isBlank() || modelIdInput.isBlank()) {
                                            testResultText = "Заполните обязательные поля: provider-id, base-url, model-id"
                                            testIsSuccess = false
                                            return@Button
                                        }

                                        val newConn = CustomConnection(
                                            id = editingConnectionId ?: java.util.UUID.randomUUID().toString(),
                                            providerId = providerIdInput.trim(),
                                            baseUrl = baseUrlInput.trim(),
                                            apiKey = apiKeyInput.trim(),
                                            modelId = modelIdInput.trim(),
                                            authHeaderFormat = authHeaderFormatInput.trim().ifBlank { "Bearer %s" },
                                            customHeadersJson = customHeadersInput.trim().ifBlank { "{}" },
                                            requestBodyTemplate = bodyTemplateInput.trim(),
                                            isActive = true
                                        )

                                        onSaveConnection(newConn)
                                        isEditingForm = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Сохранить")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isEditingForm) {
                TextButton(onClick = onDismiss) {
                    Text("Закрыть")
                }
            }
        }
    )
}

@Composable
private fun ConnectionTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) ClaudeTerracotta else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) ClaudeTerracotta else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
        )
    }
}

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Github
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GitHubRepo
import com.example.ui.theme.ClaudeTerracotta
import com.example.ui.theme.ClaudeWarning

/**
 * Вкладка «Репо»: репозитории GitHub после входа через токен.
 * Скачивание в рабочую папку агента (~/repos/<имя>), редактирование
 * файлами/агентом и отправка изменений обратно.
 */
@Composable
fun ReposView(
    login: String,
    repos: List<GitHubRepo>,
    downloadedNames: Set<String>,
    statusText: String?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onDownload: (GitHubRepo) -> Unit,
    onPush: (GitHubRepo) -> Unit,
    onOpen: (GitHubRepo) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Шапка: аккаунт + действия
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ClaudeTerracotta.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Github,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = login,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = if (repos.isEmpty()) "Репозитории GitHub" else "Репозиториев: ${repos.size}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Обновить список",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onLogout, enabled = !isLoading) {
                Text(
                    "Выйти",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Статус операций
        if (statusText != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = ClaudeTerracotta
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        if (repos.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Нажмите «Обновить», чтобы загрузить список ваших репозиториев.\n\nСкачанные репозитории попадают в ~/repos/ — их можно редактировать вручную, через агента, и отправлять изменения обратно в GitHub.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(repos, key = { it.fullName }) { repo ->
                    val isDownloaded = repo.name in downloadedNames
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isDownloaded) ClaudeTerracotta.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Github,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = repo.fullName,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        ),
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "ветка: ${repo.defaultBranch}" + if (repo.updatedAt.isNotBlank()) " • обновлён: ${repo.updatedAt}" else "",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        maxLines = 1
                                    )
                                }
                                if (isDownloaded) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = ClaudeWarning.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = "ЛОКАЛЬНО",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ClaudeWarning,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isDownloaded) {
                                    OutlinedButton(
                                        onClick = { onOpen(repo) },
                                        enabled = !isLoading,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Открыть", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { onPush(repo) },
                                        enabled = !isLoading,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Отправить", fontSize = 11.sp)
                                    }
                                }
                                Button(
                                    onClick = { onDownload(repo) },
                                    enabled = !isLoading,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isDownloaded) "Обновить" else "Скачать", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

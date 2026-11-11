package com.videohatak.player.feature.vault.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.vault.model.VaultVideo

@Composable
fun RenameVaultVideoDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعادة تسمية الفيديو المشفر", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("الاسم الجديد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title.trim())
                    }
                }
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun DeleteVaultVideoConfirmationDialog(
    video: VaultVideo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حذف من الخزنة المشفرة", fontWeight = FontWeight.Bold) },
        text = {
            Text("هل أنت متأكد من حذف \"${video.title}\" من الخزنة الخاصة؟ سيتم حذف النسخة المشفرة نهائيًا.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف نهائي", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun VaultSecurityInfoDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("معلومات الأمان والتشفير", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "• التشفير: يتم تشفير مقاطع الفيديو محليًا بنظام AES-256-GCM دون استخدام السحابة.\n" +
                            "• المفاتيح: تُدار مفاتيح التشفير داخل Android Keystore الآمن على المعالج.\n" +
                            "• الخصوصية: التطبيق يمنع لقطات الشاشة وتسجيل الشاشة عند فتح الخزنة.\n" +
                            "• سلامة ملفاتك: استيراد أي فيديو إلى الخزنة لا يحذف إطلاقًا ملف الفيديو الأصلي من ذاكرة هاتفك.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("حسناً")
            }
        }
    )
}

@Composable
fun ImportVideoProgressDialog(
    progress: Float,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Modal during crypto progress */ },
        title = { Text("جارٍ تشفير وحفظ الفيديو...", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("يتم الآن تطبيق تشفير AES-256-GCM على ملفك.")
                Spacer(modifier = Modifier.height(16.dp))
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("إلغاء العملية")
            }
        }
    )
}

@Composable
fun ConfirmDeleteVaultDialog(
    videoTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حذف الفيديو المشفر") },
        text = {
            Text("هل أنت متأكد من حذف \"$videoTitle\" نهائيًا من الخزنة المشفرة؟")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("حذف")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

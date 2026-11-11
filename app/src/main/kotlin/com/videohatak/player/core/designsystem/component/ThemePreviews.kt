package com.videohatak.player.core.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.videohatak.player.core.designsystem.theme.VideoHatakTheme

@Composable
fun DesignSystemShowcaseContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "فيديوهاتك — كما تحب (VideoHatak)",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "تَحْقِيقُ أَقْصَى دِقَّةٍ لِلْمُشَاهَدَةِ السِّينَمَائِيَّةِ مَعَ دَعْمِ التَّرْجَمَاتِ وَالأَمَانِ الْمُتَقَدِّمِ.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "بِطَاقَةُ التَّشْغِيلِ السِّينَمَائِيِّ (4K UHD)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "مَسَارُ الْمَلَفِّ: /storage/emulated/0/Movies/Cinematic_Sample.mkv",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = "بَحْثٌ فِي الْمَكْتَبَةِ وَالْخَزْنَةِ الْمُشَفَّرَةِ...",
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {},
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MaterialTheme.shapes.small
            ) {
                Text(text = "تَشْغِيلٌ الآن", style = MaterialTheme.typography.labelLarge)
            }

            OutlinedButton(
                onClick = {},
                shape = MaterialTheme.shapes.small
            ) {
                Text(text = "إِعْدَادَات", style = MaterialTheme.typography.labelLarge)
            }

            Button(
                onClick = {},
                enabled = false,
                shape = MaterialTheme.shapes.small
            ) {
                Text(text = "مُعَطَّل", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Preview(name = "Dark Theme", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PreviewDarkTheme() {
    VideoHatakTheme(darkTheme = true) {
        VideoHatakScreenSurface {
            DesignSystemShowcaseContent()
        }
    }
}

@Preview(name = "Light Theme", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun PreviewLightTheme() {
    VideoHatakTheme(darkTheme = false) {
        VideoHatakScreenSurface {
            DesignSystemShowcaseContent()
        }
    }
}

@Preview(name = "Arabic RTL with Tashkeel", locale = "ar")
@Composable
fun PreviewArabicRTL() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        VideoHatakTheme(darkTheme = true) {
            VideoHatakScreenSurface {
                DesignSystemShowcaseContent()
            }
        }
    }
}

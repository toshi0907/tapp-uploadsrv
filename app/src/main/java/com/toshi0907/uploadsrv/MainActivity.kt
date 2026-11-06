package com.toshi0907.uploadsrv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/** 保存先フォルダの表示用の状態。 */
private data class FolderState(val selected: Boolean, val name: String?, val valid: Boolean)

private fun AppSettings.folderState(): FolderState =
    FolderState(
        selected = saveFolderUri != null,
        name = saveFolder()?.name,
        valid = hasValidSaveFolder(),
    )

@Composable
private fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var folder by remember { mutableStateOf(settings.folderState()) }

    // 設定アプリ等で権限が外された場合に備え、画面復帰のたびに再確認する
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        folder = settings.folderState()
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { settings.updateSaveFolder(uri) }
            .onFailure {
                Toast.makeText(context, "フォルダの権限を取得できませんでした", Toast.LENGTH_LONG).show()
            }
        folder = settings.folderState()
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "保存先フォルダ", style = MaterialTheme.typography.titleMedium)
                val description = when {
                    !folder.selected -> "未選択"
                    !folder.valid -> "${folder.name ?: "(不明)"}(アクセス権限がありません。選び直してください)"
                    else -> folder.name ?: "(名前を取得できません)"
                }
                Text(
                    text = description,
                    color = if (folder.valid) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Button(onClick = { pickFolder.launch(null) }) {
                    Text(text = if (folder.valid) "フォルダを変更" else "フォルダを選択")
                }
            }
        }

        Text(
            text = "ビルド番号: ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

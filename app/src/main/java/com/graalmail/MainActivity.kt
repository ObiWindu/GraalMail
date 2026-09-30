package com.graalmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.graalmail.data.*
import com.graalmail.model.MailMessage
import com.graalmail.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = MailRepository(this, AppDatabase.create(this))
        setContent { AuroraTheme { MailApp(repo) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailApp(repo: MailRepository) {
    val vm: MailViewModel = viewModel(factory = MailViewModel.factory(repo))
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<MailMessage?>(null) }
    var compose by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.subject ?: "GraalMail") },
                navigationIcon = {
                    if (selected != null) IconButton({ selected = null }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton({ compose = true }) { Icon(Icons.Default.Edit, "Compose") }
                    }
                }
            )
        },
        floatingActionButton = {
            if (selected == null) FloatingActionButton({ compose = true }) {
                Icon(Icons.Default.Edit, "Compose")
            }
        }
    ) { pad ->
        if (selected == null) {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                items(state.messages, key = { it.id }) { m ->
                    ListItem(
                        headlineContent = { Text(m.subject.ifBlank { "(no subject)" }) },
                        supportingContent = { Text(m.from, maxLines = 1) },
                        leadingContent = { Icon(if (m.read) Icons.Default.Email else Icons.Default.MarkEmailUnread, null) },
                        trailingContent = { if (m.starred) Icon(Icons.Default.Star, null) },
                        modifier = Modifier.clickable { selected = m }
                    )
                    HorizontalDivider()
                }
            }
        } else {
            MessageView(selected!!, Modifier.padding(pad), vm)
        }
    }

    if (compose) ComposeDialog({ compose = false }) { compose = false }
}

@Composable
private fun MessageView(m: MailMessage, modifier: Modifier, vm: MailViewModel) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text(m.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text("From ${m.from}")
        Text("To ${m.to}")
        Spacer(Modifier.height(20.dp))
        Text(m.bodyText.ifBlank { m.bodyHtml })
        Spacer(Modifier.weight(1f))
        Row {
            Button({ vm.markRead(m.id) }) { Text("Mark read") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ vm.archive(m.id) }) { Text("Archive") }
        }
    }
}

@Composable
private fun ComposeDialog(close: () -> Unit, sent: () -> Unit) {
    var to by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("New message") },
        text = {
            Column {
                OutlinedTextField(to, { to = it }, label = { Text("To") })
                OutlinedTextField(subject, { subject = it }, label = { Text("Subject") })
                OutlinedTextField(body, { body = it }, label = { Text("Message") }, minLines = 5)
            }
        },
        confirmButton = { Button({ sent() }) { Text("Send") } },
        dismissButton = { TextButton(close) { Text("Cancel") } }
    )
}

@Composable
private fun AuroraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(), content = content)
}

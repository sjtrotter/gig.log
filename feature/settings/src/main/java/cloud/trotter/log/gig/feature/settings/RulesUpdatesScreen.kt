package cloud.trotter.log.gig.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.core.data.feed.RulesCheckOutcome
import cloud.trotter.log.gig.core.data.feed.RulesFeedStatus
import cloud.trotter.log.gig.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.log.gig.domain.format.formatShortDate

@Composable
fun RulesUpdatesScreen(
    onBack: () -> Unit,
    viewModel: RulesUpdatesViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    RulesUpdatesContent(
        enabled = enabled,
        status = status,
        onToggle = viewModel::setEnabled,
        onCheckNow = viewModel::checkNow,
        onBack = onBack,
    )
}

/**
 * Settings > Recognition rules, stateless (#640): the switch with its disclosure (the screen's one disclosure), which
 * rules are in use, "Check now" and what the last check found.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesUpdatesContent(
    enabled: Boolean,
    status: RulesFeedStatus,
    onToggle: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_updates_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            RulesUpdatesToggle(enabled = enabled, onToggle = onToggle)
            Spacer(Modifier.height(24.dp))
            LabelValue(
                label = stringResource(R.string.rules_updates_in_use_label),
                value = status.liveIssuedAt?.let { formatShortDate(it) }
                    ?: stringResource(R.string.rules_updates_in_use_built_in),
            )
            val formatTime = rememberTimeFormatter()
            val lastCheckAt = status.lastCheckAt
            val outcome = status.lastOutcome
            if (lastCheckAt != null && outcome != null) {
                LabelValue(
                    label = stringResource(R.string.rules_updates_last_check_label),
                    value = stringResource(R.string.rules_updates_last_check_format, formatTime(lastCheckAt), stringResource(outcome.label())),
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onCheckNow, enabled = !status.checking) {
                Text(
                    stringResource(
                        if (status.checking) R.string.rules_updates_checking else R.string.rules_updates_check_now,
                    ),
                )
            }
        }
    }
}

private fun RulesCheckOutcome.label(): Int = when (this) {
    RulesCheckOutcome.UPDATED -> R.string.rules_updates_outcome_updated
    RulesCheckOutcome.NO_UPDATE -> R.string.rules_updates_outcome_no_update
    RulesCheckOutcome.FAILED -> R.string.rules_updates_outcome_failed
}

/**
 * "Keep recognition rules up to date" and its disclosure: the network timing it allows, in plain words. Shared by
 * Settings and the setup wizard's question (hosted by `:app`), so both show the same words. The whole row toggles, and
 * TalkBack reads the label with its state.
 */
@Composable
fun RulesUpdatesToggle(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.rules_updates_toggle),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = null)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.rules_updates_disclosure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

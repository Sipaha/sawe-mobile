package ru.sipaha.sawe.app.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import ru.sipaha.sawe.app.R

/**
 * Which AI provider a session talks to, as the desktop shows it: the vendor's
 * mark in the vendor's colour. Mirrors the desktop's
 * `solution_agent::adapter::AgentBrand` (FORK.md #198, #203) — same agent ids,
 * same logos (converted from `assets/icons/ai_*.svg`), same colours — so a
 * session looks the same on the phone as on its desktop tab.
 *
 * Keyed on the wire's `agent_id`, which `SessionSummary`, `GetSessionResult`
 * and `list_agents` all carry; no protocol change was needed.
 */
internal enum class ProviderBrand(
    val agentId: String,
    val displayName: String,
    @DrawableRes val logo: Int,
    val color: Color,
) {
    /** Claude's orange — desktop `claude_adapter::BRAND.color`. */
    Claude("claude-acp", "Claude", R.drawable.ic_provider_claude, Color(0xFFD97757)),

    /** OpenAI's green — desktop `codex_adapter::BRAND.color`. */
    Codex("codex-native", "Codex", R.drawable.ic_provider_openai, Color(0xFF10A37F));

    companion object {
        /** The brand for [agentId], or null for an agent this app doesn't know. */
        fun forAgent(agentId: String?): ProviderBrand? = entries.firstOrNull { it.agentId == agentId }
    }
}

/**
 * The provider mark for [agentId] in its brand colour. An agent the app has no
 * brand for (a newer desktop's adapter, or an id not yet loaded) falls back to
 * a neutral chat glyph rather than a wrong logo.
 */
@Composable
internal fun ProviderLogo(agentId: String?, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val brand = ProviderBrand.forAgent(agentId)
    if (brand != null) {
        Icon(
            painter = painterResource(brand.logo),
            contentDescription = brand.displayName,
            tint = brand.color,
            modifier = modifier.size(size),
        )
    } else {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size),
        )
    }
}

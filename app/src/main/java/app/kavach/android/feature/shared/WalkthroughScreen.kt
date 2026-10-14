package app.kavach.android.feature.shared

import app.kavach.android.core.ui.components.hapticClick

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kavach.android.R
import app.kavach.android.core.data.model.UserRole
import app.kavach.android.core.ui.components.IconTile
import app.kavach.android.core.ui.components.kavachTween
import app.kavach.android.core.ui.components.rememberReduceMotion
import app.kavach.android.core.ui.theme.KavachTheme
import app.kavach.android.core.ui.theme.Spacing
import kotlinx.coroutines.launch

/** One card of the first-run walkthrough. */
data class IntroPage(val icon: ImageVector, @StringRes val title: Int, @StringRes val text: Int)

/** The walkthrough cards per role (4 each). Pure data, so it can be unit-tested. */
object IntroPages {
    fun forRole(role: UserRole): List<IntroPage> = when (role) {
        UserRole.Resident -> listOf(
            IntroPage(Icons.Rounded.Warning, R.string.shared_intro_res_report_title, R.string.shared_intro_res_report_text),
            IntroPage(Icons.Rounded.LocationOn, R.string.shared_intro_res_track_title, R.string.shared_intro_res_track_text),
            IntroPage(Icons.Rounded.CloudOff, R.string.shared_intro_res_offline_title, R.string.shared_intro_res_offline_text),
            IntroPage(Icons.Rounded.Notifications, R.string.shared_intro_res_loop_title, R.string.shared_intro_res_loop_text),
        )
        UserRole.Responder -> listOf(
            IntroPage(Icons.Rounded.Shield, R.string.shared_intro_rsp_duty_title, R.string.shared_intro_rsp_duty_text),
            IntroPage(Icons.Rounded.Notifications, R.string.shared_intro_rsp_claim_title, R.string.shared_intro_rsp_claim_text),
            IntroPage(Icons.Rounded.CheckCircle, R.string.shared_intro_rsp_steps_title, R.string.shared_intro_rsp_steps_text),
            IntroPage(Icons.Rounded.Build, R.string.shared_intro_rsp_checks_title, R.string.shared_intro_rsp_checks_text),
        )
        UserRole.Admin -> listOf(
            IntroPage(Icons.Rounded.Dashboard, R.string.shared_intro_adm_overview_title, R.string.shared_intro_adm_overview_text),
            IntroPage(Icons.Rounded.Schedule, R.string.shared_intro_adm_step_in_title, R.string.shared_intro_adm_step_in_text),
            IntroPage(Icons.Rounded.Apartment, R.string.shared_intro_adm_manage_title, R.string.shared_intro_adm_manage_text),
            IntroPage(Icons.Rounded.HealthAndSafety, R.string.shared_intro_adm_crisis_title, R.string.shared_intro_adm_crisis_text),
        )
    }
}

/**
 * First-run walkthrough: a few swipeable cards on how Kavach works for [role]. Shown once per role
 * on this phone (KavachRoot) and again from Profile → "How Kavach works". [onDone] runs on Skip
 * and on the last card's button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WalkthroughScreen(role: UserRole, onDone: () -> Unit) {
    val pages = remember(role) { IntroPages.forRole(role) }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val current = pager.currentPage
    val isLast = current == pages.lastIndex

    fun goTo(page: Int) {
        scope.launch { if (reduceMotion) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }

    // System back steps back through the cards; on the first card it does what Back normally does
    BackHandler(enabled = current > 0) { goTo(current - 1) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = Spacing.screen),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.shared_intro_heading),
                style = MaterialTheme.typography.titleSmall,
                color = KavachTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (!isLast) TextButton(onClick = hapticClick(onDone)) { Text(stringResource(R.string.shared_intro_skip)) }
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            IntroCard(pages[index])
        }

        PageDots(current = current, count = pages.size, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(Spacing.md))
        Button(
            onClick = hapticClick({ if (isLast) onDone() else goTo(current + 1) }),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text(stringResource(if (isLast) R.string.shared_intro_done else R.string.shared_intro_next))
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun IntroCard(page: IntroPage) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconTile(page.icon, KavachTheme.colors.primary, size = 96.dp)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(page.text),
            style = MaterialTheme.typography.bodyLarge,
            color = KavachTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Page indicator; the current dot is wider. TalkBack reads "Page 2 of 4" when it changes. */
@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.shared_intro_page_of, current + 1, count)
    Row(
        modifier.semantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val selected = i == current
            val width by animateDpAsState(if (selected) 20.dp else 8.dp, animationSpec = kavachTween(), label = "dot")
            Box(
                Modifier
                    .size(width = width, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (selected) KavachTheme.colors.primary else KavachTheme.colors.fill),
            )
        }
    }
}

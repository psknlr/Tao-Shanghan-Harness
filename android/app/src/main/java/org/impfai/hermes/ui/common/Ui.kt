package org.impfai.hermes.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.impfai.hermes.core.model.CitationReport
import org.impfai.hermes.core.model.ResultOrigin
import org.impfai.hermes.engine.TextNorm

/** 語料文本顯示：可選繁→簡（原文以繁體為準，僅顯示層轉換）。 */
fun String.display(simplified: Boolean): String =
    if (simplified) TextNorm.t2s(this) else this

/** 證據層 A/B/C/D/E 徽章（Hermes 證據分層）。 */
@Composable
fun LayerBadge(layer: String, label: String = "") {
    if (layer.isBlank()) return
    val (bg, fg) = when (layer) {
        "A" -> Color(0xFF2E5E4E) to Color.White
        "B" -> Color(0xFF5B7DB1) to Color.White
        "C" -> Color(0xFF8A6FB8) to Color.White
        "D" -> Color(0xFFB88A3C) to Color.White
        else -> Color(0xFF9E9E9E) to Color.White
    }
    Text(
        text = if (label.isBlank()) layer else "$layer·$label",
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 結果來源徽章：本地語料 vs 服務端（不可無差別展示）。 */
@Composable
fun OriginBadge(origin: ResultOrigin) {
    val (text, color) = when (origin) {
        ResultOrigin.LOCAL_CORPUS ->
            "本地" to MaterialTheme.colorScheme.tertiary
        ResultOrigin.SERVER ->
            "服务端" to MaterialTheme.colorScheme.primary
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 引用核驗徽章：✓ 已核驗 / △ 部分核驗 / ○ 無引用。 */
@Composable
fun CitationBadge(report: CitationReport?) {
    val (text, color) = when {
        report == null -> "○ 无引用" to MaterialTheme.colorScheme.outline
        report.ok -> "✓ 引用已核验" to Color(0xFF2E7D32)
        report.hasAnyCitation -> "△ 部分核验" to Color(0xFFB26A00)
        else -> "○ 无引用" to MaterialTheme.colorScheme.outline
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 安全聲明條（智能體/匹配結果統一底部聲明）。 */
@Composable
fun SafetyNoticeBar(notice: String) {
    if (notice.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Info, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(end = 6.dp, top = 2.dp),
        )
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 提示條（離線回退等）。 */
@Composable
fun NoticeBar(text: String, warning: Boolean = false) {
    if (text.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (warning) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (warning) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (warning) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (warning) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 標題 + 內容的分節卡片（條文詳情/教學頁通用）。 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

package com.tvbox.app.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.max

/**
 * 海报网格列数：先按最小宽度自适应，再保证「一整张卡（海报 + 下方文字）」放得进网格可见高度。
 *
 * 电视常见 1080p / density 320，只有 960×540dp；顶部再放标题和搜索框后网格可能只剩 ~270dp，
 * 而一张 2:3 海报卡就超过 300dp。卡比可见区域高时，焦点滚动只能对齐卡的上沿，片名就被挤出屏幕。
 * 这里在这种情况下多加列、把卡变窄变矮。
 */
class PosterGridCells(
    private val minSize: Dp,
    private val viewportHeight: Dp,
    private val posterAspectRatio: Float,
    private val textBlockHeight: Dp,
) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        var count = max((availableSize + spacing) / (minSize.roundToPx() + spacing), 1)
        // 给焦点放大（1.04x）和描边留余量
        val maxCardHeight = viewportHeight.toPx() * FIT_RATIO
        val maxCellWidth = (maxCardHeight - textBlockHeight.toPx()) * posterAspectRatio
        if (maxCellWidth > 0f) {
            while (count < MAX_COLUMNS && cellWidth(availableSize, spacing, count) > maxCellWidth) count++
        }
        val usable = availableSize - spacing * (count - 1)
        val slot = usable / count
        val remainder = usable % count
        return List(count) { slot + if (it < remainder) 1 else 0 }
    }

    private fun cellWidth(availableSize: Int, spacing: Int, count: Int): Float =
        (availableSize - spacing * (count - 1)).toFloat() / count

    override fun equals(other: Any?): Boolean =
        other is PosterGridCells && other.minSize == minSize && other.viewportHeight == viewportHeight &&
            other.posterAspectRatio == posterAspectRatio && other.textBlockHeight == textBlockHeight

    override fun hashCode(): Int =
        ((minSize.hashCode() * 31 + viewportHeight.hashCode()) * 31 + posterAspectRatio.hashCode()) * 31 +
            textBlockHeight.hashCode()

    private companion object {
        const val FIT_RATIO = 0.9f
        const val MAX_COLUMNS = 10
    }
}

/** 估算卡片下方文字区高度：每行按字号 1.4 倍行高，加上竖向内边距。字号随「字体大小」设置缩放。 */
@Composable
fun posterTextBlockHeight(lines: List<TextStyle>, verticalPadding: Dp): Dp {
    return with(LocalDensity.current) {
        lines.fold(verticalPadding) { total, style -> total + style.fontSize.toDp() * TEXT_LINE_HEIGHT_RATIO }
    }
}

private const val TEXT_LINE_HEIGHT_RATIO = 1.4f

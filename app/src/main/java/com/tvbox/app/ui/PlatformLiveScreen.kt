package com.tvbox.app.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.tvbox.app.data.BilibiliLoginStatus
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tvbox.app.domain.PlatformLiveCategory
import com.tvbox.app.domain.PlatformLiveParentCategory
import com.tvbox.app.domain.PlatformLiveRoom
import com.tvbox.app.domain.PlatformLiveSite
import com.tvbox.app.domain.ResolvedPlatformLiveStream
import com.tvbox.app.ui.components.ErrorState
import com.tvbox.app.ui.components.LoadingState
import com.tvbox.app.ui.components.PageSurface
import com.tvbox.app.ui.components.tvFocusScale
import com.tvbox.app.ui.theme.TvLayout
import kotlinx.coroutines.delay

@Composable
fun PlatformLiveScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    BackHandler { actions.goBack() }
    if (state.bilibiliLoginDialogVisible) {
        BilibiliLoginDialog(state = state, actions = actions)
    }
    when (state.platformLiveDestination) {
        PlatformLiveDestination.Sites -> PlatformLiveSitesScreen(state = state, actions = actions)
        PlatformLiveDestination.Favorites -> PlatformLiveRoomsScreen(state = state, actions = actions, favoritesMode = true)
        PlatformLiveDestination.ParentCategories -> PlatformLiveParentCategoriesScreen(state = state, actions = actions)
        PlatformLiveDestination.Categories -> PlatformLiveCategoriesScreen(state = state, actions = actions)
        PlatformLiveDestination.Rooms -> PlatformLiveRoomsScreen(state = state, actions = actions)
        PlatformLiveDestination.Player -> PlatformLivePlayerScreen(state = state, actions = actions)
    }
}

@Composable
private fun PlatformLiveSitesScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val firstCardFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstCardFocusRequester.requestFocus() }
    PlatformLiveBrowseSurface(
        title = "直播",
        subtitle = when {
            state.platformLiveLoading -> "正在加载直播平台，收藏仍可使用"
            state.platformLiveError != null -> "平台加载失败：${state.platformLiveError}"
            state.platformLiveFavoriteError != null -> "收藏读取失败：${state.platformLiveFavoriteError}"
            else -> "选择直播平台或本机收藏"
        },
        loading = false,
        error = null,
        isEmpty = false,
        emptyMessage = "没有可用平台直播服务",
        onRetry = actions::refreshPlatformLive,
    ) {
        item(key = "favorites") {
            PlatformLiveTextCard(
                title = "收藏",
                subtitle = "本机已收藏 ${state.platformLiveFavorites.size} 个房间",
                label = "我的收藏",
                onClick = actions::openPlatformLiveFavorites,
                modifier = Modifier.focusRequester(firstCardFocusRequester),
            )
        }
        itemsIndexed(state.platformLiveSites, key = { _, site -> site.id }) { _, site ->
            PlatformLiveSiteCard(
                site = site,
                onClick = { actions.selectPlatformLiveSite(site) },
            )
        }
        if (state.platformLiveError != null) {
            item(key = "retry-platforms") {
                PlatformLiveTextCard(
                    title = "重试加载平台",
                    subtitle = "收藏不受平台列表加载失败影响",
                    label = "重试",
                    onClick = actions::refreshPlatformLive,
                )
            }
        }
    }
}

@Composable
private fun PlatformLiveParentCategoriesScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val firstCardFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.platformLiveParentCategories) {
        if (state.platformLiveParentCategories.isNotEmpty()) firstCardFocusRequester.requestFocus()
    }
    val site = state.platformLiveSelectedSite
    PlatformLiveBrowseSurface(
        title = site?.name ?: "直播",
        subtitle = "选择直播大类",
        loading = state.platformLiveLoading,
        error = state.platformLiveError,
        isEmpty = state.platformLiveParentCategories.isEmpty(),
        emptyMessage = "没有可用直播大分类",
        onRetry = actions::refreshPlatformLive,
        headerAction = if (site?.id == "bilibili") {
            {
                val login = state.bilibiliLogin
                val (statusText, statusColor) = bilibiliLoginStatusLine(login, state.bilibiliLoginStatusError)
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 18.dp)) {
                    SettingsActionButton(
                        text = when {
                            login?.loggedIn == true -> "B站账号"
                            login?.expired == true -> "重新扫码登录 B站"
                            else -> "扫码登录 B站 · 看原画"
                        },
                        onClick = actions::openBilibiliLoginDialog,
                        selected = login?.loggedIn == true,
                    )
                    Text(
                        text = statusText,
                        modifier = Modifier.padding(top = 6.dp, end = 6.dp),
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            null
        },
    ) {
        itemsIndexed(
            state.platformLiveParentCategories,
            key = { _, category -> category.id },
        ) { index, category ->
            val childCount = state.platformLiveCategories.count { it.parentId == category.id }
            PlatformLiveParentCategoryCard(
                category = category,
                childCount = childCount,
                onClick = { actions.selectPlatformLiveParentCategory(category) },
                modifier = if (index == 0) Modifier.focusRequester(firstCardFocusRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun PlatformLiveCategoriesScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val firstCardFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.platformLiveCategories) {
        if (state.platformLiveCategories.isNotEmpty()) firstCardFocusRequester.requestFocus()
    }
    val site = state.platformLiveSelectedSite
    val parentCategory = state.platformLiveSelectedParentCategory
    val categories = state.platformLiveCategories.filter { it.parentId == parentCategory?.id }
    PlatformLiveBrowseSurface(
        title = parentCategory?.name ?: site?.name ?: "直播分类",
        subtitle = "选择二级分类",
        loading = state.platformLiveLoading,
        error = state.platformLiveError,
        isEmpty = categories.isEmpty(),
        emptyMessage = "没有可用二级分类",
        onRetry = actions::refreshPlatformLive,
    ) {
        itemsIndexed(categories, key = { _, category -> category.id }) { index, category ->
            PlatformLiveCategoryCard(
                category = category,
                onClick = { actions.selectPlatformLiveCategory(category) },
                modifier = if (index == 0) Modifier.focusRequester(firstCardFocusRequester) else Modifier,
            )
        }
    }
}

@Composable
private fun PlatformLiveRoomsScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
    favoritesMode: Boolean = false,
) {
    val gridState = rememberLazyGridState()
    val selectedRoomFocusRequester = remember { FocusRequester() }
    val emptyFavoriteFocusRequester = remember { FocusRequester() }
    val selectedRoomIndex = state.platformLiveRoomIndex.coerceIn(
        0,
        state.platformLiveRooms.lastIndex.coerceAtLeast(0),
    )
    LaunchedEffect(
        state.platformLiveDestination,
        state.platformLiveRoomIndex,
        state.platformLiveRooms.map(PlatformLiveRoom::id),
    ) {
        if (state.platformLiveRooms.isEmpty()) return@LaunchedEffect
        // 目标卡片已经在屏幕上（如加载更多后，新房间就在原“加载更多”的位置）就不滚动，直接移光标，避免页面跳动
        withFrameNanos { }
        val visible = gridState.layoutInfo.visibleItemsInfo.any { it.index == selectedRoomIndex }
        if (!visible) {
            gridState.scrollToItem(selectedRoomIndex)
            withFrameNanos { }
        }
        runCatching { selectedRoomFocusRequester.requestFocus() }
    }
    LaunchedEffect(favoritesMode, state.platformLiveRooms.isEmpty()) {
        if (favoritesMode && state.platformLiveRooms.isEmpty()) {
            runCatching { emptyFavoriteFocusRequester.requestFocus() }
        }
    }
    val category = state.platformLiveSelectedCategory
    val site = state.platformLiveSelectedSite
    if (favoritesMode && state.platformLiveRooms.isEmpty()) {
        PageSurface { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("还没有收藏的直播房间", style = MaterialTheme.typography.titleMedium)
                state.platformLiveFavoriteError?.let { error ->
                    Text(text = "收藏读取失败：$error", color = MaterialTheme.colorScheme.error)
                }
                PlatformLiveTextCard(
                    title = "返回直播平台",
                    subtitle = "在直播间卡片上长按确认键（或按菜单键）即可收藏",
                    label = "收藏",
                    onClick = actions::goBack,
                    modifier = Modifier.padding(top = 20.dp).width(280.dp).focusRequester(emptyFavoriteFocusRequester),
                )
            }
        }
        return
    }
    PlatformLiveBrowseSurface(
        title = if (favoritesMode) "收藏" else category?.name ?: "直播间",
        subtitle = (if (favoritesMode) "本机收藏的直播房间" else category?.parentName?.ifBlank { site?.name ?: "直播" } ?: site?.name ?: "直播") +
            if (favoritesMode) " · 长按确认键（或菜单键）取消收藏" else " · 长按确认键（或菜单键）收藏",
        loading = !favoritesMode && state.platformLiveLoading,
        error = if (favoritesMode) null else state.platformLiveError,
        isEmpty = state.platformLiveRooms.isEmpty(),
        emptyMessage = if (favoritesMode) "还没有收藏的直播房间" else "这个分类暂时没有正在直播的房间",
        onRetry = actions::refreshPlatformLive,
        gridState = gridState,
        notice = state.platformLiveFavoriteError,
    ) {
        itemsIndexed(state.platformLiveRooms, key = { _, room -> room.id }) { index, room ->
            PlatformLiveRoomCard(
                room = room,
                onClick = { actions.openPlatformLiveRoom(room) },
                isFavorite = state.platformLiveFavorites.any { it.id == room.id },
                onToggleFavorite = { actions.togglePlatformLiveFavorite(room) },
                favoritesMode = favoritesMode,
                modifier = if (index == selectedRoomIndex) {
                    Modifier.focusRequester(selectedRoomFocusRequester)
                } else {
                    Modifier
                },
            )
        }
        if (!favoritesMode && state.platformLiveRoomPage < state.platformLiveRoomPageCount) {
            item(key = "load-more") {
                PlatformLiveLoadMoreCard(
                    loading = state.platformLiveLoadingMore,
                    text = "已加载 ${state.platformLiveRoomPage}/${state.platformLiveRoomPageCount} 页",
                    onClick = actions::loadMorePlatformLiveRooms,
                )
            }
        }
    }
}

@Composable
private fun PlatformLiveBrowseSurface(
    title: String,
    subtitle: String,
    loading: Boolean,
    error: String?,
    isEmpty: Boolean,
    emptyMessage: String,
    onRetry: () -> Unit,
    gridState: LazyGridState? = null,
    notice: String? = null,
    headerAction: (@Composable () -> Unit)? = null, // 标题行最右边的按钮
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
) {
    PageSurface { padding ->
        when {
            loading -> LoadingState(text = "正在加载$title", modifier = Modifier.padding(padding))
            error != null -> ErrorState(message = error, onRetry = onRetry, modifier = Modifier.padding(padding))
            isEmpty -> ErrorState(message = emptyMessage, onRetry = onRetry, modifier = Modifier.padding(padding))
            else -> {
                val resolvedGridState = gridState ?: rememberLazyGridState()
                val headerVisible by remember {
                    derivedStateOf {
                        resolvedGridState.firstVisibleItemIndex == 0 && resolvedGridState.firstVisibleItemScrollOffset < 24
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                ) {
                    AnimatedVisibility(visible = headerVisible) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = subtitle,
                                        modifier = Modifier.padding(top = 5.dp, bottom = 18.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                headerAction?.invoke()
                            }
                            if (notice != null) {
                                Text(text = notice, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    LazyVerticalGrid(
                        state = resolvedGridState,
                        columns = GridCells.Fixed(TvLayout.PlatformLiveColumns),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformLiveSiteCard(
    site: PlatformLiveSite,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformLiveTextCard(
        title = site.name,
        subtitle = site.description.ifBlank { "直播分类与房间浏览" },
        label = "直播平台",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun PlatformLiveParentCategoryCard(
    category: PlatformLiveParentCategory,
    childCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformLiveTextCard(
        title = category.name,
        subtitle = if (childCount > 0) "$childCount 个二级分类" else "选择二级分类",
        label = "",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun PlatformLiveCategoryCard(
    category: PlatformLiveCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlatformLiveImageCard(
        title = category.name,
        subtitle = category.parentName.ifBlank { "直播分类" },
        imageUrl = category.cover,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun PlatformLiveRoomCard(
    room: PlatformLiveRoom,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    favoritesMode: Boolean,
    modifier: Modifier = Modifier,
) {
    PlatformLiveImageCard(
        title = room.title,
        subtitle = if (favoritesMode) {
            listOf(room.anchor, room.site.uppercase()).filter { it.isNotBlank() }.joinToString(" / ")
        } else {
            listOf(room.anchor, formatOnline(room.online)).filter { it.isNotBlank() }.joinToString(" / ")
        },
        imageUrl = room.cover,
        onClick = onClick,
        favorite = isFavorite,
        onFavoriteClick = onToggleFavorite,
        modifier = modifier,
    )
}

@Composable
private fun PlatformLiveTextCard(
    title: String,
    subtitle: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusScale(shape = shape)
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .background(if (focused) Color(0xFF252525) else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .focusable()
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (label.isNotBlank()) {
            Text(text = label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlatformLiveImageCard(
    title: String,
    subtitle: String,
    imageUrl: String,
    onClick: () -> Unit,
    favorite: Boolean? = null,
    onFavoriteClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // 遥控器：长按确认键或按菜单键 = 收藏 / 取消收藏（星星在卡片里，方向键移不到它上面）
    var longPressHandled by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusScale(shape = shape)
                .clip(shape)
                .onFocusChanged { focused = it.isFocused || it.hasFocus }
                .background(if (focused) Color(0xFF252525) else MaterialTheme.colorScheme.surface)
                .onPreviewKeyEvent { event ->
                    if (onFavoriteClick == null) return@onPreviewKeyEvent false
                    val native = event.nativeKeyEvent
                    val isOk = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == AndroidKeyEvent.KEYCODE_ENTER
                    when {
                        native.keyCode == AndroidKeyEvent.KEYCODE_MENU -> {
                            if (event.type == KeyEventType.KeyUp) onFavoriteClick()
                            true
                        }
                        isOk && event.type == KeyEventType.KeyDown && native.repeatCount > 0 -> {
                            if (!longPressHandled) {
                                longPressHandled = true
                                onFavoriteClick()
                            }
                            true
                        }
                        isOk && event.type == KeyEventType.KeyUp && longPressHandled -> {
                            longPressHandled = false
                            true // 长按松开不再当作点击进入直播间
                        }
                        else -> false
                    }
                }
                .clickable(onClick = onClick)
                .focusable(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "直播",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = title,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = if (favorite == null) 12.dp else 62.dp),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle.ifBlank { "正在直播" },
                modifier = Modifier.padding(start = 12.dp, top = 3.dp, end = if (favorite == null) 12.dp else 62.dp, bottom = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (favorite != null && onFavoriteClick != null) {
            var starFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 8.dp)
                    .size(44.dp)
                    .tvFocusScale(shape = MaterialTheme.shapes.small)
                    .clip(MaterialTheme.shapes.small)
                    .focusProperties { canFocus = false } // 只做标记；遥控器用长按确认 / 菜单键，手机可直接点
                    .onFocusChanged { starFocused = it.isFocused }
                    .background(if (starFocused) MaterialTheme.colorScheme.primary else Color(0xCC101010))
                    .semantics { contentDescription = if (favorite) "取消收藏 $title" else "收藏 $title" }
                    .clickable(onClick = onFavoriteClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (favorite) "★" else "☆",
                    color = if (starFocused) MaterialTheme.colorScheme.onPrimary else Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
    }
}

@Composable
private fun PlatformLiveLoadMoreCard(loading: Boolean, text: String, onClick: () -> Unit) {
    // 和直播间卡片同样的结构和高度（16:9 区域 + 两行字）：加载后新房间正好填进这个位置，
    // 光标移过去不需要任何滚动
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusScale(shape = shape)
            .clip(shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .background(if (focused) Color(0xFF252525) else MaterialTheme.colorScheme.surface)
            .clickable(enabled = !loading, onClick = onClick)
            .focusable(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
            } else {
                Text(text = "＋", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = if (loading) "正在加载…" else "加载更多",
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 12.dp, top = 3.dp, end = 12.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlatformLivePlayerScreen(
    state: TvBoxUiState,
    actions: TvBoxViewModel,
) {
    val room = state.platformLiveRooms.getOrNull(state.platformLiveRoomIndex)
    if (room == null) {
        PageSurface { padding ->
            ErrorState(
                message = "未选择直播间",
                onRetry = { actions.goBack() },
                modifier = Modifier.padding(padding),
            )
        }
        return
    }
    KeepScreenOnWhileVisible()
    val stream = state.platformLiveStream?.takeIf { it.channelId == room.id }
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(stream?.url, stream?.headers) {
        stream?.let { resolved ->
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(resolved.headers)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply { playWhenReady = true }
        }
    }
    val playerFocusRequester = remember { FocusRequester() }
    val session = rememberPlaybackSession()
    BindPlaybackSession(session, player)
    var playbackError by remember(stream?.url) { mutableStateOf<String?>(null) }
    var playerState by remember(stream?.url) { mutableIntStateOf(Player.STATE_IDLE) }
    var badgeVisible by remember { mutableStateOf(true) }
    // 每次显示信息卡片都重新计时，到时间自动隐藏（按确认 / 换线路后也会消失）
    var badgeShowCount by remember { mutableIntStateOf(0) }
    val showBadge = {
        badgeVisible = true
        badgeShowCount++
    }
    LaunchedEffect(badgeShowCount) {
        delay(PLATFORM_LIVE_BADGE_HIDE_DELAY_MS)
        badgeVisible = false
    }

    DisposableEffect(player) {
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (!session.canPlay) return
                    playbackError = error.localizedMessage ?: "平台直播播放失败"
                    showBadge()
                    actions.recoverPlatformLiveChannel()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    playerState = playbackState
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }
    }

    LaunchedEffect(player, stream?.url) {
        if (player == null || stream == null) return@LaunchedEffect
        playbackError = null
        showBadge()
        player.setMediaItem(MediaItem.fromUri(stream.url))
        player.prepare()
        session.preparePlayback(player)
    }

    LaunchedEffect(playerState, player, stream?.url, session.canPlay) {
        if (player == null || stream == null || !session.canPlay) return@LaunchedEffect
        when (playerState) {
            Player.STATE_ENDED -> actions.recoverPlatformLiveChannel()
            Player.STATE_BUFFERING -> {
                delay(8_000L)
                if (player.playbackState == Player.STATE_BUFFERING) {
                    playbackError = "直播连接中断，正在重新获取播放地址"
                    showBadge()
                    actions.recoverPlatformLiveChannel()
                }
            }
        }
    }

    LaunchedEffect(Unit) { playerFocusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) {
                    return@onPreviewKeyEvent event.nativeKeyEvent.keyCode in PLATFORM_LIVE_PLAYER_KEYS
                }
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        actions.playPreviousPlatformLiveRoom()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        actions.playNextPlatformLiveRoom()
                        true
                    }
                    // 上下键换 CDN 线路
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        actions.switchPlatformLiveCandidate(-1)
                        showBadge()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        actions.switchPlatformLiveCandidate(1)
                        showBadge()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    -> {
                        if (session.interrupted && player != null) {
                            session.play(player)
                            actions.reconnectPlatformLiveChannel()
                        } else if (state.platformLiveError != null) actions.reconnectPlatformLiveChannel()
                        showBadge()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        isFocusable = false
                        isFocusableInTouchMode = false
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (session.interrupted && player != null) {
            ControlButton("继续播放", {
                session.play(player)
                actions.reconnectPlatformLiveChannel()
            }, Modifier.align(Alignment.Center))
        }
        if (badgeVisible || state.platformLiveResolving || state.platformLiveError != null) {
            PlatformLivePlayerBadge(
                room = room,
                stream = stream,
                error = state.platformLiveError ?: playbackError,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(28.dp),
            )
        }
        if (state.platformLiveResolving) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text("正在解析 ${room.title}", color = Color.White)
            }
        }
    }
}

@Composable
private fun PlatformLivePlayerBadge(
    room: PlatformLiveRoom,
    stream: ResolvedPlatformLiveStream?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(Color(0x99000000))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = stream?.title ?: room.title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stream?.let {
                "${it.quality} / ${it.protocol.uppercase()} / ${it.activeCandidate.cdn} ${it.activeCandidateIndex + 1}/${it.candidates.size}"
            } ?: room.anchor,
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatOnline(value: Int): String {
    return when {
        value <= 0 -> "正在直播"
        value < 10_000 -> "$value 人气"
        else -> "${value / 10_000}.${(value % 10_000) / 1_000} 万人气"
    }
}

private const val PLATFORM_LIVE_BADGE_HIDE_DELAY_MS = 4_000L

/** B站登录状态一行字：账号、Cookie 是否有效、到期日。 */
@Composable
private fun bilibiliLoginStatusLine(login: BilibiliLoginStatus?, error: String?): Pair<String, Color> {
    val ok = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bad = MaterialTheme.colorScheme.error
    val expiry = login?.expiresAt?.takeIf { it.isNotBlank() }?.let { " · 有效期至 $it" }.orEmpty()
    return when {
        error != null -> "无法查询登录状态：$error" to bad
        login == null -> "正在检查 B站登录…" to muted
        login.loggedIn && login.verified -> "✓ 已登录：${login.name.ifBlank { "B站账号" }} · Cookie 有效$expiry" to ok
        login.loggedIn -> "已保存登录，暂时无法向 B站确认是否有效$expiry" to muted
        login.expired -> "✗ 登录已失效（Cookie 过期），只能看超清" to bad
        else -> "未登录 · 只能看超清 720p" to muted
    }
}

private val PLATFORM_LIVE_PLAYER_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_LEFT,
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_DPAD_UP,
    AndroidKeyEvent.KEYCODE_DPAD_DOWN,
)

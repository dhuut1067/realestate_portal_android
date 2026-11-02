package kr.co.estate.portal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.location.Geocoder
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.CompetitionUnit
import com.kakao.vectormap.label.OrderingType
import com.kakao.vectormap.label.CompetitionType
import com.kakao.vectormap.label.LabelLayerOptions
import com.kakao.vectormap.label.LabelTransition
import com.kakao.vectormap.label.Transition
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Locale
import kotlin.math.roundToInt

private val SEOUL_CITY_HALL: LatLng get() = LatLng.from(37.5665, 126.9780)

// Fit the actual registered apartment coordinates, irrespective of transaction prices.
// The Django endpoint uses province/district codes, so no province-specific map presets
// or fixed-distance search radii are necessary.
private fun fitAdministrativeBounds(
    current: KakaoMap, bounds: MapBounds, density: Float, maxZoom: Int
) {
    val latitudePadding = ((bounds.north - bounds.south) * 0.07).coerceAtLeast(0.003)
    val longitudePadding = ((bounds.east - bounds.west) * 0.07).coerceAtLeast(0.003)
    val corners = arrayOf(
        LatLng.from(bounds.south - latitudePadding, bounds.west - longitudePadding),
        LatLng.from(bounds.north + latitudePadding, bounds.east + longitudePadding)
    )
    current.moveCamera(CameraUpdateFactory.fitMapPoints(
        corners, (12f * density).toInt(), maxZoom))
}

// Use all four screen corners: also covers rotated maps. SDK 2.15.1 API.
private fun viewportBounds(map: KakaoMap): MapBounds? {
    val rect = map.viewport
    if (rect.width() <= 0 || rect.height() <= 0) return null
    val points = listOf(
        map.fromScreenPoint(rect.left, rect.top), map.fromScreenPoint(rect.right - 1, rect.top),
        map.fromScreenPoint(rect.left, rect.bottom - 1), map.fromScreenPoint(rect.right - 1, rect.bottom - 1)
    )
    if (points.any { it == null }) return null
    val valid = points.filterNotNull()
    return MapBounds(valid.minOf { it.longitude }, valid.minOf { it.latitude },
        valid.maxOf { it.longitude }, valid.maxOf { it.latitude })
}

@Composable
fun KakaoApartmentMap(
    selected: ServerApartment?, region: String, regionDepth: Int,
    focusRequest: Int, mapHeight: Dp, showMap: Boolean = true, onSelect: (ServerApartment) -> Unit,
    initialZoom: Int = 0, sidoCode: String = "", provinceName: String = "", districtCode: String = "", localityName: String? = null
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val select by rememberUpdatedState(onSelect)
    var map by remember { mutableStateOf<KakaoMap?>(null) }
    var bounds by remember { mutableStateOf<MapBounds?>(null) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var positionError by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var positionLoading by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var apartmentsOnMap by remember { mutableStateOf<List<ServerApartment>>(emptyList()) }
    var regionsOnMap by remember { mutableStateOf<List<RegionMarker>>(emptyList()) }
    var displayedLevel by remember { mutableIntStateOf(3) }
    var labelZoom by remember { mutableIntStateOf(15) }
    var labelSizeStep by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var explicitLevel by remember { mutableStateOf<Int?>(null) }
    var lastNotice by remember { mutableStateOf("지도 준비 중입니다.") }
    val appKey = BuildConfig.KAKAO_NATIVE_APP_KEY
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("실거래 지도", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { labelSizeStep-- }, enabled = labelSizeStep > -2) { Text("작게") }
            TextButton(onClick = { labelSizeStep++ }, enabled = labelSizeStep < 2) { Text("크게") }
        }
        if (appKey.isBlank()) {
            Text("카카오 네이티브 앱 키가 없습니다.", color = MaterialTheme.colorScheme.error)
        } else {
            val view = remember(context, appKey) {
                KakaoMapSdk.init(context.applicationContext, appKey)
                MapView(context).apply {
                    setOnTouchListener { v, event ->
                        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                            explicitLevel = null // pinch: restore zoom-dependent group levels
                        }
                        v.parent?.requestDisallowInterceptTouchEvent(
                            event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                        false
                    }
                }
            }
            DisposableEffect(view, owner) {
                var active = true
                fun publishBounds(current: KakaoMap) {
                    view.post {
                        if (active) { labelZoom = current.zoomLevel; bounds = viewportBounds(current) }
                    }
                }
                val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    map?.let(::publishBounds)
                }
                view.addOnLayoutChangeListener(layoutListener)
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> view.resume()
                        Lifecycle.Event.ON_PAUSE -> view.pause()
                        else -> Unit
                    }
                }
                owner.lifecycle.addObserver(observer)
                view.start(object : MapLifeCycleCallback() {
                    override fun onMapDestroy() {}
                    override fun onMapError(error: Exception) {
                        view.post { if (active) mapError = "지도 연결 실패: ${error.message}" }
                    }
                }, object : KakaoMapReadyCallback() {
                    override fun getPosition(): LatLng = SEOUL_CITY_HALL
                    override fun getZoomLevel(): Int = 15
                    override fun onMapReady(current: KakaoMap) {
                        current.labelManager?.addLayer(LabelLayerOptions.from("actual-trades")
                            .setCompetitionType(CompetitionType.None)
                            .setCompetitionUnit(CompetitionUnit.IconAndText)
                            .setOrderingType(OrderingType.Rank)
                            .setZOrder(5000).setClickable(true))
                        current.setOnLabelClickListener { _, _, label ->
                            val apartment = label.tag as? ServerApartment
                            val regionMarker = label.tag as? RegionMarker
                            if (regionMarker != null) {
                                // A cluster click advances the DATA level, not only camera zoom.
                                explicitLevel = when (regionMarker.type) {
                                    "sido" -> 8
                                    "sigungu" -> 5
                                    else -> 3
                                }
                                bounds = null
                                val nextZoom = when (regionMarker.type) {
                                    "sido" -> 13
                                    "sigungu" -> 15
                                    else -> 17
                                }
                                current.moveCamera(CameraUpdateFactory.newCenterPosition(
                                    LatLng.from(regionMarker.latitude, regionMarker.longitude), nextZoom))
                            }
                            view.post { if (active && apartment != null) select(apartment) }
                            true
                        }
                        current.setOnCameraMoveStartListener { _, _ ->
                            // Invalidate pending network results as soon as the camera moves.
                            view.post { if (active) bounds = null }
                        }
                        current.setOnCameraMoveEndListener { k, _, _ -> publishBounds(k) }
                        view.post {
                            if (active) { map = current; mapError = null; publishBounds(current) }
                        }
                    }
                })
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
                onDispose {
                    active = false
                    view.removeOnLayoutChangeListener(layoutListener)
                    owner.lifecycle.removeObserver(observer)
                    map = null; bounds = null
                    view.pause(); view.finish()
                }
            }
            AndroidView(factory = { view }, update = { it.visibility = if (showMap) View.VISIBLE else View.INVISIBLE }, modifier = Modifier.fillMaxWidth().height(mapHeight))

            // This effect ONLY moves the camera. Loading results never moves it back.
            LaunchedEffect(map, selected?.identity, region, regionDepth, focusRequest, initialZoom, sidoCode, districtCode, localityName) {
                val current = map ?: return@LaunchedEffect
                positionError = null; positionLoading = true
                explicitLevel = when {
                    selected != null || regionDepth >= 3 -> 3
                    regionDepth == 2 -> 5
                    else -> 8
                }
                try {
                    val point: LatLng
                    val zoom: Int
                    if (selected != null) {
                        val resolved = resolveApartment(selected)
                        point = if (resolved.latitude != null && resolved.longitude != null) {
                            LatLng.from(resolved.latitude, resolved.longitude)
                        } else {
                            withContext(Dispatchers.IO) {
                                @Suppress("DEPRECATION")
                                val address = Geocoder(context, Locale.KOREA)
                                    .getFromLocationName("$region ${selected.name}", 1)?.firstOrNull()
                                    ?: error("선택 단지의 좌표를 찾지 못했습니다.")
                                LatLng.from(address.latitude, address.longitude)
                            }
                        }
                        zoom = 17
                    } else if (sidoCode.isNotBlank()) {
                        // One universal rule for all provinces, districts and localities:
                        // derive map bounds from every registered geocoded apartment.
                        val focus = loadRegionFocus(
                            sidoCode,
                            if (regionDepth >= 2) districtCode else "",
                            if (regionDepth >= 3) localityName.orEmpty() else ""
                        )
                        val geographicBounds = focus.bounds
                            ?: error("선택 지역에 좌표가 등록된 아파트가 없습니다. (등록 좌표 0곳)")
                        bounds = null
                        fitAdministrativeBounds(current, geographicBounds,
                            context.resources.displayMetrics.density,
                            when {
                                regionDepth >= 3 -> 17
                                regionDepth == 2 -> 15
                                else -> 13
                            })
                        lastNotice = "$region · 등록 단지 ${focus.count}곳 · 그룹 ${focus.groupCount}개"
                        // Camera move-end publishes the new viewport and zoom.
                        return@LaunchedEffect
                    } else if (regionDepth == 1 && region.isBlank()) {
                        point = SEOUL_CITY_HALL; zoom = 12
                    } else {
                        point = withContext(Dispatchers.IO) {
                            @Suppress("DEPRECATION")
                            val address = Geocoder(context, Locale.KOREA).getFromLocationName(region, 1)?.firstOrNull()
                                ?: error("$region 중심 좌표를 찾지 못했습니다.")
                            LatLng.from(address.latitude, address.longitude)
                        }
                        zoom = if (initialZoom > 0) initialZoom else if (regionDepth >= 3) 16 else if (regionDepth == 2) 14 else 12
                    }
                    bounds = null
                    current.moveCamera(CameraUpdateFactory.newCenterPosition(point, zoom))
                    // Explicit refresh also covers selecting an already centered location.
                    view.post { if (map === current) bounds = viewportBounds(current) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { positionError = "위치 이동 실패: ${e.message}" }
                finally { positionLoading = false }
            }

            LaunchedEffect(map, bounds, labelZoom, retry, explicitLevel) {
                val current = map ?: return@LaunchedEffect
                val visible = bounds ?: return@LaunchedEffect
                delay(350) // Coalesce rapid gestures; cancellation discards stale responses.
                loading = true; loadError = null
                lastNotice = "현재 지도 범위 조회 중 · 확대 ${labelZoom}단계"
                try {
                    val level = explicitLevel ?: regionLevel(labelZoom)
                    if (level == 3) {
                        val apartments = loadViewportApartments(visible)
                        apartmentsOnMap = apartments
                        regionsOnMap = emptyList()
                        count = apartments.size
                        lastNotice = "개별 아파트 ${apartments.size}곳 조회 완료 · 실거래가 없는 단지도 포함"
                    } else {
                        val regions = loadViewportRegions(visible, level)
                        regionsOnMap = regions
                        apartmentsOnMap = emptyList()
                        count = regions.size
                        lastNotice = "행정구역 ${regions.size}곳 조회 완료"
                    }
                    displayedLevel = level
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { loadError = "화면 단지 조회 실패: ${e.message}"
                    lastNotice = loadError.orEmpty() }
                finally { loading = false }
            }

            // Rendering is separate from network access. Font controls redraw immediately;
            // Redraw each opaque card at the active zoom size.
            LaunchedEffect(map, apartmentsOnMap, regionsOnMap, labelSizeStep, labelZoom, selected?.identity) {
                val current = map ?: return@LaunchedEffect
                val manager = current.labelManager ?: return@LaunchedEffect
                val layer = manager.getLayer("actual-trades") ?: return@LaunchedEffect
                val density = context.resources.displayMetrics.density
                val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.2f)
                val userScale = 1f + labelSizeStep * 0.1f
                val zoomScale = (1f + (labelZoom - 15) * 0.06f).coerceIn(0.70f, 1.36f)
                val scale = density * fontScale * userScale * zoomScale
                layer.removeAll()
                regionsOnMap.forEachIndexed { index, regionMarker ->
                    coroutineContext.ensureActive()
                    val card = opaqueTradeCard(regionMarker.name, "${regionMarker.count}단지", scale)
                    layer.addLabel(LabelOptions.from("region-${regionMarker.code}",
                        LatLng.from(regionMarker.latitude, regionMarker.longitude))
                        .setStyles(LabelStyle.from(card).setApplyDpScale(false)
                            .setAnchorPoint(0.5f, 0.5f)
                            .setIconTransition(LabelTransition.from(Transition.None, Transition.None)
                                .enableTransitionWhenChange(false, false))
                            .setTextTransition(LabelTransition.from(Transition.None, Transition.None)
                                .enableTransitionWhenChange(false, false)))
                        .setRank(1000000L + regionMarker.count).setClickable(true).setTag(regionMarker))
                    if (index % 40 == 0) yield()
                }
                apartmentsOnMap.forEachIndexed { index, apt ->
                    coroutineContext.ensureActive()
                    val price = apt.latestPrice?.let { String.format(Locale.KOREA, "%.2f억", it / 10000.0) } ?: "가격 미상"
                    val area = apt.representativeArea?.let {
                        String.format(Locale.KOREA, "%.1f평", it / 3.305785)
                    } ?: "면적 미상"
                    val card = opaqueTradeCard(apt.name, "$area · $price", scale)
                    val priority = if (apt.identity == selected?.identity) Long.MAX_VALUE
                        else apt.tradeCount.toLong() * 100000L + (apartmentsOnMap.size - index)
                    layer.addLabel(LabelOptions.from(apt.identity, LatLng.from(apt.latitude!!, apt.longitude!!))
                        .setStyles(LabelStyle.from(card)
                            .setApplyDpScale(false).setAnchorPoint(0.5f, 0.5f)
                            .setIconTransition(LabelTransition.from(Transition.None, Transition.None)
                                .enableTransitionWhenChange(false, false))
                            .setTextTransition(LabelTransition.from(Transition.None, Transition.None)
                                .enableTransitionWhenChange(false, false)))
                        .setRank(priority).setClickable(true).setTag(apt))
                    if (index % 40 == 0) yield()
                }
            }
            val failure = mapError ?: positionError ?: loadError
            val statusMessage = when {
                failure != null -> failure
                loading || positionLoading -> "지도 데이터를 조회 중입니다…"
                else -> lastNotice + if (count == 0) " · 조회 결과 0곳. 지도 이동 또는 다시 조회를 이용하십시오." else ""
            }
            // This panel replaces the former variable-height status text.
            // Normal progress, zero results and errors all share this ONE fixed location.
            val statusScroll = rememberScrollState()
            Column(Modifier.fillMaxWidth().height(108.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("지도 조회 메시지", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { retry++ }, enabled = !loading) { Text("다시 조회") }
                }
                if (loading || positionLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Text(statusMessage, modifier = Modifier.fillMaxSize()
                        .padding(end = 12.dp).verticalScroll(statusScroll),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failure != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface)
                    Canvas(Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight()) {
                        drawRect(ComposeColor.LightGray.copy(alpha = 0.4f))
                        val range = statusScroll.maxValue
                        val thumb = if (range <= 0) size.height else
                            (size.height * size.height / (size.height + range))
                                .coerceIn(10.dp.toPx(), size.height)
                        val y = if (range <= 0) 0f
                            else (statusScroll.value.toFloat() / range) * (size.height - thumb)
                        drawRect(ComposeColor.Gray, topLeft = Offset(0f, y),
                            size = Size(size.width, thumb))
                    }
                }
            }
        }
    }
}

/**
 * Text and background are ONE opaque bitmap, so SDK icon/text passes cannot
 * paint a rear label's text over the front label's blue background.
 * Generate only the active zoom size instead of retaining 22 images per label.
 */
private fun opaqueTradeCard(name: String, details: String, scale: Float): Bitmap {
    val width = (132f * scale).roundToInt().coerceAtLeast(1)
    val height = (40f * scale).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        density = Bitmap.DENSITY_NONE
        eraseColor(Color.rgb(21, 101, 192))
        setHasAlpha(false) // Every pixel is opaque; never use a transparent icon surface.
    }
    val canvas = Canvas(bitmap)
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * scale
    }
    val inset = border.strokeWidth / 2f
    canvas.drawRect(inset, inset, width - inset, height - inset, border)
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * scale
        textAlign = Paint.Align.CENTER
    }
    val maxWidth = (width - 12f * scale).coerceAtLeast(1f)
    val title = TextUtils.ellipsize(name, text, maxWidth, TextUtils.TruncateAt.END).toString()
    val second = TextUtils.ellipsize(details, text, maxWidth, TextUtils.TruncateAt.END).toString()
    val centerOffset = -(text.ascent() + text.descent()) / 2f
    canvas.drawText(title, width / 2f, height * 0.30f + centerOffset, text)
    canvas.drawText(second, width / 2f, height * 0.70f + centerOffset, text)
    return bitmap
}

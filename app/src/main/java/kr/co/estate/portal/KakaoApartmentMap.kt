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
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.LabelTextBuilder
import com.kakao.vectormap.label.LabelTextStyle
import com.kakao.vectormap.label.LabelOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.ceil

private val SEOUL_CITY_HALL: LatLng get() = LatLng.from(37.5665, 126.9780)

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
    focusRequest: Int, mapHeight: Dp, showMap: Boolean = true, onSelect: (ServerApartment) -> Unit
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
    var labelSizeStep by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
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
                        if (active) bounds = viewportBounds(current)
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
            LaunchedEffect(map, selected?.identity, region, regionDepth, focusRequest) {
                val current = map ?: return@LaunchedEffect
                positionError = null; positionLoading = true
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
                    } else if (regionDepth == 1 && (region == "서울특별시" || region.isBlank())) {
                        point = SEOUL_CITY_HALL; zoom = 15
                    } else {
                        point = withContext(Dispatchers.IO) {
                            @Suppress("DEPRECATION")
                            val address = Geocoder(context, Locale.KOREA).getFromLocationName(region, 1)?.firstOrNull()
                                ?: error("$region 중심 좌표를 찾지 못했습니다.")
                            LatLng.from(address.latitude, address.longitude)
                        }
                        zoom = if (regionDepth >= 3) 16 else if (regionDepth == 2) 14 else 12
                    }
                    bounds = null
                    current.moveCamera(CameraUpdateFactory.newCenterPosition(point, zoom))
                    // Explicit refresh also covers selecting an already centered location.
                    view.post { if (map === current) bounds = viewportBounds(current) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { positionError = "위치 이동 실패: ${e.message}" }
                finally { positionLoading = false }
            }

            LaunchedEffect(map, bounds, retry) {
                val current = map ?: return@LaunchedEffect
                val visible = bounds ?: return@LaunchedEffect
                delay(350) // Coalesce rapid gestures; cancellation discards stale responses.
                loading = true; loadError = null
                try {
                    val apartments = loadViewportApartments(visible)
                    apartmentsOnMap = apartments
                    count = apartments.size
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { loadError = "화면 단지 조회 실패: ${e.message}" }
                finally { loading = false }
            }

            // Rendering is separate from network access. Font controls redraw immediately;
            // SDK zoom styles resize both the text and its background during map zoom.
            LaunchedEffect(map, apartmentsOnMap, labelSizeStep, selected?.identity) {
                val current = map ?: return@LaunchedEffect
                val manager = current.labelManager ?: return@LaunchedEffect
                val layer = manager.getLayer("actual-trades") ?: return@LaunchedEffect
                val density = context.resources.displayMetrics.density
                val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.2f)
                val userScale = 1f + labelSizeStep * 0.1f
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 11f * density * fontScale * userScale
                }
                val styleId = "uniform-card-$density-$fontScale-$labelSizeStep"
                val styles = manager.getLabelStyles(styleId) ?: manager.addLabelStyles(
                    compactTradeStyles(styleId, 132f * fontScale * userScale, density, fontScale, userScale))
                layer.removeAll()
                apartmentsOnMap.forEachIndexed { index, apt ->
                    coroutineContext.ensureActive()
                    val price = apt.latestPrice?.let { String.format(Locale.KOREA, "%.2f억", it / 10000.0) } ?: "가격 미상"
                    val area = apt.representativeArea?.let {
                        String.format(Locale.KOREA, "%.1f평", it / 3.305785)
                    } ?: "면적 미상"
                    val name = TextUtils.ellipsize(apt.name, textPaint,
                        120f * density * fontScale * userScale, TextUtils.TruncateAt.END).toString()
                    val priority = if (apt.identity == selected?.identity) Long.MAX_VALUE
                        else apt.tradeCount.toLong() * 100000L + (apartmentsOnMap.size - index)
                    layer.addLabel(LabelOptions.from(apt.identity, LatLng.from(apt.latitude!!, apt.longitude!!))
                        .setStyles(styles)
                        .setTexts(LabelTextBuilder().addTextLine(name, 0).addTextLine("$area · $price", 1))
                        .setRank(priority).setClickable(true).setTag(apt))
                    if (index % 40 == 0) yield()
                }
            }
            if (loading || positionLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (loading) "화면 안 실거래 단지를 조회 중입니다…" else "조회 ${count}곳 · 전용평수 · 전체 라벨 표시 · 누르면 상세",
                style = MaterialTheme.typography.bodySmall)
            val failure = mapError ?: positionError ?: loadError
            failure?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }, enabled = !loading) { Text("현재 화면 다시 조회") }
            }
        }
    }
}

/**
 * Content-sized, two-line cards. ZoomLevel styles are handled by the SDK without
 * waiting for the viewport API; white border, blue fill, and fonts scale together.
 */
private fun compactTradeStyles(
    id: String, baseWidthDp: Float, density: Float, fontScale: Float, userScale: Float
): LabelStyles {
    val levels = (0..21).map { zoom ->
        zoom to (1f + (zoom - 15) * 0.06f).coerceIn(0.70f, 1.36f)
    }
    val styles = levels.map { (zoom, zoomScale) ->
        val priceSize = 11f * density * fontScale * userScale * zoomScale
        val areaSize = 11f * density * fontScale * userScale * zoomScale
        val pricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = priceSize }
        val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = areaSize }
        val width = (baseWidthDp * density * zoomScale).roundToInt().coerceAtLeast(1)
        val height = ceil(pricePaint.fontSpacing + areaPaint.fontSpacing +
            10f * density * userScale * zoomScale).toInt().coerceAtLeast(1)
        val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            this.density = Bitmap.DENSITY_NONE
        }
        val canvas = Canvas(background)
        val inset = (1.5f * density * userScale * zoomScale) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 101, 192) }
        val radius = 3f * density * userScale * zoomScale
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density * userScale * zoomScale
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, paint)
        LabelStyle.from(background)
            .setApplyDpScale(false)
            .setZoomLevel(zoom)
            .setAnchorPoint(0.5f, 0.5f)
            .setTextGravity(MapGravity.CENTER)
            .setPadding(0f)
            .setTextStyles(
                LabelTextStyle.from(priceSize.roundToInt(), Color.WHITE),
                LabelTextStyle.from(areaSize.roundToInt(), Color.WHITE))
    }
    return LabelStyles.from(id, *styles.toTypedArray())
}

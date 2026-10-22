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
    focusRequest: Int, mapHeight: Dp, onSelect: (ServerApartment) -> Unit
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
    var retry by remember { mutableIntStateOf(0) }
    val appKey = BuildConfig.KAKAO_NATIVE_APP_KEY
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("실거래 지도", style = MaterialTheme.typography.titleMedium)
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
                            .setCompetitionType(CompetitionType.None).setClickable(true))
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
            AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(mapHeight))

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
                    val layer = current.labelManager?.getLayer("actual-trades") ?: error("지도 단지 레이어가 없습니다.")
                    val manager = current.labelManager ?: error("지도 스타일 관리자가 없습니다.")
                    // Scale actual pixels explicitly: the SDK's tiny unscaled text was
                    // unreadable on high-density phones. Reuse ONE blue background/style.
                    val density = context.resources.displayMetrics.density
                    val fontScale = context.resources.configuration.fontScale.coerceIn(1f, 1.2f)
                    val styleId = "trade-blue-card-$density-$fontScale"
                    val styles = manager.getLabelStyles(styleId) ?: manager.addLabelStyles(
                        blueTradeCardStyle(styleId, density, fontScale))
                    val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 13f * density * fontScale
                    }
                    coroutineContext.ensureActive()
                    layer.removeAll()
                    apartments.forEachIndexed { i, apt ->
                        layer.addLabel(LabelOptions.from(apt.identity, LatLng.from(apt.latitude!!, apt.longitude!!))
                            .setStyles(styles).setTexts(LabelTextBuilder()
                                .addTextLine(TextUtils.ellipsize(apt.name, namePaint,
                                    140f * density, TextUtils.TruncateAt.END).toString(), 0)
                                .addTextLine(mapPrice(apt.latestPrice), 1)
                                .addTextLine(apt.representativeArea?.let {
                                    String.format(Locale.KOREA, "전용 %.1f평", it / 3.305785)
                                } ?: "면적 미상", 2))
                            .setClickable(true).setTag(apt))
                        if (i % 40 == 0) yield()
                    }
                    count = apartments.size
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { loadError = "화면 단지 조회 실패: ${e.message}" }
                finally { loading = false }
            }
            if (loading || positionLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (loading) "화면 안 실거래 단지를 조회 중입니다…" else "실거래 단지 ${count}곳 · 가격·전용평수 · 누르면 상세",
                style = MaterialTheme.typography.bodySmall)
            val failure = mapError ?: positionError ?: loadError
            failure?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }, enabled = !loading) { Text("현재 화면 다시 조회") }
            }
        }
    }
}

/** Opaque blue rectangle; white text is centered INSIDE the shared image. */
private fun blueTradeCardStyle(id: String, density: Float, fontScale: Float): LabelStyles {
    val width = (160f * density).roundToInt().coerceAtLeast(1)
    val height = (86f * density).roundToInt().coerceAtLeast(1)
    val background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        // Pixel dimensions are already density-scaled; prevent a second SDK scale.
        this.density = Bitmap.DENSITY_NONE
        eraseColor(Color.rgb(21, 101, 192))
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(13, 71, 161)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    val inset = border.strokeWidth / 2f
    Canvas(background).drawRect(inset, inset, width - inset, height - inset, border)
    fun whiteText(size: Float) =
        LabelTextStyle.from((size * density * fontScale).roundToInt(), Color.WHITE)
    return LabelStyles.from(id, LabelStyle.from(background)
        .setApplyDpScale(false)
        .setAnchorPoint(0.5f, 0.5f)
        .setTextGravity(MapGravity.CENTER)
        .setPadding(0f)
        .setTextStyles(whiteText(13f), whiteText(18f), whiteText(15f)))
}

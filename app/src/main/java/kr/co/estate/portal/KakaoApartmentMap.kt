package kr.co.estate.portal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Native Kakao Map: no WebView. Region coordinates are never used as apartment coordinates. */
@Composable
fun KakaoApartmentMap(
    apartments: List<ServerApartment>,
    selected: ServerApartment?,
    region: String,
    onSelect: (ServerApartment) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val select by rememberUpdatedState(onSelect)
    var map by remember { mutableStateOf<KakaoMap?>(null) }
    var status by remember { mutableStateOf("카카오 지도 연결 중…") }
    var failure by remember { mutableStateOf<String?>(null) }
    val appKey = BuildConfig.KAKAO_NATIVE_APP_KEY

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("지도에서 단지 선택", style = MaterialTheme.typography.titleMedium)
        if (appKey.isBlank()) {
            Text("카카오 네이티브 앱 키가 없습니다. local.properties에 KAKAO_NATIVE_APP_KEY를 입력한 뒤 다시 실행하십시오.",
                color = MaterialTheme.colorScheme.error)
        } else {
            val view = remember(context) {
                KakaoMapSdk.init(context.applicationContext, appKey)
                MapView(context).apply {
                    // Let map gestures win over the enclosing search form's vertical scroll.
                    setOnTouchListener { v, event ->
                        v.parent?.requestDisallowInterceptTouchEvent(
                            event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                        false
                    }
                }
            }
            DisposableEffect(view, owner) {
                var active = true
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
                        view.post { if (active) failure = "지도 인증/연결 실패: ${error.message}" }
                    }
                }, object : KakaoMapReadyCallback() {
                    override fun getPosition(): LatLng = LatLng.from(35.1796, 129.0756)
                    override fun getZoomLevel(): Int = 12
                    override fun onMapReady(kakaoMap: KakaoMap) {
                        kakaoMap.setOnLabelClickListener { _, _, label ->
                            val apartment = label.tag as? ServerApartment
                            view.post { if (active && apartment != null) select(apartment) }
                            true
                        }
                        view.post {
                            if (active) {
                                map = kakaoMap
                                status = "지도를 움직이거나 지역·단지를 선택하십시오."
                            }
                        }
                    }
                })
                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
                onDispose {
                    active = false
                    owner.lifecycle.removeObserver(observer)
                    map = null
                    view.pause()
                    view.finish()
                }
            }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(300.dp))

            LaunchedEffect(map, apartments, selected, region) {
                val current = map ?: return@LaunchedEffect
                val layer = current.labelManager?.layer ?: return@LaunchedEffect
                layer.removeAll()
                failure = null
                try {
                    // Prefer server coordinates. Geocoder is a fallback only for the selected apartment.
                    val points = apartments.mapNotNull { apt ->
                        apt.latitude?.let { lat -> apt.longitude?.let { lng -> apt to LatLng.from(lat, lng) } }
                    }.toMutableList()
                    if (selected != null && points.none { it.first.kaptCode == selected.kaptCode }) {
                        status = "${selected.name} 위치 조회 중…"
                        val position = withContext(Dispatchers.IO) {
                            @Suppress("DEPRECATION")
                            val addresses = Geocoder(context, Locale.KOREA)
                                .getFromLocationName("$region ${selected.name}", 1)
                            addresses?.firstOrNull()?.let { LatLng.from(it.latitude, it.longitude) }
                        }
                        if (position != null) points.add(selected to position)
                        else failure = "${selected.name}의 좌표를 찾지 못했습니다. 단지 검색 버튼으로 상세정보를 열 수 있습니다."
                    }
                    points.forEach { (apt, position) ->
                        layer.addLabel(LabelOptions.from(apt.kaptCode, position)
                            .setStyles(apartmentMarker(apt.name))
                            .setClickable(true).setTag(apt))
                    }
                    val focus = points.firstOrNull { it.first.kaptCode == selected?.kaptCode } ?: points.firstOrNull()
                    if (focus != null) current.moveCamera(CameraUpdateFactory.newCenterPosition(focus.second, 15))
                    else if (selected == null && region.isNotBlank()) {
                        val center = withContext(Dispatchers.IO) {
                            @Suppress("DEPRECATION")
                            val addresses = Geocoder(context, Locale.KOREA).getFromLocationName(region, 1)
                            addresses?.firstOrNull()?.let { LatLng.from(it.latitude, it.longitude) }
                        }
                        if (center != null) current.moveCamera(CameraUpdateFactory.newCenterPosition(center, 14))
                    }
                    status = if (points.isEmpty()) "단지를 선택하면 위치를 표시합니다."
                        else "단지 ${points.size}곳 표시 · 이름표를 누르면 실거래 화면이 열립니다."
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = "단지 위치 조회 실패: ${e.message}"
                }
            }
            Text(failure ?: status, style = MaterialTheme.typography.bodySmall,
                color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun apartmentMarker(name: String): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; isFakeBoldText = true }
    val title = name.take(22)
    val width = (paint.measureText(title) + 36).toInt()
    return Bitmap.createBitmap(width, 58, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        paint.color = Color.rgb(8, 127, 104)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), 58f, 14f, 14f, paint)
        paint.color = Color.WHITE
        canvas.drawText(title, 18f, 39f, paint)
    }
}

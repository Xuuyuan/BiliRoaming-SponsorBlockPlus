package me.iacn.biliroaming.hook

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.network.SponsorBlockApi
import me.iacn.biliroaming.network.SponsorBlockSegment
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.callMethod
import me.iacn.biliroaming.utils.callMethodOrNullAs
import me.iacn.biliroaming.utils.hookAfterAllConstructors
import me.iacn.biliroaming.utils.hookAfterMethod
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.sPrefs
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

class SponsorBlockHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val scope = MainScope()
    private val bvidByCid = ConcurrentHashMap<Long, String>()
    private val segmentCache = ConcurrentHashMap<VideoKey, List<SponsorBlockSegment>>()
    private val loadingKeys = ConcurrentHashMap.newKeySet<VideoKey>()
    private val lastSkipAt = ConcurrentHashMap<String, Long>()
    private val playerServices = CopyOnWriteArrayList<WeakReference<Any>>()
    private val progressDrawables = WeakHashMap<ProgressBar, SponsorBlockProgressDrawable>()
    private var playerServiceRef: WeakReference<Any>? = null
    private var activityRef: WeakReference<Activity>? = null
    @Volatile
    private var loggedNoProgressBar = false
    @Volatile
    private var currentVideoKey: VideoKey? = null
    @Volatile
    private var pollingStarted = false
    @Volatile
    private var loggedDisabled = false

    override fun startHook() {
        Log.d("startHook: SponsorBlockHook")
        Log.d(
            "SponsorBlock: enabled=${isEnabled()} useNewMossFunc=${instance.useNewMossFunc} " +
                    "viewMoss=${instance.viewMossClass?.name} viewReq=${instance.viewReqClass?.name} " +
                    "viewUniteMoss=${instance.viewUniteMossClass?.name} viewUniteReq=${instance.viewUniteReqClass?.name} " +
            "playerService=${instance.playerCoreServiceV2Class?.name} seekTo=${instance.seekTo()}"
        )
        hookActivity()
        hookPlayerService()
        hookViewReplies()
        hookProgress()
    }

    private fun hookActivity() {
        Activity::class.java.hookAfterMethod("onResume") {
            activityRef = WeakReference(it.thisObject as Activity)
            updateNativeProgressBars()
        }
    }

    private fun hookPlayerService() {
        instance.playerCoreServiceV2Class?.hookAfterAllConstructors {
            playerServiceRef = WeakReference(it.thisObject)
            playerServices += WeakReference(it.thisObject)
            Log.d("SponsorBlock: player service captured ${it.thisObject.javaClass.name}")
            startPlayerPolling()
        }
    }

    private fun hookViewReplies() {
        instance.viewUniteMossClass?.hookBeforeMethod(
            "executeView",
            instance.viewUniteReqClass
        ) { param ->
            param.args.firstOrNull()?.let { req ->
                req.callMethodOrNullAs<String?>("getBvid")?.takeIf { it.isNotBlank() }?.let { bvid ->
                    rememberCidFromReq(req, bvid)
                }
            }
        }
        instance.viewUniteMossClass?.hookAfterMethod(
            "executeView",
            instance.viewUniteReqClass
        ) { param ->
            rememberCidsFromReply(param.result)
        }

        instance.viewMossClass?.hookBeforeMethod(
            if (instance.useNewMossFunc) "executeView" else "view",
            instance.viewReqClass
        ) { param ->
            param.args.firstOrNull()?.let { req ->
                req.callMethodOrNullAs<String?>("getBvid")?.takeIf { it.isNotBlank() }?.let { bvid ->
                    rememberCidFromReq(req, bvid)
                }
            }
        }
        instance.viewMossClass?.hookAfterMethod(
            if (instance.useNewMossFunc) "executeView" else "view",
            instance.viewReqClass
        ) { param ->
            rememberCidsFromReply(param.result)
        }

        instance.viewUniteMossClass?.hookBeforeMethod(
            "arcRefresh",
            "com.bapis.bilibili.app.viewunite.v1.ArcRefreshReq",
            instance.mossResponseHandlerClass
        ) { param ->
            param.args.firstOrNull()
                ?.callMethodOrNullAs<String?>("getBvid")
                ?.takeIf { it.isNotBlank() }
                ?.let { bvid -> currentBvid = bvid }
        }
    }

    private fun hookProgress() {
        instance.viewMossClass?.hookBeforeMethod(
            if (instance.useNewMossFunc) "executeViewProgress" else "viewProgress",
            "com.bapis.bilibili.app.view.v1.ViewProgressReq"
        ) { param ->
            handleProgress(param.args.firstOrNull())
        }

        instance.viewUniteMossClass?.hookBeforeMethod(
            if (instance.useNewMossFunc) "executeViewProgress" else "viewProgress",
            "com.bapis.bilibili.app.viewunite.v1.ViewProgressReq"
        ) { param ->
            handleProgress(param.args.firstOrNull())
        }
    }

    private fun rememberCidFromReq(req: Any, bvid: String) {
        if (!isEnabled()) {
            logDisabledOnce("view req")
            return
        }
        currentBvid = bvid
        val cid = readCid(req) ?: return
        Log.d("SponsorBlock: view req bvid=$bvid cid=$cid")
        setCurrentVideo(VideoKey(bvid, cid))
    }

    private fun rememberCidsFromReply(reply: Any?) {
        if (!isEnabled()) {
            logDisabledOnce("view reply")
            return
        }
        reply ?: return
        val bvid = reply.callMethodOrNullAs<String?>("getBvid")?.takeIf { it.isNotBlank() } ?: currentBvid ?: return
        currentBvid = bvid
        reply.callMethodOrNullAs<List<Any?>>("getPagesList").orEmpty().forEach { viewPage ->
            val page = viewPage?.callMethod("getPage")
            val cid = page?.callMethodOrNullAs<Long?>("getCid")?.takeIf { it > 0 } ?: return@forEach
            Log.d("SponsorBlock: view reply bvid=$bvid cid=$cid")
            setCurrentVideo(VideoKey(bvid, cid))
        }
    }

    private fun handleProgress(req: Any?) {
        if (!isEnabled()) {
            logDisabledOnce("progress")
            return
        }
        req ?: return
        val cid = readCid(req) ?: return
        val aid = readAid(req)
        val bvid = readBvid(req) ?: bvidByCid[cid] ?: aid?.let(::av2bv) ?: run {
            Log.d("SponsorBlock: progress cid=$cid aid=$aid has no bvid mapping")
            return
        }
        if (aid != null) Log.d("SponsorBlock: progress aid=$aid bvid=$bvid cid=$cid")
        val progressCandidates = readProgressCandidates(req)
        val key = VideoKey(bvid, cid)
        setCurrentVideo(key)
        if (segmentCache[key] == null) {
            Log.d("SponsorBlock: segments not loaded yet for ${key.bvid}/${key.cid}")
            ensureSegments(key)
        }
        if (progressCandidates.isEmpty()) {
            Log.d("SponsorBlock: no progress field in ${req.javaClass.name}")
            return
        }
        Log.d("SponsorBlock: progress bvid=$bvid cid=$cid values=${progressCandidates.joinToString()}")

        val segments = segmentCache[key]
        if (segments == null) {
            return
        }

        val segment = segments.firstOrNull {
            progressCandidates.any { progress -> progress >= it.start - 0.2 && progress < it.end } &&
                    markSkipThrottled(key, it)
        } ?: run {
            if (segments.isNotEmpty()) {
                Log.d(
                    "SponsorBlock: no segment matched progress=${progressCandidates.joinToString()} " +
                            "segments=${segments.joinToString { "${it.start}-${it.end}" }}"
                )
            }
            return
        }

        seekTo(segment.end)
    }

    private fun setCurrentVideo(key: VideoKey) {
        bvidByCid[key.cid] = key.bvid
        currentVideoKey = key
        ensureSegments(key)
        startPlayerPolling()
    }

    private fun startPlayerPolling() {
        if (pollingStarted) return
        pollingStarted = true
        scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(500)
                if (!isEnabled()) continue

                val key = currentVideoKey ?: continue
                val segments = segmentCache[key]
                if (segments == null) {
                    ensureSegments(key)
                    continue
                }
                if (segments.isEmpty()) continue

                val playerService = findActivePlayerService() ?: continue
                val positionMs = readPlayerPositionMs(playerService) ?: continue
                val durationMs = readPlayerDurationMs(playerService) ?: 0L
                updateNativeProgressBars(durationMs)
                val progress = positionMs / 1000.0

                val segment = segments.firstOrNull {
                    progress >= it.start - 0.2 && progress < it.end && markSkipThrottled(key, it)
                } ?: continue

                Log.d(
                    "SponsorBlock: player progress=$progress matched ${segment.start}-${segment.end} " +
                            "for ${key.bvid}/${key.cid}"
                )
                seekTo(segment.end, playerService)
            }
        }
    }

    private fun markSkipThrottled(key: VideoKey, segment: SponsorBlockSegment): Boolean {
        val skipKey = segment.uuid.ifBlank { "${key.bvid}:${key.cid}:${segment.start}:${segment.end}" }
        val now = System.currentTimeMillis()
        val last = lastSkipAt[skipKey] ?: 0
        if (now - last < 10_000) return false

        lastSkipAt[skipKey] = now
        return true
    }

    private fun ensureSegments(key: VideoKey) {
        if (segmentCache.containsKey(key) || !loadingKeys.add(key)) return
        Log.d("SponsorBlock: loading segments for ${key.bvid}/${key.cid}")
        scope.launch(Dispatchers.IO) {
            val segments = SponsorBlockApi.getSkipSegments(key.bvid, key.cid)
            segmentCache[key] = segments
            loadingKeys.remove(key)
            Log.d("SponsorBlock: loaded ${segments.size} segments for ${key.bvid}/${key.cid}")
            if (segments.isNotEmpty()) {
                Log.toast("已找到 ${segments.size} 个赞助片段", force = true)
            }
            updateNativeProgressBars()
        }
    }

    private fun seekTo(seconds: Double, targetPlayerService: Any? = null) {
        val playerService = targetPlayerService ?: findActivePlayerService() ?: playerServiceRef?.get() ?: return
        val seekTo = instance.seekTo() ?: return
        val positionMs = (seconds * 1000).toInt().coerceAtLeast(0)
        runCatching {
            playerService.callMethod(seekTo, positionMs)
            Log.d("SponsorBlock: seek to ${positionMs}ms")
            Log.toast("已跳过赞助片段", force = true)
        }.recoverCatching {
            playerService.callMethod(seekTo, positionMs, true)
            Log.d("SponsorBlock: seek to ${positionMs}ms with accurate flag")
            Log.toast("已跳过赞助片段", force = true)
        }.onFailure {
            Log.e(it)
        }
    }

    private fun findActivePlayerService(): Any? =
        playerServices.asSequence()
            .mapNotNull { it.get() }
            .mapNotNull { service ->
                val duration = readPlayerDurationMs(service) ?: return@mapNotNull null
                if (duration <= 0) return@mapNotNull null

                val position = readPlayerPositionMs(service) ?: 0L
                val state = service.callMethodOrNullAs<Number?>("getState")?.toInt() ?: 0
                val score = duration + position + if (state == 4) Long.MAX_VALUE / 4 else 0L
                service to score
            }
            .maxByOrNull { it.second }
            ?.first

    private fun readPlayerPositionMs(playerService: Any): Long? =
        playerService.callMethodOrNullAs<Number?>("getRealCurrentPosition")?.toLong()?.takeIf { it >= 0 }
            ?: playerService.callMethodOrNullAs<Number?>("getCurrentPosition")?.toLong()?.takeIf { it >= 0 }

    private fun readPlayerDurationMs(playerService: Any): Long? =
        playerService.callMethodOrNullAs<Number?>("getRealDuration")?.toLong()?.takeIf { it > 0 }
            ?: playerService.callMethodOrNullAs<Number?>("getDuration")?.toLong()?.takeIf { it > 0 }

    private fun readBvid(req: Any): String? =
        req.callMethodOrNullAs<String?>("getBvid")?.takeIf { it.isNotBlank() }

    private fun readCid(req: Any): Long? =
        req.callMethodOrNullAs<Long?>("getCid")?.takeIf { it > 0 }
            ?: req.callMethodOrNullAs<Any?>("getVod")?.callMethodOrNullAs<Long?>("getCid")?.takeIf { it > 0 }

    private fun readAid(req: Any): Long? =
        req.callMethodOrNullAs<Long?>("getAid")?.takeIf { it > 0 }

    private fun av2bv(aid: Long): String {
        val table = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
        val positions = intArrayOf(11, 10, 3, 8, 4, 6, 5, 7, 9)
        val value = (aid xor 23442827791579L) or (1L shl 51)
        val chars = "BV1000000000".toCharArray()
        positions.forEachIndexed { index, position ->
            chars[position] = table[(value / pow58(index) % 58).toInt()]
        }
        return String(chars)
    }

    private fun pow58(power: Int): Long {
        var value = 1L
        repeat(power) {
            value *= 58L
        }
        return value
    }

    private fun readProgressCandidates(req: Any): Set<Double> {
        val rawValues = listOf(
            "getProgress",
            "getPlayingTime",
            "getPosition",
            "getPlayedTime",
            "getPlayTime",
            "getCurrentTime",
            "getLastPlayProgressTime",
        ).mapNotNull { method ->
            req.callMethodOrNullAs<Number?>(method)?.toDouble()
        }.filter { it >= 0 }

        return buildSet {
            rawValues.forEach { value ->
                add(value)
                if (value >= 1000) add(value / 1000.0)
            }
        }
    }

    private fun isEnabled() = sPrefs.getBoolean("sponsorblock_auto_skip", false)

    private fun logDisabledOnce(source: String) {
        if (loggedDisabled) return
        loggedDisabled = true
        Log.d("SponsorBlock: $source ignored because sponsorblock_auto_skip=false")
    }

    private fun updateNativeProgressBars(durationMs: Long? = null) {
        scope.launch(Dispatchers.Main) {
            val activity = activityRef?.get() ?: return@launch
            val root = activity.window?.decorView ?: return@launch
            val segments = currentVideoKey?.let { segmentCache[it] }.orEmpty()
            val progressBars = findProgressBars(root)
            if (progressBars.isEmpty() && !loggedNoProgressBar) {
                loggedNoProgressBar = true
                Log.d("SponsorBlock: no native ProgressBar found in ${activity.javaClass.name}")
            }

            progressBars.forEach { progressBar ->
                val drawable = progressBar.progressDrawable ?: return@forEach
                val wrapped = progressDrawables[progressBar]
                    ?: SponsorBlockProgressDrawable(drawable, progressBar).also {
                        progressDrawables[progressBar] = it
                        progressBar.progressDrawable = it
                        Log.d("SponsorBlock: wrapped native progress bar ${progressBar.javaClass.name}")
                    }

                wrapped.setData(
                    segments = if (isEnabled()) segments else emptyList(),
                    durationMs = durationMs ?: wrapped.durationMs,
                )
                progressBar.invalidate()
            }
        }
    }

    private fun findProgressBars(root: View): List<ProgressBar> = buildList {
        fun visit(view: View) {
            if (view is ProgressBar) {
                add(view)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i))
                }
            }
        }
        visit(root)
    }

    private class SponsorBlockProgressDrawable(
        private val base: Drawable,
        private val owner: ProgressBar,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 193, 7)
        }
        var durationMs: Long = 0
            private set
        private var segments: List<SponsorBlockSegment> = emptyList()

        init {
            base.callback = owner
        }

        fun setData(segments: List<SponsorBlockSegment>, durationMs: Long) {
            this.segments = segments
            this.durationMs = durationMs
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            base.bounds = bounds
            base.state = state
            base.level = level
            base.draw(canvas)
            val durationSeconds = durationMs.takeIf { it > 0 }?.div(1000f) ?: return
            if (segments.isEmpty()) return

            val markerTop = bounds.top.toFloat()
            val markerBottom = bounds.bottom.toFloat()
            val width = bounds.width().toFloat()
            segments.forEach { segment ->
                val left = bounds.left + (segment.start.toFloat() / durationSeconds).coerceIn(0f, 1f) * width
                val right = bounds.left + (segment.end.toFloat() / durationSeconds).coerceIn(0f, 1f) * width
                if (right > left) {
                    canvas.drawRect(left, markerTop, right, markerBottom, paint)
                }
            }
        }

        override fun setAlpha(alpha: Int) {
            base.alpha = alpha
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            base.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            base.bounds = bounds
        }

        override fun isStateful(): Boolean = base.isStateful

        override fun onStateChange(state: IntArray): Boolean = base.setState(state)

        override fun onLevelChange(level: Int): Boolean = base.setLevel(level)

        override fun getIntrinsicWidth(): Int = base.intrinsicWidth

        override fun getIntrinsicHeight(): Int = base.intrinsicHeight
    }

    private data class VideoKey(val bvid: String, val cid: Long)

    companion object {
        @Volatile
        private var currentBvid: String? = null
    }
}

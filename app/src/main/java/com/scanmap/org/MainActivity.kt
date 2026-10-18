package com.scanmap.org

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatButton
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEngine.Listener, WifiEngine.Listener {

    // ── views ────────────────────────────────────────────────
    internal lateinit var topView: TopView
    internal lateinit var topMap: TopView
    internal lateinit var perspView: PerspectiveView
    internal lateinit var perspMap: PerspectiveView
    internal lateinit var previewView: PreviewView
    internal lateinit var camHud: CamHud
    internal lateinit var txtCamTitle: TextView
    internal lateinit var txtStats: TextView
    internal lateinit var txtWarn: TextView
    internal lateinit var txtProject: TextView
    internal lateinit var txtSub: TextView
    internal lateinit var btnFloor: AppCompatButton
    internal lateinit var btnScanToggle: AppCompatButton
    internal lateinit var btnWallRec: AppCompatButton
    internal lateinit var btnTapPos: AppCompatButton
    internal lateinit var btnApAuto: AppCompatButton
    internal lateinit var chips: List<AppCompatButton>
    internal lateinit var btnMap2d: AppCompatButton
    internal lateinit var btnMap3d: AppCompatButton
    internal lateinit var screenScan: LinearLayout
    internal lateinit var screenMap: LinearLayout
    internal lateinit var paneCamera: FrameLayout
    internal lateinit var rowPanes: LinearLayout
    internal lateinit var paneTop: FrameLayout
    internal lateinit var panePersp: FrameLayout
    internal lateinit var filesContainer: FrameLayout
    internal lateinit var settingsContainer: FrameLayout
    internal lateinit var navButtons: List<AppCompatButton>

    // ── engines ──────────────────────────────────────────────
    internal val sensorEngine = SensorEngine(this)
    internal val wifiEngine = WifiEngine(this)
    internal val dialogs by lazy { UiDialogs(this) }

    // ── state ────────────────────────────────────────────────
    internal var project: Project? = null
    internal var floor: FloorData? = null
    internal var mode = TopView.Mode.VIEW
    internal var stride = 0.72f
    internal var wallH = 2.8f
    internal var wallT = 0.15f
    internal var tx1m = -38
    internal var pathLossN = 3.0f
    internal var minRssi = -88
    internal var targetArea = 250f
    internal var northOffset = 0f
    internal var autoAp = true
    internal var tapSetPos = false
    internal var wallRec = false
    internal var pendingMoveAp: APRecord? = null

    private var scanState = 0 // 0 idle, 1 running, 2 paused
    private var scanElapsedMs = 0L
    private var scanSegmentStart = 0L
    private var wallChain = ArrayList<Pt>()
    private var wallAnchors = ArrayList<Int>()
    private var focusPane = 0
    private var mapIs3d = false
    private var lastUiAt = 0L
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)

    internal val prefs by lazy { getSharedPreferences("scanmap", MODE_PRIVATE) }

    // ── activity result launchers ────────────────────────────
    private val pngTop = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { u ->
        u?.let { saveBitmap(topView.renderToBitmap(), it) }
    }
    private val pngPersp = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { u ->
        u?.let { saveBitmap(perspView.renderToBitmap(), it) }
    }
    private val pngCam = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { u ->
        u?.let {
            val b = previewView.bitmap
            if (b != null) saveBitmap(b, it) else toast(getString(R.string.cam_null))
        }
    }
    private val objOut = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        u?.let {
            saveText(ObjExporter.export(project?.floors ?: emptyList(), tx1m, pathLossN), it)
        }
    }
    private val csvOut = registerForActivityResult(ActivityResultContracts.CreateDocument("text/comma-separated-values")) { u ->
        u?.let {
            saveText(ObjExporter.exportCsv(project?.floors ?: emptyList(), tx1m, pathLossN), it)
        }
    }
    private val jsonOut = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        u?.let {
            val p = project
            if (p != null) saveText(ModelJson.projectToJson(p).toString(), it)
        }
    }
    private val jsonIn = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let {
            try {
                val txt = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                if (txt != null) {
                    val p = ModelJson.projectFromJson(JSONObject(txt))
                    switchProject(p)
                    toast(getString(R.string.imported))
                }
            } catch (e: Exception) {
                toast("خطا: ${e.message}")
            }
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.CAMERA] == true) initCamera()
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            wifiEngine.start()
            txtWarn.visibility = View.GONE
        }
        updateStatus(true)
    }

    // ── lifecycle ────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews()
        loadSettings()
        loadLastProject()
        wireUi()

        sensorEngine.listener = this
        sensorEngine.start()

        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val locOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (camOk) initCamera()
        if (locOk) {
            wifiEngine.start()
        } else {
            txtWarn.visibility = View.VISIBLE
        }
        permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
        updateStatus(true)
    }

    override fun onPause() {
        saveProject()
        super.onPause()
    }

    override fun onDestroy() {
        sensorEngine.stop()
        wifiEngine.stop()
        super.onDestroy()
    }

    private fun bindViews() {
        topView = findViewById(R.id.topView)
        topMap = findViewById(R.id.topMap)
        perspView = findViewById(R.id.perspView)
        perspMap = findViewById(R.id.perspMap)
        previewView = findViewById(R.id.previewView)
        camHud = findViewById(R.id.camHud)
        txtCamTitle = findViewById(R.id.txtCamTitle)
        txtStats = findViewById(R.id.txtStats)
        txtWarn = findViewById(R.id.txtWarn)
        txtProject = findViewById(R.id.txtProject)
        txtSub = findViewById(R.id.txtSub)
        btnFloor = findViewById(R.id.btnFloor)
        btnScanToggle = findViewById(R.id.btnScanToggle)
        btnWallRec = findViewById(R.id.btnWallRec)
        btnTapPos = findViewById(R.id.btnTapPos)
        btnApAuto = findViewById(R.id.btnApAuto)
        btnMap2d = findViewById(R.id.btnMap2d)
        btnMap3d = findViewById(R.id.btnMap3d)
        screenScan = findViewById(R.id.screenScan)
        screenMap = findViewById(R.id.screenMap)
        paneCamera = findViewById(R.id.paneCamera)
        rowPanes = findViewById(R.id.rowPanes)
        paneTop = findViewById(R.id.paneTop)
        panePersp = findViewById(R.id.panePersp)
        filesContainer = findViewById(R.id.screenFiles)
        settingsContainer = findViewById(R.id.screenSettings)
        chips = listOf(
            findViewById(R.id.btnModeView), findViewById(R.id.btnModeWalk),
            findViewById(R.id.btnModeRoom), findViewById(R.id.btnModeWall),
            findViewById(R.id.btnModeAp)
        )
        navButtons = listOf(
            findViewById(R.id.btnNavScan), findViewById(R.id.btnNavMap),
            findViewById(R.id.btnNavFiles), findViewById(R.id.btnNavSettings)
        )
    }

    private fun wireUi() {
        // nav
        navButtons[0].setOnClickListener { setTab(0) }
        navButtons[1].setOnClickListener { setTab(1) }
        navButtons[2].setOnClickListener { setTab(2) }
        navButtons[3].setOnClickListener { setTab(3) }

        // header
        btnFloor.setOnClickListener { dialogs.floors() }
        findViewById<AppCompatButton>(R.id.btnMenu).setOnClickListener { v ->
            val pm = PopupMenu(this, v)
            pm.menu.add(0, 1, 0, getString(R.string.menu_export))
            pm.menu.add(0, 2, 1, getString(R.string.menu_floors))
            pm.menu.add(0, 3, 2, getString(R.string.menu_help))
            pm.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> dialogs.export()
                    2 -> dialogs.floors()
                    3 -> dialogs.help()
                }
                true
            }
            pm.show()
        }

        // mode chips
        val modes = listOf(TopView.Mode.VIEW, TopView.Mode.WALK, TopView.Mode.ROOM, TopView.Mode.WALL, TopView.Mode.AP)
        chips.forEachIndexed { i, b -> b.setOnClickListener { applyMode(modes[i]) } }
        applyMode(TopView.Mode.VIEW)
        btnWallRec.text = getString(R.string.wall_rec) + ": " + getString(R.string.off_l)
        btnTapPos.text = getString(R.string.tap_pos) + ": " + getString(R.string.off_l)
        btnApAuto.text = getString(R.string.ap_auto) + ": " + getString(if (autoAp) R.string.on_l else R.string.off_l)

        // walk row
        btnWallRec.setOnClickListener { toggleWallRec() }
        findViewById<AppCompatButton>(R.id.btnCorner).setOnClickListener { markCorner() }
        btnTapPos.setOnClickListener {
            tapSetPos = !tapSetPos
            btnTapPos.text = getString(R.string.tap_pos) + ": " + getString(if (tapSetPos) R.string.on_l else R.string.off_l)
            btnTapPos.setBackgroundResource(if (tapSetPos) R.drawable.bg_btn_accent else R.drawable.bg_btn)
            btnTapPos.setTextColor(if (tapSetPos) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        }

        // room row
        findViewById<AppCompatButton>(R.id.btnRoomClose).setOnClickListener { commitRoom() }
        findViewById<AppCompatButton>(R.id.btnRoomUndo).setOnClickListener {
            if (topView.pendingPts.isNotEmpty()) {
                topView.pendingPts.removeAt(topView.pendingPts.size - 1)
                topView.invalidate()
            }
        }
        findViewById<AppCompatButton>(R.id.btnRoomClear).setOnClickListener {
            topView.pendingPts.clear()
            topView.invalidate()
        }

        // wall row
        findViewById<AppCompatButton>(R.id.btnWallFinish).setOnClickListener { commitPendingWall() }
        findViewById<AppCompatButton>(R.id.btnWallUndo).setOnClickListener {
            if (topView.pendingPts.isNotEmpty()) {
                topView.pendingPts.removeAt(topView.pendingPts.size - 1)
                topView.invalidate()
            }
        }

        // ap row
        btnApAuto.setOnClickListener {
            autoAp = !autoAp
            btnApAuto.text = getString(R.string.ap_auto) + ": " + getString(if (autoAp) R.string.on_l else R.string.off_l)
        }

        // scan CTA
        btnScanToggle.setOnClickListener {
            val now = System.currentTimeMillis()
            when (scanState) {
                0 -> { scanElapsedMs = 0L; scanSegmentStart = now; setScanState(1) }
                1 -> { scanElapsedMs += now - scanSegmentStart; setScanState(2) }
                else -> { scanSegmentStart = now; setScanState(1) }
            }
        }

        // focus buttons
        findViewById<AppCompatButton>(R.id.btnFocusCam).setOnClickListener { setFocus(if (focusPane == 1) 0 else 1) }
        findViewById<AppCompatButton>(R.id.btnFocusTop).setOnClickListener { setFocus(if (focusPane == 2) 0 else 2) }
        findViewById<AppCompatButton>(R.id.btnFocusPersp).setOnClickListener { setFocus(if (focusPane == 3) 0 else 3) }

        // map toggle
        btnMap2d.setOnClickListener { setMap3d(false) }
        btnMap3d.setOnClickListener { setMap3d(true) }
        findViewById<AppCompatButton>(R.id.btnApChip).setOnClickListener { dialogs.apList() }
        findViewById<AppCompatButton>(R.id.btnExportChip).setOnClickListener { dialogs.export() }

        // top view interactions
        topView.onTap = { p ->
            when (mode) {
                TopView.Mode.WALK -> if (tapSetPos) {
                    sensorEngine.setPosition(p.x, p.y)
                    toast("موقعیت تنظیم شد")
                }
                TopView.Mode.ROOM -> { topView.pendingPts.add(p); topView.invalidate() }
                TopView.Mode.WALL -> { topView.pendingPts.add(p); topView.invalidate() }
                TopView.Mode.AP -> {
                    val mv = pendingMoveAp
                    if (mv != null) { moveAp(mv, p) } else dialogs.pickApForPlacement(p)
                }
                TopView.Mode.VIEW -> apAt(p)?.let { dialogs.apDetails(it) }
            }
        }
        topView.onLongTap = { p -> if (mode == TopView.Mode.VIEW) deleteNear(p) }

        topMap.onTap = { p ->
            val mv = pendingMoveAp
            if (mv != null) moveAp(mv, p) else apAt(p)?.let { dialogs.apDetails(it) }
        }
        topMap.onLongTap = { p -> deleteNear(p) }

        buildSettingsScreen()
        buildFilesScreen()
    }

    // ── tabs & focus ─────────────────────────────────────────
    internal fun setTab(i: Int) {
        screenScan.visibility = if (i == 0) View.VISIBLE else View.GONE
        screenMap.visibility = if (i == 1) View.VISIBLE else View.GONE
        filesContainer.visibility = if (i == 2) View.VISIBLE else View.GONE
        settingsContainer.visibility = if (i == 3) View.VISIBLE else View.GONE
        val on = listOf(R.drawable.bg_chip_on, R.drawable.bg_chip_on, R.drawable.bg_chip_on, R.drawable.bg_chip_on)
        for (j in navButtons.indices) {
            if (j == i) {
                navButtons[j].setBackgroundResource(on[j])
                navButtons[j].setTextColor(0xFFFFFFFF.toInt())
            } else {
                navButtons[j].background = null
                navButtons[j].setTextColor(0xFF64748B.toInt())
            }
        }
        if (i == 2) refreshFilesScreen()
        if (i == 3) refreshDiagnostics()
        if (i == 1) topMap.post { topMap.fitContent() }
    }

    private fun setFocus(pane: Int) {
        focusPane = pane
        paneCamera.visibility = if (pane == 0 || pane == 1) View.VISIBLE else View.GONE
        rowPanes.visibility = if (pane == 1) View.GONE else View.VISIBLE
        paneTop.visibility = if (pane == 0 || pane == 2) View.VISIBLE else View.GONE
        panePersp.visibility = if (pane == 0 || pane == 3) View.VISIBLE else View.GONE
    }

    private fun setMap3d(v: Boolean) {
        mapIs3d = v
        topMap.visibility = if (v) View.GONE else View.VISIBLE
        perspMap.visibility = if (v) View.VISIBLE else View.GONE
        btnMap2d.setBackgroundResource(if (v) R.drawable.bg_btn else R.drawable.bg_btn_accent)
        btnMap2d.setTextColor(if (v) 0xFF0F172A.toInt() else 0xFFFFFFFF.toInt())
        btnMap3d.setBackgroundResource(if (v) R.drawable.bg_btn_accent else R.drawable.bg_btn)
        btnMap3d.setTextColor(if (v) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
    }

    // ── modes ────────────────────────────────────────────────
    internal fun applyMode(m: TopView.Mode) {
        if ((mode == TopView.Mode.ROOM || mode == TopView.Mode.WALL) && m != mode) {
            topView.pendingPts.clear()
        }
        mode = m
        topView.mode = m
        val names = listOf(R.string.mode_view, R.string.mode_walk, R.string.mode_room, R.string.mode_wall, R.string.mode_ap)
        val modes = listOf(TopView.Mode.VIEW, TopView.Mode.WALK, TopView.Mode.ROOM, TopView.Mode.WALL, TopView.Mode.AP)
        chips.forEachIndexed { i, b ->
            val sel = modes[i] == m
            b.setBackgroundResource(if (sel) R.drawable.bg_chip_on else R.drawable.bg_chip)
            b.setTextColor(if (sel) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
            b.setText(names[i])
        }
        findViewById<LinearLayout>(R.id.rowWalk).visibility = if (m == TopView.Mode.WALK) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.rowRoom).visibility = if (m == TopView.Mode.ROOM) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.rowWall).visibility = if (m == TopView.Mode.WALL) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.rowAp).visibility = if (m == TopView.Mode.AP) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.txtViewHint).visibility = if (m == TopView.Mode.VIEW) View.VISIBLE else View.GONE
    }

    // ── walking / walls ──────────────────────────────────────
    private fun toggleWallRec() {
        wallRec = !wallRec
        btnWallRec.text = getString(R.string.wall_rec) + ": " + getString(if (wallRec) R.string.on_l else R.string.off_l)
        btnWallRec.setBackgroundResource(if (wallRec) R.drawable.bg_btn_accent else R.drawable.bg_btn)
        btnWallRec.setTextColor(if (wallRec) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        if (wallRec) {
            wallChain.clear()
            wallAnchors.clear()
            wallChain.add(Pt(sensorEngine.x, sensorEngine.y))
            wallAnchors.add(0)
        } else {
            commitWallChain()
        }
    }

    private fun markCorner() {
        if (!wallRec) return
        val p = Pt(sensorEngine.x, sensorEngine.y)
        val last = wallChain.lastOrNull()
        if (last == null || GeoUtils.dist(last, p) > 0.3f) {
            wallChain.add(p)
            wallAnchors.add(wallChain.size - 1)
            toast("گوشه ثبت شد")
        }
    }

    internal fun commitWallChain() {
        if (wallChain.size >= 3) {
            val fl = floor
            if (fl != null) {
                val simp = GeoUtils.simplifyAnchored(wallChain, wallAnchors.toSet(), 0.3f)
                for (i in 1 until simp.size) {
                    val a = simp[i - 1]
                    val b = simp[i]
                    if (GeoUtils.dist(a, b) > 0.15f && !wallExists(fl, a, b)) {
                        fl.walls.add(WallSeg(a, b, wallT, wallH))
                    }
                }
            }
        }
        wallChain.clear()
        wallAnchors.clear()
        if (wallRec) {
            wallRec = false
            btnWallRec.text = getString(R.string.wall_rec) + ": " + getString(R.string.off_l)
            btnWallRec.setBackgroundResource(R.drawable.bg_btn)
            btnWallRec.setTextColor(0xFF0F172A.toInt())
        }
        saveProject()
        invalidateViews()
    }

    private fun commitPendingWall() {
        val fl = floor ?: return
        val pts = topView.pendingPts
        if (pts.size >= 2) {
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                if (GeoUtils.dist(a, b) > 0.15f && !wallExists(fl, a, b)) {
                    fl.walls.add(WallSeg(a, b, wallT, wallH))
                }
            }
        }
        pts.clear()
        topView.invalidate()
        saveProject()
    }

    private fun commitRoom() {
        val fl = floor ?: return
        val pts = ArrayList(topView.pendingPts)
        if (pts.size < 3) {
            toast("حداقل ۳ نقطه لازم است")
            return
        }
        val input = EditText(this)
        input.setText(getString(R.string.room_default) + " " + (fl.rooms.size + 1))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.room_close))
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.room_default) }
                fl.rooms.add(RoomPoly(name, pts))
                for (i in pts.indices) {
                    val a = pts[i]
                    val b = pts[(i + 1) % pts.size]
                    if (GeoUtils.dist(a, b) > 0.15f && !wallExists(fl, a, b)) {
                        fl.walls.add(WallSeg(a, b, wallT, wallH))
                    }
                }
                topView.pendingPts.clear()
                topView.invalidate()
                saveProject()
                updateStatus(true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun wallExists(fl: FloorData, a: Pt, b: Pt): Boolean {
        for (w in fl.walls) {
            if ((GeoUtils.dist(w.a, a) < 0.05f && GeoUtils.dist(w.b, b) < 0.05f) ||
                (GeoUtils.dist(w.a, b) < 0.05f && GeoUtils.dist(w.b, a) < 0.05f)
            ) return true
        }
        return false
    }

    // ── AP helpers ───────────────────────────────────────────
    internal fun apAt(p: Pt): APRecord? {
        val fl = floor ?: return null
        var best: APRecord? = null
        var bestD = 1.2f
        for (ap in fl.aps) {
            val d = GeoUtils.dist(p, Pt(ap.x, ap.y))
            if (d < bestD) { bestD = d; best = ap }
        }
        return best
    }

    internal fun moveAp(ap: APRecord, p: Pt) {
        ap.x = p.x; ap.y = p.y
        ap.manual = true
        ap.confidence = 1f
        pendingMoveAp = null
        saveProject()
        invalidateViews()
        toast("AP جابه‌جا شد")
    }

    internal fun securityOf(caps: String): String = when {
        caps.contains("WPA3", true) && caps.contains("Enterprise", true) -> "WPA3-Enterprise"
        caps.contains("WPA3", true) -> "WPA3"
        caps.contains("WPA2", true) && caps.contains("Enterprise", true) -> "WPA2-Enterprise"
        caps.contains("WPA2", true) -> "WPA2"
        caps.contains("WPA", true) -> "WPA"
        caps.contains("WEP", true) -> "WEP"
        else -> "Open"
    }

    private fun apDistance(ap: APRecord): Float =
        Math.pow(10.0, (tx1m - ap.rssi) / (10.0 * pathLossN)).toFloat().coerceIn(0.5f, 150f)

    private fun mergeApResults(list: List<WifiEngine.ScanRes>) {
        val fl = floor ?: return
        val now = System.currentTimeMillis()
        var changed = false
        for (r in list) {
            if (r.rssi < minRssi) continue
            val ex = fl.aps.firstOrNull { it.bssid.equals(r.bssid, ignoreCase = true) }
            if (ex == null) {
                if (!autoAp) continue
                val ap = APRecord(
                    r.bssid,
                    r.ssid.ifEmpty { "Hidden-" + r.bssid.takeLast(5).replace(":", "") },
                    r.freq, securityOf(r.caps),
                    sensorEngine.x, sensorEngine.y, 0.15f,
                    r.rssi.toFloat(), r.rssi, ArrayList(), now, now, false
                )
                ap.samples.add(RssiSample(now, sensorEngine.walked, r.rssi, sensorEngine.x, sensorEngine.y))
                fl.aps.add(ap)
                changed = true
            } else {
                ex.rssi = 0.6f * ex.rssi + 0.4f * r.rssi
                if (r.rssi > ex.rssiBest) ex.rssiBest = r.rssi
                ex.lastSeen = now
                ex.freq = r.freq
                ex.samples.add(RssiSample(now, sensorEngine.walked, r.rssi, sensorEngine.x, sensorEngine.y))
                if (ex.samples.size > 120) ex.samples.removeAt(0)
                if (!ex.manual && now - ex.firstSeen > 3500) estimateApPosition(ex)
                changed = true
            }
        }
        if (changed) {
            invalidateViews()
            dialogs.refreshApList()
            updateStatus(true)
        }
    }

    /** Least-squares grid estimator for AP position from walked (pos, rssi) samples. */
    private fun estimateApPosition(ap: APRecord) {
        val samples = ap.samples.takeLast(40)
        if (samples.size < 4) {
            ap.confidence = min(0.3f, samples.size * 0.1f)
            return
        }
        val ds = ArrayList<Float>(samples.size)
        for (s in samples) {
            ds.add(Math.pow(10.0, (tx1m - s.rssi) / (10.0 * pathLossN)).toFloat().coerceIn(0.3f, 150f))
        }
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in samples) {
            minX = min(minX, s.x - 25f); maxX = max(maxX, s.x + 25f)
            minY = min(minY, s.y - 25f); maxY = max(maxY, s.y + 25f)
        }
        var bestErr = Double.MAX_VALUE
        var bx = ap.x; var by = ap.y
        var gx = minX
        while (gx <= maxX) {
            var gy = minY
            while (gy <= maxY) {
                var err = 0.0
                for (i in samples.indices) {
                    val dx = gx - samples[i].x
                    val dy = gy - samples[i].y
                    val dd = ds[i] - sqrt(dx * dx + dy * dy)
                    err += (dd * dd).toDouble()
                }
                if (err < bestErr) { bestErr = err; bx = gx; by = gy }
                gy += 1.5f
            }
            gx += 1.5f
        }
        var rx = bx; var ry = by
        bestErr = Double.MAX_VALUE
        var fx = bx - 1.5f
        while (fx <= bx + 1.5f) {
            var fy = by - 1.5f
            while (fy <= by + 1.5f) {
                var err = 0.0
                for (i in samples.indices) {
                    val dx = fx - samples[i].x
                    val dy = fy - samples[i].y
                    val dd = ds[i] - sqrt(dx * dx + dy * dy)
                    err += (dd * dd).toDouble()
                }
                if (err < bestErr) { bestErr = err; rx = fx; ry = fy }
                fy += 0.25f
            }
            fx += 0.25f
        }
        ap.x = rx; ap.y = ry
        val spread = HashSet<String>()
        for (s in samples.takeLast(10)) spread.add("${(s.x / 4).toInt()},${(s.y / 4).toInt()}")
        ap.confidence = (0.35f + spread.size * 0.08f + min(samples.size, 30) * 0.008f).coerceAtMost(0.96f)
    }

    private fun deleteNear(p: Pt) {
        val fl = floor ?: return
        val options = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val ap = apAt(p)
        if (ap != null) {
            options.add("حذف AP: " + (ap.ssid.ifEmpty { ap.bssid }))
            actions.add { fl.aps.remove(ap); saveProject(); invalidateViews() }
        }
        var nearWall: WallSeg? = null
        var wd = 0.4f
        for (w in fl.walls) {
            val d = GeoUtils.ptSegDist(p, w.a, w.b)
            if (d < wd) { wd = d; nearWall = w }
        }
        if (nearWall != null) {
            options.add("حذف دیوار انتخابی")
            actions.add { fl.walls.remove(nearWall); saveProject(); invalidateViews() }
        }
        var inRoom: RoomPoly? = null
        for (r in fl.rooms) if (GeoUtils.pointInPoly(p, r.pts)) { inRoom = r; break }
        if (inRoom != null) {
            options.add("حذف «" + inRoom.name + "» و دیوارهای آن")
            actions.add {
                fl.rooms.remove(inRoom)
                val n = inRoom.pts.size
                val edges = ArrayList<Pair<Pt, Pt>>()
                for (i in 0 until n) edges.add(Pair(inRoom.pts[i], inRoom.pts[(i + 1) % n]))
                fl.walls.removeAll { w ->
                    edges.any { e ->
                        (GeoUtils.dist(w.a, e.first) < 0.05f && GeoUtils.dist(w.b, e.second) < 0.05f) ||
                            (GeoUtils.dist(w.a, e.second) < 0.05f && GeoUtils.dist(w.b, e.first) < 0.05f)
                    }
                }
                saveProject(); invalidateViews()
            }
        }
        if (options.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setItems(options.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── engines callbacks ────────────────────────────────────
    override fun onPose(x: Float, y: Float, headingDeg: Float) {
        topView.poseX = x; topView.poseY = y; topView.poseHeading = headingDeg
        topMap.poseX = x; topMap.poseY = y; topMap.poseHeading = headingDeg
        perspView.poseX = x; perspView.poseY = y; perspView.poseHeading = headingDeg
        perspMap.poseX = x; perspMap.poseY = y; perspMap.poseHeading = headingDeg
        if (focusPane != 1) {
            topView.invalidate(); perspView.invalidate()
            topMap.invalidate(); perspMap.invalidate()
        } else {
            topView.invalidate()
        }
        updateStatus(false)
    }

    override fun onStep() {
        val fl = floor ?: return
        if (scanState == 1) {
            val p = Pt(sensorEngine.x, sensorEngine.y)
            val last = fl.path.lastOrNull()
            if (last == null || GeoUtils.dist(last, p) > 0.3f) {
                fl.path.add(p)
                if (fl.path.size > 20000) fl.path.removeAt(0)
            }
            if (wallRec) {
                val wl = wallChain.lastOrNull()
                if (wl == null || GeoUtils.dist(wl, p) > 0.25f) wallChain.add(p)
            }
        }
    }

    override fun onScan(list: List<WifiEngine.ScanRes>, atMs: Long) {
        mergeApResults(list)
        refreshDiagnostics()
    }

    // ── status / stats ───────────────────────────────────────
    internal fun updateStatus(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUiAt < 300) return
        lastUiAt = now
        val fl = floor
        val area = fl?.rooms?.sumOf { it.area().toDouble() } ?: 0.0
        val targetWalk = (4f * sqrt(max(4f, targetArea)))
        val progress = (sensorEngine.walked / targetWalk * 100f).coerceIn(0f, 100f)
        val elapsed = scanElapsedMs + (if (scanState == 1) now - scanSegmentStart else 0L)
        val mm = elapsed / 60000L
        val ss = (elapsed % 60000L) / 1000L
        val st = if (scanState == 1) "فعال" else getString(R.string.status_paused)
        txtStats.text =
            getString(R.string.st_progress) + ": " + GeoUtils.fmt0(progress) + "%   |   " +
                getString(R.string.st_area) + ": " + GeoUtils.fmt1(area.toFloat()) + " m²\n" +
                getString(R.string.st_wifi) + ": " + (fl?.aps?.size ?: 0) + "   |   " +
                getString(R.string.st_path) + ": " + GeoUtils.fmt0(sensorEngine.walked) + " m   |   " +
                getString(R.string.st_steps) + ": " + sensorEngine.steps + "\n" +
                getString(R.string.st_head) + ": " + GeoUtils.fmt0(sensorEngine.headingDeg) + "°   |   " +
                getString(R.string.st_time) + ": " + String.format(Locale.US, "%02d:%02d", mm, ss) +
                "   |   " + st
        camHud.lines = listOf(
            getString(R.string.st_head) + ": " + GeoUtils.fmt0(sensorEngine.headingDeg) + "°",
            getString(R.string.st_path) + ": " + GeoUtils.fmt0(sensorEngine.walked) + " m",
            "AP: " + (fl?.aps?.size ?: 0)
        )
        camHud.invalidate()
    }

    private fun refreshDiagnostics() {
        val v = findViewById<TextView?>(R.id.txtDiag) ?: return
        v.text =
            "جهت: " + GeoUtils.fmt0(sensorEngine.headingDeg) + "°   |   گام: " + sensorEngine.steps +
                "   |   مسیر: " + GeoUtils.fmt0(sensorEngine.walked) + " m\n" +
                "rotation vector: " + (if (sensorEngine.hasRotationVector) "دارد" else "ندارد") +
                "   |   قطب‌نما: " + (if (sensorEngine.hasMagnetometer) "دارد" else "ندارد") +
                "   |   گام‌شمار سیستم: " + (if (sensorEngine.hasStepDetector) "دارد" else "ندارد") + "\n" +
                "آخرین اسکن وای‌فای: " +
                (if (wifiEngine.lastScanAt > 0) sdf.format(Date(wifiEngine.lastScanAt)) else "—") +
                "   |   نتایج: " + wifiEngine.lastResults.size
    }

    // ── camera ───────────────────────────────────────────────
    private fun initCamera() {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                } catch (e: Exception) {
                    toast("دوربین: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            toast("دوربین: ${e.message}")
        }
    }

    // ── scan state ───────────────────────────────────────────
    private fun setScanState(s: Int) {
        scanState = s
        when (s) {
            0 -> {
                btnScanToggle.text = getString(R.string.cta_start)
                btnScanToggle.setBackgroundResource(R.drawable.bg_cta)
                sensorEngine.tracking = false
                txtCamTitle.text = getString(R.string.cam_idle)
            }
            1 -> {
                btnScanToggle.text = getString(R.string.cta_pause)
                btnScanToggle.setBackgroundResource(R.drawable.bg_cta_stop)
                sensorEngine.tracking = true
                txtCamTitle.text = getString(R.string.cam_scanning) + " " +
                    (project?.name ?: "") + " — " + (floor?.name ?: "")
            }
            else -> {
                btnScanToggle.text = getString(R.string.cta_resume)
                btnScanToggle.setBackgroundResource(R.drawable.bg_cta)
                sensorEngine.tracking = false
                txtCamTitle.text = getString(R.string.status_paused)
            }
        }
        if (s != 1) commitWallChain()
        updateStatus(true)
    }

    // ── projects ─────────────────────────────────────────────
    private fun projectsDir(): File = File(filesDir, "projects").apply { mkdirs() }

    internal fun saveProject() {
        val p = project ?: return
        try {
            val dir = projectsDir()
            val tmp = File(dir, p.id + ".tmp")
            tmp.writeText(ModelJson.projectToJson(p).toString())
            val f = File(dir, p.id + ".json")
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            prefs.edit().putString("lastProjectId", p.id).apply()
            txtSub.text = "آخرین ذخیره: " + sdf.format(Date(System.currentTimeMillis()))
        } catch (_: Exception) {
        }
    }

    internal fun listProjects(): MutableList<Project> {
        val out = ArrayList<Project>()
        val files = projectsDir().listFiles { f -> f.name.endsWith(".json") } ?: return out
        files.sortByDescending { it.lastModified() }
        for (f in files) {
            try {
                val txt = f.readText()
                out.add(ModelJson.projectFromJson(JSONObject(txt)))
            } catch (_: Exception) {
            }
        }
        return out
    }

    internal fun switchProject(p: Project) {
        project = p
        floor = p.activeFloor()
        bindFloorToViews()
        saveProject()
        refreshFilesScreen()
        updateStatus(true)
    }

    private fun loadLastProject() {
        val id = prefs.getString("lastProjectId", null)
        if (id != null) {
            val f = File(projectsDir(), id + ".json")
            if (f.exists()) {
                try {
                    project = ModelJson.projectFromJson(JSONObject(f.readText()))
                } catch (_: Exception) {
                }
            }
        }
        if (project == null) project = Project.create(getString(R.string.project_default))
        floor = project!!.activeFloor()
        bindFloorToViews()
    }

    internal fun bindFloorToViews() {
        val p = project
        val fl = floor
        txtProject.text = p?.name ?: getString(R.string.app_name)
        btnFloor.text = fl?.name ?: getString(R.string.floor_default)
        topView.floor = fl
        topMap.floor = fl
        perspView.floor = fl
        perspMap.floor = fl
        topView.tx1m = tx1m
        topMap.tx1m = tx1m
        topView.pathLossN = pathLossN
        topMap.pathLossN = pathLossN
        topView.invalidate(); topMap.invalidate(); perspView.invalidate(); perspMap.invalidate()
        topView.post { topView.fitContent() }
        topMap.post { topMap.fitContent() }
        updateStatus(true)
    }

    // ── settings ─────────────────────────────────────────────
    private fun loadSettings() {
        stride = prefs.getFloat("stride", 0.72f)
        wallH = prefs.getFloat("wallH", 2.8f)
        wallT = prefs.getFloat("wallT", 0.15f)
        tx1m = prefs.getInt("tx1m", -38)
        pathLossN = prefs.getFloat("pathLossN", 3.0f)
        minRssi = prefs.getInt("minRssi", -88)
        targetArea = prefs.getFloat("targetArea", 250f)
        northOffset = prefs.getFloat("northOffset", 0f)
        autoAp = prefs.getBoolean("autoAp", true)
        sensorEngine.stride = stride
        sensorEngine.northOffset = northOffset
    }

    internal fun applySettings(
        strideCm: Float, wallHm: Float, wallTcm: Float,
        tx: Int, minR: Int, target: Float, north: Float, auto: Boolean
    ) {
        stride = (strideCm / 100f).coerceIn(0.3f, 1.5f)
        wallH = wallHm.coerceIn(1.5f, 6f)
        wallT = (wallTcm / 100f).coerceIn(0.05f, 0.6f)
        tx1m = tx
        minRssi = minR
        targetArea = target.coerceIn(10f, 10000f)
        northOffset = north
        autoAp = auto
        sensorEngine.stride = stride
        sensorEngine.northOffset = northOffset
        prefs.edit()
            .putFloat("stride", stride)
            .putFloat("wallH", wallH)
            .putFloat("wallT", wallT)
            .putInt("tx1m", tx1m)
            .putInt("minRssi", minRssi)
            .putFloat("targetArea", targetArea)
            .putFloat("northOffset", northOffset)
            .putBoolean("autoAp", autoAp)
            .apply()
        toast(getString(R.string.saved))
    }

    // ── exports ──────────────────────────────────────────────
    internal fun runExport(kind: String) {
        saveProject()
        val base = GeoUtils.sanitizeName((project?.name ?: "scanmap") + "-" + (floor?.name ?: "floor"))
        when (kind) {
            "top" -> pngTop.launch("$base-top.png")
            "persp" -> pngPersp.launch("$base-3d.png")
            "cam" -> pngCam.launch("$base-camera.png")
            "obj" -> objOut.launch("$base.obj")
            "csv" -> csvOut.launch("$base-aps.csv")
            "json" -> jsonOut.launch("$base.json")
        }
    }

    internal fun runImport() {
        jsonIn.launch(arrayOf("application/json", "text/plain"))
    }

    internal fun saveBitmap(bmp: Bitmap, uri: android.net.Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast(getString(R.string.saved))
        } catch (e: Exception) {
            toast("خطا: ${e.message}")
        }
    }

    internal fun saveText(text: String, uri: android.net.Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            toast(getString(R.string.saved))
        } catch (e: Exception) {
            toast("خطا: ${e.message}")
        }
    }

    // ── misc ─────────────────────────────────────────────────
    internal fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    internal fun invalidateViews() {
        topView.invalidate()
        topMap.invalidate()
        perspView.invalidate()
        perspMap.invalidate()
        refreshFilesScreen()
    }

    // ── files screen (built in code, like the reference) ─────
    private var filesScroll: ScrollView? = null

    private fun buildFilesScreen() {
        val sc = ScrollView(this)
        sc.setBackgroundColor(0xFFF1F5F9.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(12), dp(12), dp(12))
        sc.addView(col)
        filesContainer.addView(sc)
        filesScroll = sc
    }

    internal fun refreshFilesScreen() {
        val sc = filesScroll ?: return
        val col = sc.getChildAt(0) as? LinearLayout ?: return
        col.removeAllViews()
        val p = project ?: return
        val fl = floor

        // current project card
        col.addView(makeCardTitle(p.name))
        val info = TextView(this)
        info.text = "طبقه‌ها: ${p.floors.size}   |   APها: ${p.floors.sumOf { it.aps.size }}   |   " +
            "مسیر کل: ${GeoUtils.fmt0(p.floors.sumOf { GeoUtils.pathLength(it.path).toDouble() }.toFloat())} m"
        info.setTextColor(0xFF64748B.toInt())
        info.textSize = 12f
        info.setPadding(dp(4), dp(2), dp(4), dp(6))
        col.addView(info)

        val cta = AppCompatButton(this)
        cta.text = getString(R.string.scan_new_floor)
        cta.setBackgroundResource(R.drawable.bg_cta)
        cta.setTextColor(0xFFFFFFFF.toInt())
        cta.textSize = 14f
        cta.setTypeface(null, android.graphics.Typeface.BOLD)
        cta.minWidth = 0
        cta.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(4)
        }
        cta.setOnClickListener { dialogs.addFloorFlow(true) }
        col.addView(cta)

        // floors summary (read-only line)
        val floorsLine = TextView(this)
        floorsLine.text = "طبقات: " + p.floors.joinToString("، ") { it.name }
        floorsLine.setTextColor(0xFF64748B.toInt())
        floorsLine.textSize = 11f
        floorsLine.setPadding(dp(4), dp(8), dp(4), dp(2))
        col.addView(floorsLine)

        // saved projects card
        col.addView(makeCardTitle(getString(R.string.projects_title)))
        val list = listProjects()
        if (list.isEmpty()) {
            col.addView(makeSubText("هنوز پروژه‌ای ذخیره نشده است"))
        }
        for (pr in list) {
            val row = TextView(this)
            val dt = SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date(pr.createdAt))
            row.text = (if (pr.id == p.id) "● " else "○ ") + pr.name +
                "   (" + pr.floors.size + " طبقه — " + dt + ")"
            row.setTextColor(if (pr.id == p.id) 0xFF0F766E.toInt() else 0xFF0F172A.toInt())
            row.textSize = 13f
            row.setPadding(dp(4), dp(8), dp(4), dp(8))
            row.setOnClickListener {
                if (pr.id != p.id) switchProject(pr)
            }
            col.addView(row)
        }
        val prRow = LinearLayout(this)
        prRow.orientation = LinearLayout.HORIZONTAL
        prRow.addView(makeSmallBtn(getString(R.string.new_project)) { dialogs.newProjectFlow() })
        prRow.addView(makeSmallBtn(getString(R.string.rename)) { dialogs.renameProjectFlow() })
        prRow.addView(makeSmallBtn(getString(R.string.delete)) { dialogs.deleteProjectFlow() })
        col.addView(prRow)

        // json io card
        val ioRow = LinearLayout(this)
        ioRow.orientation = LinearLayout.HORIZONTAL
        ioRow.setPadding(0, dp(10), 0, 0)
        ioRow.addView(makeSmallBtn(getString(R.string.export_json)) { runExport("json") })
        ioRow.addView(makeSmallBtn(getString(R.string.import_json)) { runImport() })
        col.addView(ioRow)
    }

    private fun makeCardTitle(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.setTextColor(0xFF0F172A.toInt())
        tv.textSize = 15f
        tv.setTypeface(null, android.graphics.Typeface.BOLD)
        tv.setBackgroundResource(R.drawable.bg_card)
        tv.setPadding(dp(12), dp(10), dp(12), dp(10))
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(4) }
        return tv
    }

    private fun makeSubText(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.setTextColor(0xFF94A3B8.toInt())
        tv.textSize = 12f
        tv.setPadding(dp(6), dp(6), dp(6), dp(6))
        return tv
    }

    private fun makeSmallBtn(label: String, onClick: () -> Unit): AppCompatButton {
        val b = AppCompatButton(this)
        b.text = label
        b.setBackgroundResource(R.drawable.bg_btn)
        b.setTextColor(0xFF0F172A.toInt())
        b.textSize = 12f
        b.isAllCaps = false
        b.minWidth = 0
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)
        ).apply { marginEnd = dp(8) }
        b.setOnClickListener { onClick() }
        return b
    }

    // ── settings screen (built in code) ──────────────────────
    private lateinit var eStride: EditText
    private lateinit var eWallH: EditText
    private lateinit var eWallT: EditText
    private lateinit var eTx: EditText
    private lateinit var eMinR: EditText
    private lateinit var eTarget: EditText
    private lateinit var eNorth: EditText

    private fun buildSettingsScreen() {
        val sc = ScrollView(this)
        sc.setBackgroundColor(0xFFF1F5F9.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(12), dp(12), dp(24))

        val title = makeCardTitle(getString(R.string.settings_title))
        col.addView(title)
        eStride = addNumField(col, getString(R.string.set_stride), (stride * 100).toInt().toString())
        eWallH = addNumField(col, getString(R.string.set_wall_h), GeoUtils.fmt1(wallH))
        eWallT = addNumField(col, getString(R.string.set_wall_t), (wallT * 100).toInt().toString())
        eTx = addNumField(col, getString(R.string.set_tx), tx1m.toString())
        eMinR = addNumField(col, getString(R.string.set_minrssi), minRssi.toString())
        eTarget = addNumField(col, getString(R.string.set_target), targetArea.toInt().toString())
        eNorth = addNumField(col, getString(R.string.set_north), GeoUtils.fmt0(northOffset))

        val save = AppCompatButton(this)
        save.text = getString(R.string.set_save)
        save.setBackgroundResource(R.drawable.bg_btn_accent)
        save.setTextColor(0xFFFFFFFF.toInt())
        save.textSize = 14f
        save.setTypeface(null, android.graphics.Typeface.BOLD)
        save.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(10)
        }
        save.setOnClickListener {
            applySettings(
                eStride.text.toString().toFloatOrNull() ?: 72f,
                eWallH.text.toString().toFloatOrNull() ?: 2.8f,
                eWallT.text.toString().toFloatOrNull() ?: 15f,
                eTx.text.toString().toIntOrNull() ?: -38,
                eMinR.text.toString().toIntOrNull() ?: -88,
                eTarget.text.toString().toFloatOrNull() ?: 250f,
                eNorth.text.toString().toFloatOrNull() ?: 0f,
                autoAp
            )
            bindFloorToViews()
        }
        col.addView(save)

        val diagTitle = makeCardTitle(getString(R.string.diag_title))
        (diagTitle.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        col.addView(diagTitle)
        val diag = TextView(this)
        diag.id = R.id.txtDiag
        diag.setTextColor(0xFF334155.toInt())
        diag.textSize = 12f
        diag.setBackgroundResource(R.drawable.bg_card)
        diag.setPadding(dp(12), dp(10), dp(12), dp(10))
        col.addView(diag)

        val helpTitle = makeCardTitle(getString(R.string.help_title))
        (helpTitle.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        col.addView(helpTitle)
        val help = TextView(this)
        help.text = getString(R.string.help_text)
        help.setTextColor(0xFF334155.toInt())
        help.textSize = 12f
        help.setBackgroundResource(R.drawable.bg_card)
        help.setPadding(dp(12), dp(10), dp(12), dp(12))
        help.setLineSpacing(0f, 1.25f)
        col.addView(help)

        settingsContainer.addView(sc)
    }

    internal fun addNumField(parent: LinearLayout, label: String, value: String): EditText {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(6), 0, dp(6))
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFF0F172A.toInt())
        tv.textSize = 13f
        tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
        val ed = EditText(this)
        ed.setText(value)
        ed.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        ed.textSize = 13f
        ed.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(tv)
        row.addView(ed)
        parent.addView(row)
        return ed
    }

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

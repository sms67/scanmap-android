package com.scanmap.org

import android.graphics.Typeface
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All dialogs (floors, projects, AP list/details, exports, help). */
class UiDialogs(private val act: MainActivity) {

    private fun dp(v: Int): Int = act.dp(v)

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val tv = TextView(act)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        return tv
    }

    private fun btn(t: String, accent: Boolean, onClick: () -> Unit): AppCompatButton {
        val b = AppCompatButton(act)
        b.text = t
        b.isAllCaps = false
        b.minWidth = 0
        b.textSize = 12f
        b.setBackgroundResource(if (accent) R.drawable.bg_btn_accent else R.drawable.bg_btn)
        b.setTextColor(if (accent) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)
        ).apply { marginEnd = dp(8) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun prompt(title: String, preset: String, onOk: (String) -> Unit) {
        val input = EditText(act)
        input.setText(preset)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setPadding(dp(16), dp(12), dp(16), dp(12))
        AlertDialog.Builder(act)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirm(msg: String, onOk: () -> Unit) {
        AlertDialog.Builder(act)
            .setTitle(R.string.delete)
            .setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ -> onOk() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── floors ───────────────────────────────────────────────
    private var floorsList: LinearLayout? = null

    fun floors() {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(10), dp(18), dp(0))
        scroll.addView(col)
        floorsList = col
        val actions = LinearLayout(act)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(dp(14), dp(4), dp(14), dp(6))
        actions.addView(btn(act.getString(R.string.add_floor), true) { addFloorFlow(false) })
        actions.addView(btn(act.getString(R.string.rename), false) {
            act.floor?.let { f ->
                prompt(act.getString(R.string.rename), f.name) { nm ->
                    if (nm.isNotEmpty()) {
                        f.name = nm
                        act.bindFloorToViews()
                        act.saveProject()
                        rebuildFloors()
                    }
                }
            }
        })
        actions.addView(btn(act.getString(R.string.delete), false) { deleteFloor() })
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320))
        )
        root.addView(actions)
        rebuildFloors()
        AlertDialog.Builder(act)
            .setTitle(R.string.floors_title)
            .setView(root)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun rebuildFloors() {
        val col = floorsList ?: return
        col.removeAllViews()
        val p = act.project ?: return
        for (f in p.floors) {
            val row = label(
                (if (f.id == p.activeFloorId) "● " else "○ ") + f.name +
                    "   (سطح " + f.level + ")",
                14f,
                if (f.id == p.activeFloorId) 0xFF0F766E.toInt() else 0xFF0F172A.toInt(),
                f.id == p.activeFloorId
            )
            row.setPadding(0, dp(10), 0, dp(10))
            row.setOnClickListener {
                act.floor = f
                act.project?.activeFloorId = f.id
                act.bindFloorToViews()
                act.saveProject()
                rebuildFloors()
            }
            col.addView(row)
        }
    }

    fun addFloorFlow(switchToScan: Boolean) {
        val p = act.project ?: return
        val maxLevel = p.floors.maxOfOrNull { it.level } ?: -1
        val defName = if (p.floors.isEmpty()) act.getString(R.string.floor_default)
        else act.getString(R.string.floor_auto) + " " + (maxLevel + 1)
        prompt(act.getString(R.string.add_floor), defName) { nm ->
            val name = nm.ifEmpty { defName }
            val f = FloorData.create(name, maxLevel + 1)
            p.floors.add(f)
            act.floor = f
            p.activeFloorId = f.id
            act.bindFloorToViews()
            act.saveProject()
            rebuildFloors()
            if (switchToScan) act.setTab(0)
        }
    }

    private fun deleteFloor() {
        val p = act.project ?: return
        val fl = act.floor ?: return
        if (p.floors.size <= 1) {
            act.toast("آخرین طبقه قابل حذف نیست")
            return
        }
        confirm(act.getString(R.string.confirm_del)) {
            p.floors.remove(fl)
            act.floor = p.floors.firstOrNull()
            p.activeFloorId = act.floor?.id ?: ""
            act.bindFloorToViews()
            act.saveProject()
            rebuildFloors()
        }
    }

    // ── projects ─────────────────────────────────────────────
    fun newProjectFlow() {
        prompt(act.getString(R.string.new_project), act.getString(R.string.project_default)) { nm ->
            val p = Project.create(nm.ifEmpty { act.getString(R.string.project_default) })
            act.switchProject(p)
        }
    }

    fun renameProjectFlow() {
        val p = act.project ?: return
        prompt(act.getString(R.string.rename), p.name) { nm ->
            if (nm.isNotEmpty()) {
                p.name = nm
                act.saveProject()
                act.bindFloorToViews()
                act.refreshFilesScreen()
            }
        }
    }

    fun deleteProjectFlow() {
        val cur = act.project
        val list = act.listProjects()
        if (list.isEmpty()) return
        val names = list.map { it.name }.toTypedArray()
        AlertDialog.Builder(act)
            .setTitle(R.string.delete)
            .setItems(names) { _, which ->
                val target = list[which]
                confirm(act.getString(R.string.confirm_del)) {
                    val f = java.io.File(
                        act.filesDir, "projects/" + target.id + ".json"
                    )
                    if (f.exists()) f.delete()
                    if (cur != null && target.id == cur.id) {
                        val rest = act.listProjects()
                        act.switchProject(rest.firstOrNull() ?: Project.create(act.getString(R.string.project_default)))
                    }
                    act.refreshFilesScreen()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── AP list ──────────────────────────────────────────────
    private var apDialog: AlertDialog? = null
    private var apListCol: LinearLayout? = null

    fun apList() {
        val scroll = ScrollView(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(6), dp(14), dp(10))
        scroll.addView(col)
        apListCol = col
        apDialog = AlertDialog.Builder(act)
            .setTitle(R.string.ap_list_title)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .create()
        rebuildApList()
        apDialog?.show()
    }

    fun refreshApList() {
        if (apDialog?.isShowing == true) rebuildApList()
    }

    private fun rebuildApList() {
        val col = apListCol ?: return
        col.removeAllViews()
        val fl = act.floor
        val aps = fl?.aps
        if (aps == null || aps.isEmpty()) {
            col.addView(label(act.getString(R.string.ap_none), 12f, 0xFF64748B.toInt()))
            return
        }
        val sorted = aps.sortedByDescending { it.rssi }
        for (ap in sorted) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(4), dp(10), dp(4), dp(10))
            val col2 = LinearLayout(act)
            col2.orientation = LinearLayout.HORIZONTAL
            val c1 = LinearLayout(act)
            c1.orientation = LinearLayout.VERTICAL
            c1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
            c1.addView(label(if (ap.ssid.isNotEmpty()) ap.ssid else "Hidden", 14f, 0xFF0F172A.toInt(), true))
            c1.addView(label(ap.bssid + " — " + ap.bandLabel() + " ch" + ap.channel(), 10f, 0xFF64748B.toInt()))
            val c2 = LinearLayout(act)
            c2.orientation = LinearLayout.VERTICAL
            c2.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
            val rssiTv = label(ap.rssiBest.toString() + " dBm", 14f, GeoUtils.gradeColor(ap.grade()), true)
            c2.addView(rssiTv)
            val dist = Math.pow(10.0, (act.tx1m - ap.rssi) / (10.0 * act.pathLossN)).toFloat().coerceIn(0.5f, 150f)
            c2.addView(label(GeoUtils.fmt1(dist) + " m", 10f, 0xFF64748B.toInt()))
            col2.addView(c1)
            col2.addView(c2)
            row.addView(col2)
            row.setOnClickListener { apDetails(ap) }
            col.addView(row)
        }
    }

    fun apDetails(ap: APRecord) {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val pad = dp(18)
        col.setPadding(pad, dp(8), pad, dp(0))

        val head = LinearLayout(act)
        head.orientation = LinearLayout.VERTICAL
        head.setPadding(0, 0, 0, dp(10))
        head.addView(label(if (ap.ssid.isNotEmpty()) ap.ssid else "Hidden", 18f, 0xFF0F172A.toInt(), true))
        head.addView(label(ap.bssid, 11f, 0xFF64748B.toInt()))
        val rssiLine = label(
            ap.gradeLabel() + " — " + ap.rssiBest + " dBm",
            15f, GeoUtils.gradeColor(ap.grade()), true
        )
        rssiLine.setPadding(0, dp(4), 0, 0)
        head.addView(rssiLine)
        col.addView(head)

        val dist = Math.pow(10.0, (act.tx1m - ap.rssi) / (10.0 * act.pathLossN)).toFloat().coerceIn(0.5f, 150f)
        fun row(k: String, v: String) {
            val r = LinearLayout(act)
            r.orientation = LinearLayout.HORIZONTAL
            r.setPadding(0, dp(7), 0, dp(7))
            val k1 = label(k, 13f, 0xFF64748B.toInt())
            k1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val v1 = label(v, 13f, 0xFF0F172A.toInt(), true)
            v1.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            v1.textAlignment = TextView.TEXT_ALIGNMENT_VIEW_END
            r.addView(k1); r.addView(v1)
            col.addView(r)
        }
        row("باند / کانال", ap.bandLabel() + " — ch" + ap.channel())
        row("امنیت", ap.security.ifEmpty { "—" })
        row(act.getString(R.string.ap_dist), GeoUtils.fmt1(dist) + " m")
        row("سیگنال هموارشده", GeoUtils.fmt1(ap.rssi) + " dBm")
        row(act.getString(R.string.ap_conf), (ap.confidence * 100).toInt().toString() + "%")
        row(act.getString(R.string.ap_samples), ap.samples.size.toString() + " نمونه")
        row(act.getString(R.string.ap_last), SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ap.lastSeen)))
        row("موقعیت تخمینی", GeoUtils.fmt1(ap.x) + " , " + GeoUtils.fmt1(ap.y) + " m")

        val actions = LinearLayout(act)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(0, dp(12), 0, dp(0))
        actions.addView(btn(act.getString(R.string.ap_move), false) {
            act.pendingMoveAp = ap
            apDialog?.dismiss()
            act.toast("روی نقشه لمس کنید")
            act.setTab(1)
        })
        actions.addView(btn(act.getString(R.string.ap_delete), true) {
            act.floor?.aps?.remove(ap)
            act.saveProject()
            act.invalidateViews()
            apDialog?.dismiss()
        })
        col.addView(actions)

        AlertDialog.Builder(act)
            .setTitle(R.string.ap_title)
            .setView(col)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    fun pickApForPlacement(p: Pt) {
        val list = act.wifiEngine.lastResults
        if (list.isEmpty()) {
            act.toast(act.getString(R.string.ap_none))
            return
        }
        val names = list.take(15).map {
            (if (it.ssid.isNotEmpty()) it.ssid else "Hidden") + "  " + it.bssid + "  " + it.rssi + "dBm"
        }
        AlertDialog.Builder(act)
            .setTitle(act.getString(R.string.mode_ap))
            .setItems(names.toTypedArray()) { _, which ->
                val r = list[which]
                val fl = act.floor ?: return@setItems
                val ex = fl.aps.firstOrNull { it.bssid.equals(r.bssid, ignoreCase = true) }
                if (ex != null) {
                    act.moveAp(ex, p)
                } else {
                    val ap = APRecord(
                        r.bssid, r.ssid.ifEmpty { "Hidden-" + r.bssid.takeLast(5).replace(":", "") },
                        r.freq, act.securityOf(r.caps), p.x, p.y, 1f,
                        r.rssi.toFloat(), r.rssi, ArrayList(),
                        System.currentTimeMillis(), System.currentTimeMillis(), true
                    )
                    fl.aps.add(ap)
                    act.saveProject()
                    act.invalidateViews()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── exports ──────────────────────────────────────────────
    fun export() {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        col.setPadding(pad, dp(8), pad, dp(0))
        fun item(t: String, kind: String) {
            val b = AppCompatButton(act)
            b.text = t
            b.isAllCaps = false
            b.textSize = 13f
            b.setBackgroundResource(R.drawable.bg_btn)
            b.setTextColor(0xFF0F172A.toInt())
            b.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
            ).apply { bottomMargin = dp(8) }
            b.setOnClickListener {
                act.runExport(kind)
            }
            col.addView(b)
        }
        item(act.getString(R.string.exp_top), "top")
        item(act.getString(R.string.exp_persp), "persp")
        item(act.getString(R.string.exp_cam), "cam")
        item(act.getString(R.string.exp_obj), "obj")
        item(act.getString(R.string.exp_csv), "csv")
        item(act.getString(R.string.exp_json), "json")
        val note = label(act.getString(R.string.export_note), 11f, 0xFF64748B.toInt())
        note.setPadding(0, dp(4), 0, dp(8))
        col.addView(note)
        AlertDialog.Builder(act)
            .setTitle(R.string.export_title)
            .setView(col)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    fun help() {
        val tv = TextView(act)
        tv.text = act.getString(R.string.help_text)
        tv.textSize = 13f
        tv.setTextColor(0xFF0F172A.toInt())
        tv.setPadding(dp(18), dp(10), dp(18), dp(8))
        tv.lineSpacingMultiplier = 1.3f
        val sc = ScrollView(act)
        sc.addView(tv)
        AlertDialog.Builder(act)
            .setTitle(R.string.help_title)
            .setView(sc)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}

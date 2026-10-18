package com.scanmap.org

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 2D point in meters — x: East, y: North (world frame). */
data class Pt(val x: Float, val y: Float)

/** One wall segment with metric thickness/height. */
class WallSeg(var a: Pt, var b: Pt, var thickness: Float, var height: Float) {
    constructor(a: Pt, b: Pt) : this(a, b, 0.15f, 2.8f)
}

/** Closed room polygon — area computed with the shoelace formula. */
class RoomPoly(var name: String, val pts: MutableList<Pt>) {
    fun area(): Float = GeoUtils.polygonArea(pts)
}

/** One RSSI sample taken while walking (used for AP position estimation). */
class RssiSample(val t: Long, val d: Float, val rssi: Int, val x: Float, val y: Float)

/** A discovered Wi-Fi access point. */
class APRecord(
    val bssid: String,
    var ssid: String,
    var freq: Int,
    var security: String,
    var x: Float,
    var y: Float,
    var confidence: Float,
    var rssi: Float,          // smoothed current RSSI
    var rssiBest: Int,
    val samples: ArrayList<RssiSample>,
    var firstSeen: Long,
    var lastSeen: Long,
    var manual: Boolean
) {
    fun grade(): Int = when {
        rssi >= -55f -> 0   // excellent
        rssi >= -67f -> 1   // good
        rssi >= -75f -> 2   // fair
        else -> 3           // weak
    }

    fun gradeLabel(): String = when (grade()) {
        0 -> "عالی"; 1 -> "خوب"; 2 -> "متوسط"; else -> "ضعیف"
    }

    fun bandLabel(): String = when {
        freq >= 5925 -> "6 GHz"
        freq >= 4900 -> "5 GHz"
        else -> "2.4 GHz"
    }

    fun channel(): Int = when {
        freq in 5915..7125 -> (freq - 5950) / 5
        freq >= 4900 -> (freq - 5000) / 5
        freq in 2407..2494 -> (freq - 2407) / 5
        else -> 0
    }
}

/** One building floor — walls, rooms, APs and the walked path. */
class FloorData(val id: String, var name: String, var level: Int, var createdAt: Long) {
    val walls: ArrayList<WallSeg> = ArrayList()
    val rooms: ArrayList<RoomPoly> = ArrayList()
    val aps: ArrayList<APRecord> = ArrayList()
    val path: ArrayList<Pt> = ArrayList()

    companion object {
        fun create(name: String, level: Int): FloorData =
            FloorData(UUID.randomUUID().toString(), name, level, System.currentTimeMillis())
    }
}

/** A survey project containing multiple floors. */
class Project(var id: String, var name: String, var createdAt: Long) {
    val floors: ArrayList<FloorData> = ArrayList()
    var activeFloorId: String = ""

    fun activeFloor(): FloorData? =
        floors.firstOrNull { it.id == activeFloorId } ?: floors.firstOrNull()

    companion object {
        fun create(name: String): Project {
            val p = Project(UUID.randomUUID().toString(), name, System.currentTimeMillis())
            p.floors.add(FloorData.create("همکف", 0))
            p.activeFloorId = p.floors[0].id
            return p
        }
    }
}

/** JSON (de)serialization for project persistence and export. */
object ModelJson {

    fun projectToJson(p: Project): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("name", p.name)
        o.put("createdAt", p.createdAt)
        o.put("activeFloorId", p.activeFloorId)
        o.put("app", "scanmap-android")
        o.put("version", 1)
        val fs = JSONArray()
        for (f in p.floors) {
            val fo = JSONObject()
            fo.put("id", f.id)
            fo.put("name", f.name)
            fo.put("level", f.level)
            fo.put("createdAt", f.createdAt)
            val ws = JSONArray()
            for (w in f.walls) {
                val wo = JSONObject()
                wo.put("x1", w.a.x.toDouble()); wo.put("y1", w.a.y.toDouble())
                wo.put("x2", w.b.x.toDouble()); wo.put("y2", w.b.y.toDouble())
                wo.put("t", w.thickness.toDouble()); wo.put("h", w.height.toDouble())
                ws.put(wo)
            }
            fo.put("walls", ws)
            val rs = JSONArray()
            for (r in f.rooms) {
                val ro = JSONObject()
                ro.put("name", r.name)
                val ps = JSONArray()
                for (pt in r.pts) { ps.put(pt.x.toDouble()); ps.put(pt.y.toDouble()) }
                ro.put("pts", ps)
                rs.put(ro)
            }
            fo.put("rooms", rs)
            val ajs = JSONArray()
            for (ap in f.aps) ajs.put(apToJson(ap))
            fo.put("aps", ajs)
            val pp = JSONArray()
            for (pt in f.path) { pp.put(pt.x.toDouble()); pp.put(pt.y.toDouble()) }
            fo.put("path", pp)
            fs.put(fo)
        }
        o.put("floors", fs)
        return o
    }

    fun apToJson(ap: APRecord): JSONObject {
        val ao = JSONObject()
        ao.put("bssid", ap.bssid)
        ao.put("ssid", ap.ssid)
        ao.put("freq", ap.freq)
        ao.put("sec", ap.security)
        ao.put("x", ap.x.toDouble()); ao.put("y", ap.y.toDouble())
        ao.put("conf", ap.confidence.toDouble())
        ao.put("rssi", ap.rssi.toDouble())
        ao.put("rssiBest", ap.rssiBest)
        ao.put("firstSeen", ap.firstSeen)
        ao.put("lastSeen", ap.lastSeen)
        ao.put("manual", ap.manual)
        val ss = JSONArray()
        val n = ap.samples.size
        for (i in (n - 40).coerceAtLeast(0) until n) {
            val s = ap.samples[i]
            ss.put(s.rssi); ss.put(s.x.toDouble()); ss.put(s.y.toDouble())
        }
        ao.put("samples", ss)
        return ao
    }

    fun apFromJson(ao: JSONObject): APRecord {
        val samples = ArrayList<RssiSample>()
        val ss = ao.optJSONArray("samples")
        if (ss != null) {
            var i = 0
            while (i + 2 < ss.length()) {
                samples.add(RssiSample(0L, 0f, ss.optInt(i), ss.optDouble(i + 1, 0.0).toFloat(), ss.optDouble(i + 2, 0.0).toFloat()))
                i += 3
            }
        }
        return APRecord(
            ao.optString("bssid", "00:00:00:00:00:00"),
            ao.optString("ssid", ""),
            ao.optInt("freq", 2437),
            ao.optString("sec", ""),
            ao.optDouble("x", 0.0).toFloat(),
            ao.optDouble("y", 0.0).toFloat(),
            ao.optDouble("conf", 0.0).toFloat(),
            ao.optDouble("rssi", -100.0).toFloat(),
            ao.optInt("rssiBest", -100),
            samples,
            ao.optLong("firstSeen", 0L),
            ao.optLong("lastSeen", 0L),
            ao.optBoolean("manual", false)
        )
    }

    fun projectFromJson(o: JSONObject): Project {
        val p = Project(
            o.optString("id", UUID.randomUUID().toString()),
            o.optString("name", "پروژه"),
            o.optLong("createdAt", System.currentTimeMillis())
        )
        val fs = o.optJSONArray("floors") ?: JSONArray()
        for (i in 0 until fs.length()) {
            val fo = fs.getJSONObject(i)
            val f = FloorData(
                fo.optString("id", UUID.randomUUID().toString()),
                fo.optString("name", "طبقه"),
                fo.optInt("level", 0),
                fo.optLong("createdAt", System.currentTimeMillis())
            )
            val ws = fo.optJSONArray("walls") ?: JSONArray()
            for (j in 0 until ws.length()) {
                val wo = ws.getJSONObject(j)
                f.walls.add(
                    WallSeg(
                        Pt(wo.optDouble("x1", 0.0).toFloat(), wo.optDouble("y1", 0.0).toFloat()),
                        Pt(wo.optDouble("x2", 0.0).toFloat(), wo.optDouble("y2", 0.0).toFloat()),
                        wo.optDouble("t", 0.15).toFloat(),
                        wo.optDouble("h", 2.8).toFloat()
                    )
                )
            }
            val rs = fo.optJSONArray("rooms") ?: JSONArray()
            for (j in 0 until rs.length()) {
                val ro = rs.getJSONObject(j)
                val pts = ArrayList<Pt>()
                val ps = ro.optJSONArray("pts") ?: JSONArray()
                var k = 0
                while (k + 1 < ps.length()) {
                    pts.add(Pt(ps.optDouble(k, 0.0).toFloat(), ps.optDouble(k + 1, 0.0).toFloat()))
                    k += 2
                }
                if (pts.size >= 3) f.rooms.add(RoomPoly(ro.optString("name", "اتاق"), pts))
            }
            val ajs = fo.optJSONArray("aps") ?: JSONArray()
            for (j in 0 until ajs.length()) f.aps.add(apFromJson(ajs.getJSONObject(j)))
            val pp = fo.optJSONArray("path") ?: JSONArray()
            var k = 0
            while (k + 1 < pp.length()) {
                f.path.add(Pt(pp.optDouble(k, 0.0).toFloat(), pp.optDouble(k + 1, 0.0).toFloat()))
                k += 2
            }
            p.floors.add(f)
        }
        p.activeFloorId = o.optString("activeFloorId", "")
        if (p.activeFloor() == null && p.floors.isNotEmpty()) p.activeFloorId = p.floors[0].id
        return p
    }
}

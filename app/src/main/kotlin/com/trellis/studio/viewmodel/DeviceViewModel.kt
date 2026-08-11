package com.trellis.studio.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trellis.studio.util.GameBooster
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Live device vitals for the "Device" panel — techy aura, all real readings. */
class DeviceViewModel(app: Application) : AndroidViewModel(app) {

    data class Vitals(
        val ramUsedPct: Int = 0,
        val ramAvailMb: Long = 0,
        val ramTotalMb: Long = 0,
        val batteryPct: Int = 0,
        val charging: Boolean = false,
        val tempC: Float = 0f,
        val storageUsedPct: Int = 0,
        val storageFreeGb: Float = 0f,
        val storageTotalGb: Float = 0f,
        val cpuCores: Int = Runtime.getRuntime().availableProcessors(),
        val model: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        val android: String = "Android ${Build.VERSION.RELEASE}",
    )

    private val _v = MutableStateFlow(Vitals())
    val vitals: StateFlow<Vitals> = _v.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) { _v.update { read() }; delay(1500) }
        }
    }

    private fun read(): Vitals {
        val ctx = getApplication<Application>()
        val mem = GameBooster.memory(ctx)

        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalB = stat.blockCountLong * stat.blockSizeLong
        val freeB = stat.availableBlocksLong * stat.blockSizeLong
        val totalGb = totalB / 1_000_000_000f
        val freeGb = freeB / 1_000_000_000f
        val usedPct = if (totalB > 0) (((totalB - freeB) * 100) / totalB).toInt() else 0

        return Vitals(
            ramUsedPct = mem.usedPercent, ramAvailMb = mem.availMb, ramTotalMb = mem.totalMb,
            batteryPct = pct, charging = charging, tempC = temp,
            storageUsedPct = usedPct, storageFreeGb = freeGb, storageTotalGb = totalGb,
        )
    }
}

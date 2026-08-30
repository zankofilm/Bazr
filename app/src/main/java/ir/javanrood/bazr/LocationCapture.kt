package ir.javanrood.bazr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

object LocationCapture {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun snapshot(context: Context): JSONObject? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        var best: Location? = null
        providers.forEach { p ->
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: return@forEach
            if (best == null || loc.time > (best?.time ?: 0L) || loc.accuracy < (best?.accuracy ?: Float.MAX_VALUE)) best = loc
        }
        val x = best ?: return null
        return JSONObject()
            .put("lat", x.latitude)
            .put("lng", x.longitude)
            .put("accuracy_m", x.accuracy)
            .put("captured_at", java.time.Instant.ofEpochMilli(x.time).toString())
            .put("provider", x.provider ?: "")
    }
}

package de.nulide.findmydevice.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import androidx.collection.ArrayMap
import java.net.NetworkInterface
import java.net.SocketException

object NetworkUtils {

    fun getWifiNetworks(context: Context): MutableList<ScanResult> {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        wifiManager.setWifiEnabled(true)
        wifiManager.startScan()

        @SuppressLint("MissingPermission") val results = wifiManager.scanResults
        return results
    }

    fun getAllIP(): MutableMap<String, String> {
        val ip = ArrayMap<String, String>()
        try {
            val en = NetworkInterface.getNetworkInterfaces()

            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.getInetAddresses()

                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress) {
                        ip.put(intf.displayName, inetAddress.hostAddress)
                    }
                }
            }
        } catch (e: SocketException) {
            e.printStackTrace()
        }
        return ip
    }

    @JvmStatic
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        val activeNetwork = connectivityManager.activeNetwork ?: return false

        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

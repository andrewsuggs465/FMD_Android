package de.nulide.findmydevice.utils

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.TelephonyManager
import android.telephony.gsm.GsmCellLocation


data class CellParameters(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val radio: String
) {
    fun prettyPrint(): String {
        return """
            CellParameters:
            mcc: $mcc
            mnc: $mnc
            lac: $lac
            cid: $cid
            radio: $radio
        """.trimIndent()
    }

    companion object {
        private val TAG = CellParameters::class.simpleName

        fun queryCellParametersFromTelephonyManager(context: Context): List<CellParameters> {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            // TODO: Migrate to CellInfo (GsmCellLocation is deprecated)
            @SuppressLint("MissingPermission")  // ACCESS_FINE_LOCATION
            val location = tm.cellLocation as? GsmCellLocation
            val operator = tm.networkOperator
            if (location == null || operator.length <= 3) {
                return emptyList()
            }

            val mcc = operator.substring(0, 3).toInt()
            val mnc = operator.substring(3).toInt()

            return listOf(CellParameters(mcc, mnc, location.lac, location.cid, "GSM"))
        }
    }
}

fun List<CellParameters>.prettyPrint(): String {
    return this.joinToString("\n\n") { it.prettyPrint() }
}

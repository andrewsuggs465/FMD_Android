package de.nulide.findmydevice.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import de.nulide.findmydevice.R
import de.nulide.findmydevice.securepouch.DiscoveredDevice
import de.nulide.findmydevice.securepouch.DiscoveredDeviceState

class DiscoveredDeviceAdapter(
    private val onDeviceTapped: (DiscoveredDevice) -> Unit,
) : RecyclerView.Adapter<DiscoveredDeviceAdapter.ViewHolder>() {

    private var items: List<DiscoveredDevice> = emptyList()

    fun submitList(newItems: List<DiscoveredDevice>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_discovered_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvDeviceName)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvDeviceAddress)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvDeviceStatus)
        private val tvRssi: TextView = itemView.findViewById(R.id.tvRssi)
        private val progress: CircularProgressIndicator = itemView.findViewById(R.id.progressDevice)

        fun bind(device: DiscoveredDevice) {
            val ctx = itemView.context

            tvName.text = device.deviceId ?: device.displayName
            tvAddress.text = formatAddress(device.address)
            tvRssi.text = "${device.rssi} dBm"

            when (device.state) {
                DiscoveredDeviceState.FOUND -> {
                    tvStatus.text = ctx.getString(R.string.sp_scan_tap_to_pair)
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.sp_status_ready))
                    progress.visibility = View.GONE
                    tvRssi.visibility = View.VISIBLE
                    itemView.isEnabled = true
                    itemView.alpha = 1f
                    itemView.setOnClickListener { onDeviceTapped(device) }
                }
                DiscoveredDeviceState.CONNECTING -> {
                    tvStatus.text = ctx.getString(R.string.sp_scan_reading_id)
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.sp_status_connecting))
                    progress.visibility = View.VISIBLE
                    tvRssi.visibility = View.GONE
                    itemView.isEnabled = false
                    itemView.alpha = 1f
                    itemView.setOnClickListener(null)
                }
                DiscoveredDeviceState.READY -> {
                    tvStatus.text = ctx.getString(R.string.sp_scan_tap_to_register)
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.sp_status_ready))
                    progress.visibility = View.GONE
                    tvRssi.visibility = View.VISIBLE
                    itemView.isEnabled = true
                    itemView.alpha = 1f
                    itemView.setOnClickListener { onDeviceTapped(device) }
                }
                DiscoveredDeviceState.ALREADY_PAIRED -> {
                    tvStatus.text = ctx.getString(R.string.sp_scan_already_added)
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.sp_status_neutral))
                    progress.visibility = View.GONE
                    tvRssi.visibility = View.GONE
                    itemView.isEnabled = false
                    itemView.alpha = 0.6f
                    itemView.setOnClickListener(null)
                }
                DiscoveredDeviceState.PAIRING -> {
                    tvStatus.text = ctx.getString(R.string.sp_scan_registering)
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.sp_status_connecting))
                    progress.visibility = View.VISIBLE
                    tvRssi.visibility = View.GONE
                    itemView.isEnabled = false
                    itemView.alpha = 1f
                    itemView.setOnClickListener(null)
                }
                DiscoveredDeviceState.PAIRED -> {
                    tvStatus.text = ctx.getString(R.string.sp_scan_added)
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.sp_status_success))
                    progress.visibility = View.GONE
                    tvRssi.visibility = View.GONE
                    itemView.isEnabled = false
                    itemView.alpha = 1f
                    itemView.setOnClickListener(null)
                }
                DiscoveredDeviceState.ERROR -> {
                    tvStatus.text = ctx.getString(R.string.sp_scan_error_tap_retry)
                    tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.sp_status_error))
                    progress.visibility = View.GONE
                    tvRssi.visibility = View.VISIBLE
                    itemView.isEnabled = true
                    itemView.alpha = 1f
                    itemView.setOnClickListener {
                        onDeviceTapped(device.copy(state = DiscoveredDeviceState.FOUND))
                    }
                }
            }
        }

        private fun formatAddress(address: String): String {
            // Show last 4 hex chars for brevity: "AA:BB:CC:DD:EE:FF" → "…EE:FF"
            return "…${address.takeLast(5)}"
        }
    }
}

package com.bordy.app.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

object AppIcons {
    val all = mapOf(
        "MCB" to Icons.Default.ElectricalServices,
        "MCCB" to Icons.Default.Power,
        "Contactor" to Icons.Default.DeveloperBoard,
        "Relay" to Icons.Default.Memory,
        "Fuse" to Icons.Default.FlashOn,
        "Selector" to Icons.Default.Tune,
        "Switch" to Icons.Default.ToggleOn,
        "Cable" to Icons.Default.Cable,
        "Wire" to Icons.Default.LinearScale,
        "Socket" to Icons.Default.Outlet,
        "Bulb" to Icons.Default.Lightbulb,
        "Solar" to Icons.Default.SolarPower,
        "Battery" to Icons.Default.BatteryChargingFull,
        "Clover" to Icons.Default.Eco,
        "Shield" to Icons.Default.Security,
        "Lock" to Icons.Default.Lock,
        "Star" to Icons.Default.Star,
        "Warning" to Icons.Default.Warning,
        "Tools" to Icons.Default.Build,
        "Settings" to Icons.Default.Settings
    )
    fun get(name: String): ImageVector = all[name] ?: Icons.Default.Memory
}
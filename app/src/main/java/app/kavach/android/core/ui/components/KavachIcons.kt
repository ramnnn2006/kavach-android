package app.kavach.android.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Elevator
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.ui.graphics.vector.ImageVector
import app.kavach.android.core.data.model.AssetKind
import app.kavach.android.core.data.model.IncidentType

/** Icon per incident type (society.js INCIDENT_TYPES). */
fun IncidentType.icon(): ImageVector = when (this) {
    IncidentType.Lift -> Icons.Rounded.Elevator
    IncidentType.Fire -> Icons.Rounded.LocalFireDepartment
    IncidentType.Medical -> Icons.Rounded.MonitorHeart
    IncidentType.Water -> Icons.Rounded.WaterDrop
    IncidentType.Power -> Icons.Rounded.Bolt
    IncidentType.Security -> Icons.Rounded.Shield
}

/** Icon per asset kind (admin/parts/assetKinds.js). */
fun AssetKind.icon(): ImageVector = when (this) {
    AssetKind.Lift -> Icons.Rounded.Elevator
    AssetKind.Dg -> Icons.Rounded.LocalGasStation
    AssetKind.Transformer -> Icons.Rounded.ElectricalServices
    AssetKind.WaterPump -> Icons.Rounded.WaterDrop
    AssetKind.Sump -> Icons.Rounded.Waves
    AssetKind.FirePump -> Icons.Rounded.LocalFireDepartment
}

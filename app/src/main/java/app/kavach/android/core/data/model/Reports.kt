package app.kavach.android.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ReportSummaryRow(
    val type: IncidentType,
    val total: Long = 0,
    val resolved: Long = 0,
    val cancelled: Long = 0,
    val escalated: Long = 0,
    @SerialName("avg_claim_minutes") val avgClaimMinutes: Double? = null,
    @SerialName("avg_resolve_minutes") val avgResolveMinutes: Double? = null,
)

@Serializable
data class ReportByAssetRow(
    @SerialName("asset_id") val assetId: String,
    @SerialName("asset_name") val assetName: String,
    @SerialName("zone_name") val zoneName: String? = null,
    val kind: AssetKind,
    val incidents: Long = 0,
    @SerialName("avg_resolve_minutes") val avgResolveMinutes: Double? = null,
    @SerialName("last_incident_at") val lastIncidentAt: IsoInstant? = null,
)

@Serializable
data class ReportByZoneRow(
    @SerialName("zone_id") val zoneId: String,
    @SerialName("zone_name") val zoneName: String,
    val incidents: Long = 0,
    @SerialName("avg_resolve_minutes") val avgResolveMinutes: Double? = null,
)

@Serializable
data class ReportMonthlyRow(
    val month: IsoDate,
    val type: IncidentType,
    val incidents: Long = 0,
)

/** Everything the admin Reports (Insights) screen shows for one period (admin.js listenReports). */
data class ReportBundle(
    val summary: List<ReportSummaryRow>,
    val byAsset: List<ReportByAssetRow>,
    val byZone: List<ReportByZoneRow>,
    val monthly: List<ReportMonthlyRow>,
    val from: Instant,
    val to: Instant,
)

package app.kavach.android.core.data.repo

import app.kavach.android.core.data.model.ReportBundle
import app.kavach.android.core.data.model.ReportByAssetRow
import app.kavach.android.core.data.model.ReportByZoneRow
import app.kavach.android.core.data.model.ReportMonthlyRow
import app.kavach.android.core.data.model.ReportSummaryRow
import app.kavach.android.core.data.remote.Db
import app.kavach.android.core.data.remote.LiveQueries
import app.kavach.android.core.data.remote.Loadable
import app.kavach.android.core.data.remote.table
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Admin reliability reports. */
@Singleton
class ReportRepository @Inject constructor(
    private val db: Db,
    private val live: LiveQueries,
) {
    private fun range(from: Instant, to: Instant) = buildJsonObject {
        put("p_from", from.toString())
        put("p_to", to.toString())
    }

    suspend fun summary(from: Instant, to: Instant): List<ReportSummaryRow> =
        db.rpcAs("report_summary", range(from, to))

    suspend fun byAsset(from: Instant, to: Instant): List<ReportByAssetRow> =
        db.rpcAs("report_by_asset", range(from, to))

    suspend fun byZone(from: Instant, to: Instant): List<ReportByZoneRow> =
        db.rpcAs("report_by_zone", range(from, to))

    suspend fun monthly(months: Int = 6): List<ReportMonthlyRow> =
        db.rpcAs("report_monthly", buildJsonObject { put("p_months", months) })

    /** Everything for the last [days] days (+ [months]-month trend); refetches when incidents change. */
    fun listenReports(days: Int, months: Int = 6): Flow<Loadable<ReportBundle>> =
        live.live("reports:$days:$months", table("incidents")) {
            val to = Instant.now()
            val from = to.minus(Duration.ofDays(days.toLong()))
            coroutineScope {
                val s = async { summary(from, to) }
                val a = async { byAsset(from, to) }
                val z = async { byZone(from, to) }
                val m = async { monthly(months) }
                ReportBundle(s.await(), a.await(), z.await(), m.await(), from, to)
            }
        }
}

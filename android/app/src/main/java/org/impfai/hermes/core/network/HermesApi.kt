package org.impfai.hermes.core.network

import kotlinx.serialization.json.JsonObject
import org.impfai.hermes.core.model.AgentData
import org.impfai.hermes.core.model.AgentRequest
import org.impfai.hermes.core.model.ChannelsData
import org.impfai.hermes.core.model.ClauseDetail
import org.impfai.hermes.core.model.DomainsData
import org.impfai.hermes.core.model.Envelope
import org.impfai.hermes.core.model.FormulasData
import org.impfai.hermes.core.model.HealthData
import org.impfai.hermes.core.model.ManifestData
import org.impfai.hermes.core.model.MatchData
import org.impfai.hermes.core.model.MatchRequest
import org.impfai.hermes.core.model.Readyz
import org.impfai.hermes.core.model.SearchData
import org.impfai.hermes.core.model.SearchRequest
import org.impfai.hermes.core.model.TeachRequest
import org.impfai.hermes.core.model.WhoAmI
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Hermes 後端 /api/v1 合同（樣本驅動，見 backend/tests/test_v1_api.py）。 */
interface HermesApi {

    @GET("readyz")
    suspend fun readyz(): Response<Readyz>

    @GET("api/v1/health")
    suspend fun health(): Response<Envelope<HealthData>>

    @GET("api/v1/whoami")
    suspend fun whoami(@Query("role") role: String? = null): Response<Envelope<WhoAmI>>

    @POST("api/v1/search")
    suspend fun search(@Body req: SearchRequest): Response<Envelope<SearchData>>

    @GET("api/v1/clause/{ref}")
    suspend fun clause(
        @Path("ref") ref: String,
        @Query("role") role: String? = null,
    ): Response<Envelope<ClauseDetail>>

    @POST("api/v1/match")
    suspend fun match(@Body req: MatchRequest): Response<Envelope<MatchData>>

    @POST("api/v1/agent")
    suspend fun agent(@Body req: AgentRequest): Response<Envelope<AgentData>>

    @GET("api/v1/formulas")
    suspend fun formulas(): Response<Envelope<FormulasData>>

    @GET("api/v1/channels")
    suspend fun channels(): Response<Envelope<ChannelsData>>

    @POST("api/v1/teach")
    suspend fun teach(@Body req: TeachRequest): Response<Envelope<JsonObject>>

    @GET("api/v1/domains")
    suspend fun domains(): Response<Envelope<DomainsData>>

    @GET("api/v1/content/manifest")
    suspend fun contentManifest(): Response<Envelope<ManifestData>>
}

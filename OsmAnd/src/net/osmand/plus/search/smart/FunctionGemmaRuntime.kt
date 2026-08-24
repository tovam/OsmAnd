package net.osmand.plus.search.smart

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object FunctionGemmaRuntime {

    enum class ErrorCode {
        DISABLED,
        MODEL_NOT_INSTALLED,
        GPU_UNAVAILABLE,
        GPU_OUTPUT_CORRUPTED,
        TIMEOUT,
        NO_TOOL_CALL,
        INVALID_MODEL_OUTPUT,
        INFERENCE_ERROR,
    }

    interface Callback {
        fun onResult(request: SmartSearchRequest)
        fun onClarification(message: String)
        fun onError(code: ErrorCode, message: String)
    }

    private const val INFERENCE_TIMEOUT_SECONDS = 12L
    private const val ENGINE_IDLE_SECONDS = 120L
    private const val MAX_NUM_TOKENS = 1024
    private val executor = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: Engine? = null
    private var engineModelStamp: String? = null
    private var idleReleaseTask: ScheduledFuture<*>? = null

    private const val DEVELOPER_PROMPT = """Tu convertis une demande de recherche géographique en un appel d'outil pour OsmAnd.
Utilise search_poi pour une catégorie de POI ou un établissement. Renseigne exactement un seul des champs name et category :
- name pour une enseigne ou un nom propre, recopié caractère pour caractère depuis la demande, sans le traduire ni le remplacer ;
- category pour un besoin générique, avec l'identifiant sémantique stable appris dans les exemples. N'invente pas d'identifiant.
Utilise search_location, sans search_poi, pour une adresse, des coordonnées, un code Plus ou un lieu unique à ouvrir directement sur la carte.
Choisis uniquement le contexte exprimé par l'utilisateur : CURRENT_LOCATION pour près de lui, MAP_CENTER pour la zone visible, DESTINATION pour sa destination, ROUTE pour son trajet, et NAMED_PLACE pour une ville ou un lieu explicitement cité. Une recherche de POI sans indication géographique donne UNSPECIFIED afin que l'application demande où chercher. Avec NAMED_PLACE, recopie le lieu dans place.
result_mode vaut ALL pour une recherche normale ou tous les résultats du trajet, NEAREST uniquement si l'utilisateur demande le plus proche, NEXT pour le prochain résultat devant lui sur sa route, et LAST pour le dernier résultat avant la destination.
availability vaut ANY sans demande d'ouverture, OPEN_NOW pour ouvert maintenant, OPEN_24_7 pour ouvert 24h/24, et OPEN_AT_ARRIVAL uniquement si l'utilisateur demande que le lieu soit ouvert lorsqu'il y arrivera ou y passera.
Appelle exactement un outil si l'objet recherché est identifiable. Sinon, ne produis aucun appel d'outil et demande la précision utile ou indique que l'action n'est pas une recherche.
Après un appel d'outil accepté, réponds seulement OK."""

    @JvmStatic
    fun parse(context: Context, userText: String, callback: Callback) {
        val text = userText.trim()
        val directLocation = SmartSearchPreprocessor.directLocationQuery(text)
        if (directLocation != null) {
            mainHandler.post { callback.onResult(SmartSearchRequest.location(directLocation)) }
            return
        }

        val appContext = context.applicationContext
        val manager = FunctionGemmaModelManager.get(appContext)
        if (!manager.isEnabled) {
            mainHandler.post { callback.onError(ErrorCode.DISABLED, "La recherche intelligente est désactivée") }
            return
        }
        if (!manager.isInstalled) {
            mainHandler.post {
                callback.onError(ErrorCode.MODEL_NOT_INSTALLED, "Le modèle FunctionGemma n’est pas installé")
            }
            return
        }
        executor.execute { parseOnGpu(appContext, manager.modelFile, text, callback) }
    }

    @OptIn(ExperimentalApi::class)
    private fun parseOnGpu(context: Context, modelFile: File, userText: String, callback: Callback) {
        var conversation: Conversation? = null
        var timeoutTask: ScheduledFuture<*>? = null
        val timedOut = AtomicBoolean(false)
        try {
            val activeEngine = getOrCreateGpuEngine(context, modelFile)
            val tools = OsmandSearchTools()
            ExperimentalFlags.enableConversationConstrainedDecoding = true
            val config = ConversationConfig(
                systemMessage = Message.of(DEVELOPER_PROMPT),
                tools = listOf(tools),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
            )
            conversation = activeEngine.createConversation(config)
            val runningConversation = conversation
            timeoutTask = watchdog.schedule({
                timedOut.set(true)
                runCatching { runningConversation.cancelProcess() }
            }, INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val response = conversation.sendMessage(Message.of(userText)).toString()
            val request = when (val call = tools.captured.get()) {
                is CapturedCall.Location -> SmartSearchGuard.guardLocation(userText, call.query)
                is CapturedCall.Poi -> SmartSearchGuard.guardPoi(
                    userText,
                    call.name,
                    call.category,
                    call.context,
                    call.place,
                    call.resultMode,
                    call.availability,
                    SmartSearchCategoryRegistry.get(context),
                )
                null -> null
            }
            when {
                timedOut.get() -> postError(callback, ErrorCode.TIMEOUT, "Le GPU n’a pas répondu à temps")
                request != null -> mainHandler.post { callback.onResult(request) }
                response.contains("<pad>", ignoreCase = true) -> postError(
                    callback,
                    ErrorCode.GPU_OUTPUT_CORRUPTED,
                    "Le backend GPU a produit une sortie Gemma 270M corrompue",
                )
                response.isNotBlank() -> mainHandler.post { callback.onClarification(response) }
                else -> postError(callback, ErrorCode.NO_TOOL_CALL, "FunctionGemma n’a produit aucun appel de recherche")
            }
        } catch (error: Throwable) {
            val code = when {
                timedOut.get() -> ErrorCode.TIMEOUT
                error is IllegalArgumentException -> ErrorCode.INVALID_MODEL_OUTPUT
                error.message?.contains("GPU", ignoreCase = true) == true -> ErrorCode.GPU_UNAVAILABLE
                else -> ErrorCode.INFERENCE_ERROR
            }
            postError(callback, code, error.message ?: error.javaClass.simpleName)
        } finally {
            timeoutTask?.cancel(false)
            runCatching { conversation?.close() }
            scheduleIdleRelease()
        }
    }

    @Synchronized
    private fun getOrCreateGpuEngine(context: Context, modelFile: File): Engine {
        idleReleaseTask?.cancel(false)
        idleReleaseTask = null
        val stamp = "${modelFile.absolutePath}:${modelFile.length()}:${modelFile.lastModified()}"
        val current = engine
        if (current != null && engineModelStamp == stamp && current.isInitialized()) {
            return current
        }
        if (current != null && current.isInitialized()) {
            runCatching { current.close() }
        }
        val cacheDirectory = File(context.cacheDir, "functiongemma").apply { mkdirs() }
        val next = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU,
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = cacheDirectory.absolutePath,
            )
        )
        next.initialize()
        engine = next
        engineModelStamp = stamp
        return next
    }

    @Synchronized
    private fun scheduleIdleRelease() {
        idleReleaseTask?.cancel(false)
        idleReleaseTask = watchdog.schedule({
            executor.execute { closeEngine() }
        }, ENGINE_IDLE_SECONDS, TimeUnit.SECONDS)
    }

    @JvmStatic
    fun release() {
        executor.execute { closeEngine() }
    }

    @Synchronized
    private fun closeEngine() {
        idleReleaseTask?.cancel(false)
        idleReleaseTask = null
        val current = engine
        engine = null
        engineModelStamp = null
        runCatching { current?.close() }
    }

    private fun postError(callback: Callback, code: ErrorCode, message: String) {
        mainHandler.post { callback.onError(code, message) }
    }

    private sealed interface CapturedCall {
        data class Poi(
            val name: String?,
            val category: String?,
            val context: String,
            val place: String?,
            val resultMode: String,
            val availability: String,
        ) : CapturedCall

        data class Location(val query: String) : CapturedCall
    }

    private class OsmandSearchTools {
        val captured = AtomicReference<CapturedCall?>()

        @Tool(description = "Search OsmAnd POIs by one normalized app category or one verbatim establishment name.")
        fun searchPoi(
            @ToolParam(description = "Verbatim establishment, chain, or POI name copied from the request; mutually exclusive with category.")
            name: String? = null,
            @ToolParam(description = "Stable semantic OsmAnd category key learned from examples; mutually exclusive with name.")
            category: String? = null,
            @ToolParam(description = "One of CURRENT_LOCATION, MAP_CENTER, DESTINATION, ROUTE, NAMED_PLACE, UNSPECIFIED.")
            context: String,
            @ToolParam(description = "Verbatim named reference place; only with NAMED_PLACE.")
            place: String? = null,
            @ToolParam(description = "One of ALL, NEAREST, NEXT, LAST.")
            resultMode: String,
            @ToolParam(description = "One of ANY, OPEN_NOW, OPEN_24_7, OPEN_AT_ARRIVAL.")
            availability: String,
        ): Map<String, Any> {
            val call = CapturedCall.Poi(name, category, context, place, resultMode, availability)
            check(captured.compareAndSet(null, call)) { "Only one search call is allowed" }
            return mapOf("accepted" to true)
        }

        @Tool(description = "Open or search one global address, coordinate pair, Plus Code, or unique named location in OsmAnd.")
        fun searchLocation(
            @ToolParam(description = "Verbatim address, coordinates, Plus Code, or unique place text.")
            query: String,
        ): Map<String, Any> {
            val call = CapturedCall.Location(query)
            check(captured.compareAndSet(null, call)) { "Only one search call is allowed" }
            return mapOf("accepted" to true)
        }
    }
}

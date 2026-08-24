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
        MODEL_OUTPUT_CORRUPTED,
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

    private const val INFERENCE_TIMEOUT_SECONDS = 90L
    private const val TOOL_CAPTURE_CANCEL_POLL_MS = 25L
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
        executor.execute { parseOnCpu(appContext, manager.modelFile, text, callback) }
    }

    @OptIn(ExperimentalApi::class)
    private fun parseOnCpu(context: Context, modelFile: File, userText: String, callback: Callback) {
        var conversation: Conversation? = null
        var timeoutTask: ScheduledFuture<*>? = null
        var toolCaptureCancelTask: ScheduledFuture<*>? = null
        val timedOut = AtomicBoolean(false)
        val tools = OsmandSearchTools()
        try {
            val activeEngine = getOrCreateCpuEngine(context, modelFile)
            // The exported FunctionGemma bundle embeds a Hugging Face tokenizer.
            // LiteRT-LM constrained decoding only supports SentencePiece tokenizers.
            ExperimentalFlags.enableConversationConstrainedDecoding = false
            val config = ConversationConfig(
                systemMessage = Message.of(DEVELOPER_PROMPT),
                tools = listOf(tools),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
            )
            conversation = activeEngine.createConversation(config)
            val runningConversation = conversation
            // LiteRT-LM 0.8 automatically asks the model for a final response after executing a
            // tool. OsmAnd already has the complete search request at that point, so repeatedly
            // cancel that unnecessary second generation as soon as the tool has been captured.
            toolCaptureCancelTask = watchdog.scheduleAtFixedRate({
                if (tools.captured.get() != null) {
                    runCatching { runningConversation.cancelProcess() }
                }
            }, TOOL_CAPTURE_CANCEL_POLL_MS, TOOL_CAPTURE_CANCEL_POLL_MS, TimeUnit.MILLISECONDS)
            timeoutTask = watchdog.schedule({
                timedOut.set(true)
                runCatching { runningConversation.cancelProcess() }
            }, INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val response = conversation.sendMessage(Message.of(userText)).toString()
            val request = tools.captured.get()?.let { requestFromCapturedCall(context, userText, it) }
            when {
                request != null -> mainHandler.post { callback.onResult(request) }
                timedOut.get() -> postError(callback, ErrorCode.TIMEOUT, "FunctionGemma n’a pas répondu à temps sur le CPU")
                response.contains("<pad>", ignoreCase = true) -> postError(
                    callback,
                    ErrorCode.MODEL_OUTPUT_CORRUPTED,
                    "Le modèle a produit une sortie corrompue (<pad>) sur le CPU",
                )
                response.isNotBlank() -> mainHandler.post { callback.onClarification(response) }
                else -> postError(callback, ErrorCode.NO_TOOL_CALL, "FunctionGemma n’a produit aucun appel de recherche")
            }
        } catch (error: Throwable) {
            val capturedCall = tools.captured.get()
            if (capturedCall != null) {
                try {
                    val request = requestFromCapturedCall(context, userText, capturedCall)
                    mainHandler.post { callback.onResult(request) }
                } catch (validationError: Throwable) {
                    postError(
                        callback,
                        ErrorCode.INVALID_MODEL_OUTPUT,
                        validationError.message ?: validationError.javaClass.simpleName,
                    )
                }
            } else {
                val code = when {
                    timedOut.get() -> ErrorCode.TIMEOUT
                    error is IllegalArgumentException -> ErrorCode.INVALID_MODEL_OUTPUT
                    else -> ErrorCode.INFERENCE_ERROR
                }
                val message = if (code == ErrorCode.TIMEOUT) {
                    "FunctionGemma n’a produit aucun appel de recherche en ${INFERENCE_TIMEOUT_SECONDS} secondes"
                } else {
                    error.message ?: error.javaClass.simpleName
                }
                postError(callback, code, message)
            }
        } finally {
            timeoutTask?.cancel(false)
            toolCaptureCancelTask?.cancel(false)
            runCatching { conversation?.close() }
            scheduleIdleRelease()
        }
    }

    private fun requestFromCapturedCall(
        context: Context,
        userText: String,
        call: CapturedCall,
    ): SmartSearchRequest = when (call) {
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
    }

    @Synchronized
    private fun getOrCreateCpuEngine(context: Context, modelFile: File): Engine {
        idleReleaseTask?.cancel(false)
        idleReleaseTask = null
        val stamp = "${modelFile.absolutePath}:${modelFile.length()}:${modelFile.lastModified()}"
        val current = engine
        if (current != null && engineModelStamp == stamp
            && runCatching { current.isInitialized() }.getOrDefault(false)) {
            return current
        }
        if (current != null) {
            runCatching { current.close() }
        }
        engine = null
        engineModelStamp = null
        val cacheDirectory = File(context.cacheDir, "functiongemma").apply { mkdirs() }
        val next = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU,
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = cacheDirectory.absolutePath,
            )
        )
        try {
            next.initialize()
        } catch (error: Throwable) {
            runCatching { next.close() }
            throw error
        }
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

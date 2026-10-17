package org.aim.handwriting

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON for the local correction log: ink + what the user said it should read.
 * Stays on device. Used later to fine-tune the open model on your own hand.
 * Points are stored as flat arrays to keep files small. Time is relative to
 * the first point of the sample.
 */
@Serializable
data class InkSampleDto(
    val version: Int = 1,
    val locale: String,
    val label: String,
    val engine: String,
    val engineGuess: String?,
    val createdAtEpochMs: Long,
    val writingAreaWidth: Float,
    val writingAreaHeight: Float,
    /** Each stroke: [x0,y0,dt0,p0, x1,y1,dt1,p1, ...] */
    val strokes: List<List<Float>>,
)

object InkSerialization {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun toDto(ink: Ink, ctx: RecognitionContext, label: String, engine: EngineId, engineGuess: String?, nowEpochMs: Long): InkSampleDto {
        val t0 = ink.strokes.firstOrNull()?.startTime ?: 0L
        return InkSampleDto(
            locale = ctx.locale, label = label, engine = engine.name, engineGuess = engineGuess,
            createdAtEpochMs = nowEpochMs,
            writingAreaWidth = ctx.writingAreaWidth, writingAreaHeight = ctx.writingAreaHeight,
            strokes = ink.strokes.map { s ->
                val out = ArrayList<Float>(s.points.size * 4)
                for (p in s.points) { out.add(p.x); out.add(p.y); out.add((p.t - t0).toFloat()); out.add(p.pressure) }
                out
            },
        )
    }

    fun toInk(dto: InkSampleDto): Ink = Ink(dto.strokes.map { flat ->
        require(flat.size % 4 == 0) { "stroke array length must be a multiple of 4" }
        InkStroke((flat.indices step 4).map { i -> InkPoint(flat[i], flat[i + 1], flat[i + 2].toLong(), flat[i + 3]) })
    })

    fun encode(dto: InkSampleDto): String = json.encodeToString(dto)
    fun decode(s: String): InkSampleDto = json.decodeFromString(s)
}

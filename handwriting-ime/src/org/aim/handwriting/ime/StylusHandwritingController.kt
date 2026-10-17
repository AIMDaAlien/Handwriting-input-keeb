package org.aim.handwriting.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aim.handwriting.Candidate
import org.aim.handwriting.CommitPolicy
import org.aim.handwriting.CommitPolicy.Decision
import org.aim.handwriting.HandwritingRecognizer
import org.aim.handwriting.Ink
import org.aim.handwriting.RecognitionContext
import org.aim.handwriting.RecognizerException
import org.aim.handwriting.StrokeBuilder
import org.aim.handwriting.TextJoiner
import org.futo.inputmethod.latin.uix.ActionInputTransaction

/**
 * Mode B: write directly on a text field. The system starts a session when the pen
 * lands on (or near) an editor, calls onStartStylusHandwriting, and from then on
 * routes every stylus event to the full-screen, transparent ink window, whose view
 * coordinates are screen coordinates.
 *
 * While a session is open every pen touch on screen is ink, so the session is
 * finished as soon as a word commits; the next pen-down on a field starts a new one.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class StylusHandwritingController(
    private val service: InputMethodService,
    private val scope: CoroutineScope,
    private val locale: () -> String,
    private val createTransaction: () -> ActionInputTransaction?,
) : StylusInkAdapter.Listener {
    private val builder = StrokeBuilder()
    private val adapter = StylusInkAdapter(builder, this)
    private val policy = CommitPolicy()

    private var recognizer: HandwritingRecognizer? = null
    private var recognizerLocale: String? = null
    private var candidates: List<Candidate> = emptyList()
    private var transaction: ActionInputTransaction? = null
    private var tickJob: Job? = null
    private var recognizeJob: Job? = null
    private var inkView: InkView? = null

    /** From onStartStylusHandwriting. Returns whether this IME takes the session. */
    fun onStart(): Boolean {
        val window = service.stylusHandwritingWindow ?: return false
        val view = inkView ?: InkView(service, adapter::onTouchEvent) { builder.snapshot() }.also { inkView = it }
        if (view.parent == null) window.setContentView(view)
        val loc = locale()
        if (recognizer == null || recognizerLocale != loc) {
            recognizer?.close()
            recognizer = HandwritingEngines.create(service, loc)
            recognizerLocale = loc
        }
        reset()
        return true
    }

    /** From onFinishStylusHandwriting: the system timed out, or we finished it. */
    fun onFinish() {
        tickJob?.cancel()
        recognizeJob?.cancel()
        discardTransaction()
        reset()
    }

    fun onDestroy() {
        onFinish()
        recognizer?.close()
        recognizer = null
    }

    override fun onStrokeStart() {
        if (policy.onPenDown(now()) == Decision.CANCEL_PENDING) {
            tickJob?.cancel()
            recognizeJob?.cancel()
        }
        inkView?.invalidate()
    }

    override fun onStrokeMove() {
        inkView?.invalidate()
    }

    override fun onStrokeEnd() {
        policy.onPenUp(now())
        inkView?.invalidate()
        scheduleTicks()
    }

    override fun onStrokeCancelled() {
        policy.onPenUp(now())
        inkView?.invalidate()
        if (builder.strokeCount > 0) scheduleTicks()
    }

    private fun scheduleTicks() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val wait = policy.nextDeadline(now()) ?: break
                delay(wait)
                when (policy.onTick(now())) {
                    Decision.RECOGNIZE_PARTIAL -> recognizePartial()
                    Decision.COMMIT -> {
                        recognizeJob?.join()
                        commit(candidates.firstOrNull()?.text ?: recognize(builder.snapshot()).firstOrNull()?.text)
                        break
                    }
                    else -> {}
                }
            }
        }
    }

    private fun recognizePartial() {
        recognizeJob?.cancel()
        recognizeJob = scope.launch {
            candidates = recognize(builder.snapshot())
            val best = candidates.firstOrNull() ?: return@launch
            val tx = transaction ?: createTransaction().also { transaction = it } ?: return@launch
            tx.updatePartial(TextJoiner.join(best.text, tx.textContext.beforeCursor, tx.textContext.afterCursor))
        }
    }

    private suspend fun recognize(ink: Ink): List<Candidate> {
        val engine = recognizer ?: return emptyList()
        if (ink.isEmpty) return emptyList()
        val ctx = RecognitionContext(
            locale = recognizerLocale ?: locale(),
            preContext = transaction?.textContext?.beforeCursor?.toString().orEmpty(),
            writingAreaWidth = inkView?.width?.toFloat() ?: 0f,
            writingAreaHeight = inkView?.height?.toFloat() ?: 0f,
        )
        return try {
            withContext(Dispatchers.Default) { engine.recognize(ink, ctx) }.candidates
        } catch (e: RecognizerException.ModelMissing) {
            emptyList()
        }
    }

    private fun commit(text: String?) {
        val tx = transaction ?: if (text.isNullOrBlank()) null else createTransaction()
        if (tx != null && !text.isNullOrBlank()) {
            tx.commit(TextJoiner.join(text, tx.textContext.beforeCursor, tx.textContext.afterCursor))
        } else {
            tx?.let { it.updatePartial(""); it.cancel() }
        }
        transaction = null
        reset()
        // Hand the pen back to the app; onFinishStylusHandwriting follows.
        service.finishStylusHandwriting()
    }

    private fun discardTransaction() {
        transaction?.let { it.updatePartial(""); it.cancel() }
        transaction = null
    }

    private fun reset() {
        builder.take()
        policy.reset()
        candidates = emptyList()
        inkView?.invalidate()
    }

    private fun now() = SystemClock.uptimeMillis()
}

@SuppressLint("ViewConstructor")
private class InkView(
    context: Context,
    private val onEvent: (MotionEvent) -> Boolean,
    private val ink: () -> Ink,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f * resources.displayMetrics.density
        // Drawn over arbitrary app content, so a saturated blue reads on light and dark.
        color = Color.rgb(0x3D, 0x7B, 0xFF)
    }
    private val path = Path()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = onEvent(event)

    override fun onDraw(canvas: Canvas) {
        for (s in ink().strokes) {
            val pts = s.points
            if (pts.size == 1) {
                canvas.drawPoint(pts[0].x, pts[0].y, paint)
                continue
            }
            path.rewind()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
            canvas.drawPath(path, paint)
        }
    }
}

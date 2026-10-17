package org.aim.handwriting.ime

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aim.handwriting.Candidate
import org.aim.handwriting.CommitPolicy
import org.aim.handwriting.CommitPolicy.Decision
import org.aim.handwriting.HandwritingRecognizer
import org.aim.handwriting.RecognitionContext
import org.aim.handwriting.RecognizerException
import org.aim.handwriting.StrokeBuilder
import org.aim.handwriting.TextJoiner
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ActionInputTransaction
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction

/**
 * Handwriting pad shown in place of the keyboard.
 *
 * Input transactions: while one is open FUTO routes all input through it, so the
 * normal keyboard is effectively disconnected. One is therefore opened lazily for
 * the first live preview of a word and always ended by commit or discard, never
 * left open between words. Note that cancel() on a transaction commits its
 * partial text, so discarding means updatePartial("") first.
 */
class HandwritingActionWindow(
    private val manager: KeyboardManagerForAction,
    private val recognizer: HandwritingRecognizer,
    private val locale: String,
) : ActionWindow(), StylusInkAdapter.Listener {
    private val scope = manager.getLifecycleScope()
    private val builder = StrokeBuilder()
    private val adapter = StylusInkAdapter(builder, this)
    private val policy = CommitPolicy()

    private var inkVersion by mutableIntStateOf(0)
    private val candidates = mutableStateListOf<Candidate>()
    private var message by mutableStateOf<String?>(null)
    private var padSize = IntSize.Zero

    private var transaction: ActionInputTransaction? = null
    private var tickJob: Job? = null
    private var recognizeJob: Job? = null

    private fun now() = SystemClock.uptimeMillis()

    override fun onStrokeStart() {
        if (policy.onPenDown(now()) == Decision.CANCEL_PENDING) {
            tickJob?.cancel()
            recognizeJob?.cancel()
        }
        inkVersion++
    }

    override fun onStrokeMove() {
        inkVersion++
    }

    override fun onStrokeEnd() {
        policy.onPenUp(now())
        inkVersion++
        scheduleTicks()
    }

    override fun onStrokeCancelled() {
        // The policy already saw pen down; treat the dropped stroke as a pen up.
        policy.onPenUp(now())
        inkVersion++
        if (builder.strokeCount > 0) scheduleTicks() else discard()
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
                        commit(candidates.firstOrNull()?.text ?: recognize().firstOrNull()?.text)
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
            val result = recognize()
            candidates.clear()
            candidates.addAll(result)
            val best = result.firstOrNull() ?: return@launch
            val tx = openTransaction() ?: return@launch
            tx.updatePartial(TextJoiner.join(best.text, tx.textContext.beforeCursor, tx.textContext.afterCursor))
        }
    }

    private suspend fun recognize(): List<Candidate> {
        val ink = builder.snapshot()
        if (ink.isEmpty) return emptyList()
        val ctx = RecognitionContext(
            locale = locale,
            preContext = transaction?.textContext?.beforeCursor?.toString().orEmpty(),
            writingAreaWidth = padSize.width.toFloat(),
            writingAreaHeight = padSize.height.toFloat(),
        )
        return try {
            withContext(Dispatchers.Default) { recognizer.recognize(ink, ctx) }.candidates
                .also { message = null }
        } catch (e: RecognizerException.ModelMissing) {
            message = "No handwriting model for ${e.locale}"
            emptyList()
        }
    }

    private fun openTransaction(): ActionInputTransaction? {
        transaction?.let { return it }
        // Throws in debug builds when no text field has input focus.
        return runCatching { manager.createInputTransaction() }
            .onFailure { message = "Tap a text field first" }
            .getOrNull()
            .also { transaction = it }
    }

    private fun commit(text: String?) {
        val tx = transaction ?: if (text.isNullOrBlank()) null else openTransaction()
        if (tx != null) {
            if (text.isNullOrBlank()) {
                tx.updatePartial("")
                tx.cancel()
            } else {
                tx.commit(TextJoiner.join(text, tx.textContext.beforeCursor, tx.textContext.afterCursor))
            }
        }
        transaction = null
        clearInk()
    }

    private fun discard() {
        tickJob?.cancel()
        recognizeJob?.cancel()
        commit(null)
    }

    private fun pick(candidate: Candidate) {
        tickJob?.cancel()
        recognizeJob?.cancel()
        commit(candidate.text)
    }

    private fun clearInk() {
        builder.take()
        policy.reset()
        candidates.clear()
        inkVersion++
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.action_handwriting_title)

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val view = LocalView.current
        val inkColor = MaterialTheme.colorScheme.onSurface
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    candidates.forEach { c ->
                        TextButton(onClick = { pick(c) }) { Text(c.text) }
                    }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                Spacer(modifier = Modifier.padding(4.dp))
                TextButton(onClick = { discard() }) {
                    Text(stringResource(R.string.action_handwriting_clear))
                }
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { padSize = it }
                    .pointerInteropFilter { ev ->
                        // Deliver every sample as it arrives instead of once per frame.
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(ev)
                        adapter.onTouchEvent(ev)
                    }
            ) {
                // Reading the counter subscribes this draw pass to ink changes.
                if (inkVersion < 0) return@Canvas
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                for (s in builder.snapshot().strokes) {
                    val path = Path()
                    s.points.forEachIndexed { i, p ->
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    if (s.points.size == 1) {
                        drawCircle(inkColor, radius = stroke.width / 2, center = Offset(s.points[0].x, s.points[0].y))
                    } else {
                        drawPath(path, inkColor, style = stroke)
                    }
                }
            }
        }
    }

    override fun close(): CloseResult {
        tickJob?.cancel()
        recognizeJob?.cancel()
        // Unfinished ink is dropped rather than committed: closing is an explicit "never mind".
        transaction?.let {
            it.updatePartial("")
            it.cancel()
        }
        transaction = null
        recognizer.close()
        return CloseResult.Default
    }
}

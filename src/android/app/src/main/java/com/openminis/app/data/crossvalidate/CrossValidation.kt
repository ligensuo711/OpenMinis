package com.openminis.app.data.crossvalidate

import com.openminis.app.data.memorygraph.MemoryGraphLayout

/**
 * [T-cross-validation] Stage 4.12 — multi-model cross-validation pure logic.
 *
 * Given N answers to the same prompt, produce pairwise divergence signals so
 * the UI can flag which model pairs answered substantially differently. Zero
 * Android / Compose deps so the math is assertable standalone (kotlinc) before
 * the sheet wires it — same discipline as the stage-1/2/3 pure layers.
 *
 * ## Honesty of the signal
 * Similarity is LEXICAL (Jaccard over significant terms, reusing
 * [MemoryGraphLayout.terms] — English words + CJK bigrams, stopword-filtered).
 * Lexical divergence is a *weak* proxy for real disagreement: two answers can
 * say the same thing in different words and score low, or pad the same words
 * and score high. The UI therefore labels low-similarity pairs as "answers
 * differ markedly" rather than claiming a factual conflict — the user (or a
 * follow-up prompt) decides whether the difference is semantic.
 */
object CrossValidation {

    /**
     * One pairwise comparison. [similarity] is the Jaccard overlap in 0..1;
     * [diverged] true when similarity < [threshold].
     */
    data class Divergence(
        val modelA: String,
        val modelB: String,
        val similarity: Double,
        val diverged: Boolean,
    )

    /**
     * Jaccard similarity of two texts over their significant-term sets.
     * Empty-vs-empty → 1.0 (both said nothing); one-empty → 0.0.
     */
    fun similarity(a: String, b: String): Double {
        val ta = MemoryGraphLayout.terms(a)
        val tb = MemoryGraphLayout.terms(b)
        if (ta.isEmpty() && tb.isEmpty()) return 1.0
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size
        val union = ta.union(tb).size
        if (union == 0) return 1.0
        return inter.toDouble() / union.toDouble()
    }

    /**
     * Pairwise divergence over [answers] (label → text). One row per pair, in
     * stable order. [threshold] defaults to 0.25 (lexical Jaccard below a
     * quarter = markedly different wording).
     */
    fun divergences(
        answers: List<Pair<String, String>>,
        threshold: Double = 0.25,
    ): List<Divergence> {
        val out = ArrayList<Divergence>()
        for (i in answers.indices) {
            for (j in i + 1 until answers.size) {
                val sim = similarity(answers[i].second, answers[j].second)
                out.add(Divergence(answers[i].first, answers[j].first, sim, sim < threshold))
            }
        }
        return out
    }
}
package io.agora.conversational.api.transcript

/**
 * Word-mode merge rule for one agent turn. An incoming word is a duplicate only when a kept
 * word has the same text and start time; incoming words are not compared with each other.
 * Kept words come first, new words follow in message order, then a stable sort by start time.
 */
internal object TranscriptWordMerge {
    fun <W> merge(kept: List<W>, incoming: List<W>, word: (W) -> String, startMs: (W) -> Long): List<W> {
        val added = incoming.filter { w -> kept.none { k -> startMs(k) == startMs(w) && word(k) == word(w) } }
        return (kept + added).sortedBy { startMs(it) }
    }
}

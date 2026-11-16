package io.agora.conversational.api.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/**
 * Tests for [TranscriptWordMerge]. A local word type keeps the RTC-backed [TranscriptController]
 * out of the test runtime. `start_ms` values are relative to the log sessions they come from.
 */
class TranscriptWordMergeTest {

    private data class Word(val word: String, val startMs: Long)

    private fun merge(kept: List<Word>, incoming: List<Word>): List<Word> =
        TranscriptWordMerge.merge(kept, incoming, { it.word }, { it.startMs })

    // Property 1: Bug Condition - a different word with an already kept start_ms is not dropped.
    // **Validates: Requirements 1.1, 1.3, 1.4, 2.1**

    @Test
    fun u1CrossMessageWordWithKeptStartIsKept() {
        // Cartesia turn 4, seq 13 -> seq 14: zero-duration 'a' shares start_ms with 'different'.
        val kept = listOf(w("try", 6744), sp(6902), w("a", 6984))
        val incoming = listOf(w("try", 6744), sp(6902), w("a", 6984), sp(6983), w("different", 6984))

        assertWords(
            listOf(w("try", 6744), sp(6902), sp(6983), w("a", 6984), w("different", 6984)),
            merge(kept, incoming)
        )
    }

    @Test
    fun u5OverlappingWindowsRestoreSequenceWithSameStartGroups() {
        val windows = slidingWindows(U5_SEQUENCE, size = 32, step = 3)
        // D3: the first message of a Kotlin turn is stored as is and never goes through the merge.
        var kept = windows.first()
        windows.drop(1).forEach { kept = merge(kept, it) }

        assertWords(U5_SEQUENCE, kept)
    }

    @Test
    fun u6SameStartWordArrivingAfterInterruptFollowsA() {
        // K after an interrupt at 6984 (pts 6990). The controller marks only 'a' as interrupted
        // (<= boundary); the merge ignores status, so only presence and order are asserted.
        val kept = listOf(w("try", 6744), sp(6902), sp(6983), w("a", 6984))
        val incoming = listOf(sp(6983), w("a", 6984), w("different", 6984))

        val result = merge(kept, incoming)
        val a = result.indexOf(w("a", 6984))
        val different = result.indexOf(w("different", 6984))
        assertTrue("different@6984 missing: ${format(result)}", different >= 0)
        assertEquals("different@6984 must directly follow a@6984: ${format(result)}", a + 1, different)
    }

    @Test
    fun generatedBugConditionSequencesMatchExpectedMerge() {
        val random = Random(SEED)
        var bugConditionCases = 0
        var failures = 0
        var firstFailure: String? = null

        repeat(ITERATIONS) { iteration ->
            val messages = generateMessages(random)
            if (!isBugCondition(messages)) return@repeat
            bugConditionCases++
            val mismatch = firstMismatchWithExpectedMerge(messages) ?: return@repeat
            failures++
            if (firstFailure == null) {
                firstFailure = "seed=$SEED iteration=$iteration $mismatch\nmessages:\n${formatMessages(messages)}"
            }
        }

        firstFailure?.let {
            System.err.println(it)
            fail("$failures of $bugConditionCases bug-condition sequences differ from expectedMerge; first: $it")
        }
        assertTrue("generator produced no bug-condition sequences", bugConditionCases > 0)
    }

    // Property 2: Preservation - inputs outside the bug condition keep the pre-fix result.
    // **Validates: Requirements 3.1, 3.2, 3.3, 3.7, 3.10, 3.11**
    // Expected values were observed on the unfixed rule (start_ms key) before the fix.

    @Test
    fun u2aSameStartWordsFirstSeenInOneMessageAreKept() {
        // Kotlin compares incoming words only with K, so 'a' and 'different' first seen together stay.
        val kept = listOf(w("try", 6744), sp(6902))
        val incoming = listOf(sp(6902), w("a", 6984), sp(6983), w("different", 6984))
        assertOutsideBugCondition(kept, incoming)

        assertWords(
            listOf(w("try", 6744), sp(6902), sp(6983), w("a", 6984), w("different", 6984)),
            merge(kept, incoming)
        )
    }

    @Test
    fun u3OverlappingResendAddsOnlyNewWords() {
        // Cartesia turn 4 seq 14 -> seq 15 on top of the fixed U1 result.
        val kept = listOf(w("try", 6744), sp(6902), sp(6983), w("a", 6984), w("different", 6984))
        val incoming = listOf(sp(6902), w("a", 6984), sp(6983), w("different", 6984), sp(7222), w("one.", 7304))
        assertOutsideBugCondition(kept, incoming)

        assertWords(kept + listOf(sp(7222), w("one.", 7304)), merge(kept, incoming))
    }

    @Test
    fun u4aResendAfterFirstMessageAddsOnlyTian() {
        // D3: the first message (～ and 团 share 7195) is K as is; only the second step merges.
        val first = listOf(w("想", 6932), w("～", 7195), w("团", 7195), w("团", 7413), w("昨", 7686))
        val second = first + w("天", 7823)
        assertOutsideBugCondition(first, second)

        assertWords(first + w("天", 7823), merge(first, second))
    }

    @Test
    fun u4bSameStartPunctuationFirstSeenInOneMessageIsKept() {
        val kept = listOf(w("架", 700), w("’", 927), w("？", 927))
        val first = listOf(
            w("架", 700), w("’", 927), w("？", 927), w("”", 1061), w("。", 1079), w("。", 1079), w("小", 1079)
        )
        val second = listOf(w("”", 1061), w("。", 1079), w("。", 1079), w("小", 1079), w("白", 1270))
        assertOutsideBugCondition(kept, first, second)

        val afterFirst = merge(kept, first)
        assertWords(kept + listOf(w("”", 1061), w("。", 1079), w("。", 1079), w("小", 1079)), afterFirst)
        assertWords(afterFirst + w("白", 1270), merge(afterFirst, second))
    }

    @Test
    fun u7SameKeySpaceFirstSeenInLaterMessageIsStillDropped() {
        // Known gap 3.10: seq 12 brings another ␠@6662 after me@6663; it has K's key and is dropped.
        val kept = listOf(w("Let", 6380), sp(6662), w("me", 6663))
        val incoming = listOf(w("Let", 6380), sp(6662), w("me", 6663), sp(6662), w("try", 6744))
        assertOutsideBugCondition(kept, incoming)

        val result = merge(kept, incoming)
        assertWords(listOf(w("Let", 6380), sp(6662), w("me", 6663), w("try", 6744)), result)
        assertEquals("Let metry", text(result))
    }

    @Test
    fun sameKeyTwiceInOneMessageIsKeptWhenAbsentFromKept() {
        val kept = listOf(w("try", 6744))
        val incoming = listOf(w("try", 6744), w("a", 6984), w("a", 6984))
        assertOutsideBugCondition(kept, incoming)

        assertWords(listOf(w("try", 6744), w("a", 6984), w("a", 6984)), merge(kept, incoming))
    }

    @Test
    fun plainOverlappingWindowsRestoreSequence() {
        // Same 40 words as U5, every start_ms distinct; the first window is K as is (D3).
        val windows = slidingWindows(PLAIN_SEQUENCE, size = 32, step = 3)
        assertOutsideBugCondition(*windows.toTypedArray())
        var kept = windows.first()
        windows.drop(1).forEach { kept = merge(kept, it) }

        assertWords(PLAIN_SEQUENCE, kept)
    }

    @Test
    fun generatedSequencesOutsideBugConditionMatchPreFixMerge() {
        // Same seed and generator as the bug-condition test, so the two tests split the iterations.
        val random = Random(SEED)
        var preservationCases = 0
        var failures = 0
        var firstFailure: String? = null

        repeat(ITERATIONS) { iteration ->
            val messages = generateMessages(random)
            if (isBugCondition(messages)) return@repeat
            preservationCases++
            val mismatch = firstMismatchWithReferenceMerge(messages) ?: return@repeat
            failures++
            if (firstFailure == null) {
                firstFailure = "seed=$SEED iteration=$iteration $mismatch\nmessages:\n${formatMessages(messages)}"
            }
        }

        println("preservation: seed=$SEED $preservationCases of $ITERATIONS sequences outside the bug condition")
        firstFailure?.let {
            System.err.println(it)
            fail("$failures of $preservationCases preservation sequences differ from the pre-fix merge; first: $it")
        }
        assertTrue("generator produced no sequences outside the bug condition", preservationCases > 0)
    }

    // Property 3: overlapping windows restore the reference sequence.
    // **Validates: Requirements 2.1, 2.2, 2.3, 3.1**

    @Test
    fun property3OverlappingWindowsRestoreReference() {
        val random = Random(SEED)
        var multiWindowCases = 0
        var sameStartCases = 0
        var failures = 0
        var firstFailure: String? = null

        repeat(ITERATIONS) { iteration ->
            val reference = generateReference(random)
            val windows = generateWindows(random, reference)
            if (windows.size > 1) multiWindowCases++
            if (reference.zipWithNext().any { (a, b) -> a.startMs == b.startMs }) sameStartCases++
            // D3: the first window is K as is; every later window goes through the merge.
            var kept = windows.first()
            windows.drop(1).forEach { kept = merge(kept, it) }
            if (kept == reference) return@repeat
            failures++
            if (firstFailure == null) {
                firstFailure = "seed=$SEED iteration=$iteration\nL=${format(reference)}\n" +
                    "result=${format(kept)}\nwindows:\n${formatMessages(windows)}"
            }
        }

        println(
            "property 3: seed=$SEED iterations=$ITERATIONS multiWindow=$multiWindowCases " +
                "sameStartInL=$sameStartCases"
        )
        firstFailure?.let {
            System.err.println(it)
            fail("property 3: $failures of $ITERATIONS window sequences do not restore L; first: $it")
        }
        assertTrue("generator produced no multi-window sequences", multiWindowCases > 0)
        assertTrue("generator produced no same-start words in L", sameStartCases > 0)
    }

    // Property 4: dedup key counts. Every key that occurs in K keeps its count in K; a key that
    // occurs only in M has its count in M (new words are not dropped, M is not deduplicated).
    // **Validates: Requirements 2.1, 2.2, 3.1, 3.10**

    @Test
    fun property4DedupKeyCountsArePreserved() {
        val random = Random(SEED)
        val coverage = mutableMapOf<MergeCase, Int>()
        var failures = 0
        var firstFailure: String? = null

        repeat(ITERATIONS) { iteration ->
            val (kept, incoming) = generateKeptAndIncoming(random)
            countMergeCases(kept, incoming, coverage)
            val result = merge(kept, incoming)
            val mismatch = keyCountMismatch(kept, incoming, result) ?: return@repeat
            failures++
            if (firstFailure == null) {
                firstFailure = "seed=$SEED iteration=$iteration $mismatch\nK=${format(kept)}\n" +
                    "M=${format(incoming)}\nresult=${format(result)}"
            }
        }

        reportRandomMergeRun("property 4", coverage, failures, firstFailure)
    }

    // Property 5: stable order. The result is K ++ added stably sorted by start_ms: within one
    // start_ms, kept words come first in K order and new words follow in message order.
    // **Validates: Requirements 2.3, 3.3**
    //
    // Property 6 (interrupt inheritance) is not unit-tested in Kotlin. The existing loop in
    // TranscriptController.handleAgentWordMessage (lines 616-623) marks every word after the first
    // interrupted word of the merge result as interrupted, the same rule Swift and TS adopt; it
    // depends only on the order checked here.

    @Test
    fun property5ResultIsStableSortOfKeptThenAdded() {
        val random = Random(SEED)
        val coverage = mutableMapOf<MergeCase, Int>()
        var failures = 0
        var firstFailure: String? = null

        repeat(ITERATIONS) { iteration ->
            val (kept, incoming) = generateKeptAndIncoming(random)
            countMergeCases(kept, incoming, coverage)
            val expected = stableSortOracle(kept, incoming)
            val result = merge(kept, incoming)
            if (result == expected) return@repeat
            failures++
            if (firstFailure == null) {
                firstFailure = "seed=$SEED iteration=$iteration\nK=${format(kept)}\nM=${format(incoming)}\n" +
                    "expected=${format(expected)}\nactual=${format(result)}"
            }
        }

        reportRandomMergeRun("property 5", coverage, failures, firstFailure)
    }

    /** Input shapes that Properties 4 and 5 must exercise at least once. */
    private enum class MergeCase {
        EMPTY_KEPT,
        EMPTY_INCOMING,
        UNSORTED_KEPT,
        KEPT_KEY_RESENT,
        NEW_WORD_WITH_KEPT_START,
        NEW_KEY_REPEATED_IN_MESSAGE
    }

    /**
     * Random K and M for Properties 4 and 5, drawn from a design.md reference sequence L so words
     * and start_ms follow its rules. K: 0-40 draws from L with repetition, sorted by start_ms like a
     * merged K, or left in draw order (p = 0.25) like a Kotlin first message kept as is (D3).
     * M: 0-32 words in message order, each a resend of a K word (p = 0.5) or a draw from L.
     */
    private fun generateKeptAndIncoming(random: Random): Pair<List<Word>, List<Word>> {
        val reference = generateReference(random)
        val drawn = List(random.nextInt(41)) { reference[random.nextInt(reference.size)] }
        val kept = if (random.nextInt(4) == 0) drawn else drawn.sortedBy { it.startMs }
        val incoming = List(random.nextInt(33)) {
            if (kept.isNotEmpty() && random.nextBoolean()) {
                kept[random.nextInt(kept.size)]
            } else {
                reference[random.nextInt(reference.size)]
            }
        }
        return kept to incoming
    }

    private fun countMergeCases(kept: List<Word>, incoming: List<Word>, coverage: MutableMap<MergeCase, Int>) {
        val keptKeys = kept.mapTo(HashSet()) { key(it) }
        val keptStarts = kept.mapTo(HashSet()) { it.startMs }
        val newWords = incoming.filter { key(it) !in keptKeys }
        listOfNotNull(
            MergeCase.EMPTY_KEPT.takeIf { kept.isEmpty() },
            MergeCase.EMPTY_INCOMING.takeIf { incoming.isEmpty() },
            MergeCase.UNSORTED_KEPT.takeIf { kept.zipWithNext().any { (a, b) -> a.startMs > b.startMs } },
            MergeCase.KEPT_KEY_RESENT.takeIf { incoming.any { key(it) in keptKeys } },
            MergeCase.NEW_WORD_WITH_KEPT_START.takeIf { newWords.any { it.startMs in keptStarts } },
            MergeCase.NEW_KEY_REPEATED_IN_MESSAGE.takeIf {
                newWords.groupingBy { key(it) }.eachCount().values.any { it > 1 }
            }
        ).forEach { coverage[it] = (coverage[it] ?: 0) + 1 }
    }

    private fun reportRandomMergeRun(
        name: String,
        coverage: Map<MergeCase, Int>,
        failures: Int,
        firstFailure: String?
    ) {
        println("$name: seed=$SEED iterations=$ITERATIONS coverage=$coverage")
        firstFailure?.let {
            System.err.println(it)
            fail("$name: $failures of $ITERATIONS inputs failed; first: $it")
        }
        MergeCase.values().forEach { case ->
            assertTrue("$name: generator produced no $case input", (coverage[case] ?: 0) > 0)
        }
    }

    /** Property 4 check; returns a description of the first key whose count is wrong. */
    private fun keyCountMismatch(kept: List<Word>, incoming: List<Word>, result: List<Word>): String? {
        val keptCounts = kept.groupingBy { key(it) }.eachCount()
        val incomingCounts = incoming.groupingBy { key(it) }.eachCount()
        val resultCounts = result.groupingBy { key(it) }.eachCount()
        for ((dedupKey, count) in keptCounts) {
            val actual = resultCounts[dedupKey] ?: 0
            if (actual != count) return "key ${formatKey(dedupKey)} in K: K=$count result=$actual"
        }
        for ((dedupKey, count) in incomingCounts) {
            if (dedupKey in keptCounts) continue
            val actual = resultCounts[dedupKey] ?: 0
            if (actual != count) return "key ${formatKey(dedupKey)} only in M: M=$count result=$actual"
        }
        for (dedupKey in resultCounts.keys) {
            if (dedupKey !in keptCounts && dedupKey !in incomingCounts) {
                return "key ${formatKey(dedupKey)} in neither K nor M"
            }
        }
        return null
    }

    /**
     * Property 5 oracle, independent of the merge code: added words come from a key set, and
     * K ++ added is ordered by (start_ms, position), so it does not rely on sortedBy being stable.
     */
    private fun stableSortOracle(kept: List<Word>, incoming: List<Word>): List<Word> {
        val keptKeys = kept.mapTo(HashSet()) { key(it) }
        val added = incoming.filter { key(it) !in keptKeys }
        return (kept + added).withIndex()
            .sortedWith(compareBy<IndexedValue<Word>>({ it.value.startMs }, { it.index }))
            .map { it.value }
    }

    /** Dedup key (word, start_ms), compared field by field. */
    private fun key(word: Word): Pair<String, Long> = word.word to word.startMs

    private fun formatKey(key: Pair<String, Long>): String =
        "${if (key.first == " ") "␠" else key.first}@${key.second}"

    /**
     * Preservation checking: F (referenceMerge) and F' (merge) accumulate their own K and agree on
     * the word sequence after every step; the first message is K as is for both (D3).
     */
    private fun firstMismatchWithReferenceMerge(messages: List<List<Word>>): String? {
        var referenceKept = messages.first()
        var kept = messages.first()
        for (step in 1 until messages.size) {
            val incoming = messages[step]
            val reference = referenceMerge(referenceKept, incoming)
            val actual = merge(kept, incoming)
            if (actual != reference) {
                return "step=$step\nK(F)=${format(referenceKept)}\nK(F')=${format(kept)}\nM=${format(incoming)}\n" +
                    "F=${format(reference)}\nF'=${format(actual)}"
            }
            referenceKept = reference
            kept = actual
        }
        return null
    }

    private fun assertOutsideBugCondition(vararg messages: List<Word>) {
        assertFalse(
            "input satisfies the bug condition:\n${formatMessages(messages.toList())}",
            isBugCondition(messages.toList())
        )
    }

    private fun text(words: List<Word>): String = words.joinToString("") { it.word }

    /** Fix checking: every merge step equals [expectedMerge]; the first message is K as is (D3). */
    private fun firstMismatchWithExpectedMerge(messages: List<List<Word>>): String? {
        var kept = messages.first()
        for (step in 1 until messages.size) {
            val incoming = messages[step]
            val expected = expectedMerge(kept, incoming)
            val actual = merge(kept, incoming)
            if (actual != expected) {
                return "step=$step\nK=${format(kept)}\nM=${format(incoming)}\n" +
                    "expected=${format(expected)}\nactual=${format(actual)}"
            }
            kept = actual
        }
        return null
    }

    /**
     * Kotlin bug condition: a word whose (word, start_ms) is not kept but whose start_ms is kept with
     * different text. Kotlin compares only with K before the merge, so words earlier in the same
     * message never count. K accumulates with the pre-fix rule [referenceMerge].
     */
    private fun isBugCondition(messages: List<List<Word>>): Boolean {
        var kept: List<Word> = emptyList()
        messages.forEachIndexed { index, incoming ->
            for (w in incoming) {
                val sameKeyKept = kept.any { it.word == w.word && it.startMs == w.startMs }
                val sameStartKept = kept.any { it.startMs == w.startMs && it.word != w.word }
                if (!sameKeyKept && sameStartKept) return true
            }
            kept = if (index == 0) incoming else referenceMerge(kept, incoming)
        }
        return false
    }

    /** Pre-fix rule: copy of TranscriptController.kt lines 611-621 at v2.10.1 (start_ms only). */
    private fun referenceMerge(kept: List<Word>, newWords: List<Word>): List<Word> {
        val mergedWords = kept.toMutableList()
        newWords.forEach { newWord ->
            if (kept.none { it.startMs == newWord.startMs }) {
                mergedWords.add(newWord)
            }
        }
        return mergedWords.sortedBy { it.startMs }.toList()
    }

    /** design.md expectedMerge: K ++ added, stably sorted by start_ms. */
    private fun expectedMerge(kept: List<Word>, incoming: List<Word>): List<Word> {
        val added = incoming.filter { w -> kept.none { it.word == w.word && it.startMs == w.startMs } }
        return (kept + added).sortedBy { it.startMs }
    }

    private fun generateMessages(random: Random): List<List<Word>> {
        val reference = generateReference(random)
        val messages = mutableListOf<List<Word>>()
        for (window in generateWindows(random, reference)) {
            messages += perturb(random, window)
            if (messages.size > 1 && random.nextInt(5) == 0) {
                // Resend a whole earlier message.
                messages += messages[random.nextInt(messages.size)]
            }
        }
        return messages
    }

    /** Length 1-120, start_ms from 0, increments 0 (p = 0.3), 1, 80 or 250, (word, start_ms) unique. */
    private fun generateReference(random: Random): List<Word> {
        val length = 1 + random.nextInt(120)
        val result = ArrayList<Word>(length)
        var startMs = 0L
        for (index in 0 until length) {
            if (index > 0) startMs += if (random.nextInt(10) < 3) 0L else INCREMENTS[random.nextInt(INCREMENTS.size)]
            var choices = VOCAB.filter { text -> result.none { it.startMs == startMs && it.word == text } }
            if (choices.isEmpty()) {
                startMs += 1
                choices = VOCAB
            }
            result += Word(choices[random.nextInt(choices.size)], startMs)
        }
        return result
    }

    /** Windows of length 1-32; each start advances 1..window length; the last covers the end. */
    private fun generateWindows(random: Random, reference: List<Word>): List<List<Word>> {
        val windows = mutableListOf<List<Word>>()
        var start = 0
        while (true) {
            val end = minOf(start + 1 + random.nextInt(32), reference.size)
            windows += reference.subList(start, end).toList()
            if (end == reference.size) return windows
            start += 1 + random.nextInt(end - start)
        }
    }

    /** Randomly adds a same-start different word and an in-message duplicate key, keeping order. */
    private fun perturb(random: Random, window: List<Word>): List<Word> {
        val message = window.toMutableList()
        if (random.nextInt(5) == 0) {
            val index = random.nextInt(message.size)
            val anchor = message[index]
            val others = VOCAB.filter { it != anchor.word }
            message.add(index + 1, Word(others[random.nextInt(others.size)], anchor.startMs))
        }
        if (random.nextInt(5) == 0) {
            val index = random.nextInt(message.size)
            message.add(index + 1, message[index])
        }
        return message
    }

    private fun slidingWindows(sequence: List<Word>, size: Int, step: Int): List<List<Word>> {
        val windows = mutableListOf<List<Word>>()
        var start = 0
        while (true) {
            val end = minOf(start + size, sequence.size)
            windows += sequence.subList(start, end).toList()
            if (end == sequence.size) return windows
            start += step
        }
    }

    private fun assertWords(expected: List<Word>, actual: List<Word>) {
        assertEquals(format(expected), format(actual))
    }

    private fun format(words: List<Word>): String =
        words.joinToString(", ") { "${if (it.word == " ") "␠" else it.word}@${it.startMs}" }

    private fun formatMessages(messages: List<List<Word>>): String =
        messages.withIndex().joinToString("\n") { (index, words) -> "  M$index: ${format(words)}" }

    private fun w(word: String, startMs: Long) = Word(word, startMs)

    private fun sp(startMs: Long) = Word(" ", startMs)

    companion object {
        private const val SEED = 20261113L
        private const val ITERATIONS = 500
        private val VOCAB = listOf(" ", "a", "the", "different", "。", "～", "团", "小")
        private val INCREMENTS = longArrayOf(1L, 80L, 250L)

        private val U5_TEXTS = listOf(
            "So", "let", "me", "tell", "you", "about", "the", "plan", "for", "today",
            "a", "classic", "walk", "in", "the", "park", "then", "lunch", "at", "noon",
            "and", "after", "that", "we", "can", "visit", "the", "museum", "or", "try",
            "something", "a", "different", "like", "the", "river", "a", "bit", "later", "tonight"
        )

        // Words that share start_ms with the zero-duration word before them. With windows of 32
        // advancing by 3: 10/11 are inside the first window, 31/32 straddle its end (32 first
        // arrives in the second window), 36/37 first arrive together in the third window.
        private val U5_SAME_START_AS_PREVIOUS = setOf(11, 32, 37)

        private val U5_SEQUENCE: List<Word> = run {
            var startMs = 1000L
            U5_TEXTS.mapIndexed { index, text ->
                if (index > 0 && index !in U5_SAME_START_AS_PREVIOUS) startMs += 80
                Word(text, startMs)
            }
        }

        // U5's words with no shared start_ms: windows hold no same-start different words.
        private val PLAIN_SEQUENCE: List<Word> = U5_TEXTS.mapIndexed { index, text -> Word(text, 1000L + 80L * index) }
    }
}

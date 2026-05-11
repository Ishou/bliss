package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Grid
import kotlin.math.min
import kotlin.random.Random

/**
 * Default per-attempt deadline. The outer retry loop in
 * `GeneratePuzzleUseCase` retries on failure with a fresh seed, so a tight
 * per-attempt budget paired with retries is usually faster than one long
 * attempt — the pathological 5s-tail puzzles abandon quickly and the next
 * seed often succeeds in <100ms.
 */
const val DEFAULT_GENERATION_TIMEOUT_MS = 5_000L

private const val NS_PER_MS: Long = 1_000_000L

/**
 * Bitmask-CSP grid generator (Phase 1: sequential).
 *
 * Pipeline:
 *  1. Build a [Lexicon] once (cached for lifetime of this instance).
 *  2. Compute `L_useful` (longest length with adequate corpus).
 *  3. [BlackCellLayout.seed] a fresh black-cell substrate.
 *  4. Luby-scheduled restart loop:
 *     - Build [SlotRegistry] from the current layout.
 *     - Initial AC-3 over all slots.
 *     - [BitmaskCsp.search] with backtrack budget `BASE × luby(attempt)`.
 *     - On `OK` → build `Grid.fromPlacements`.
 *     - On failure → targeted perturbation at hot-slot midpoints,
 *       full re-seed every [GenerationKnobs.CONSEC_RESEED] failures.
 */
class GridGenerator(
    private val repository: WordRepository,
    private val clock: Clock = SystemClock,
) {
    // Lexicon construction can be expensive on a large French corpus; build
    // it lazily on the first `generate` call and reuse for subsequent calls.
    @Volatile private var cachedLexicon: Lexicon? = null

    private fun lexicon(): Lexicon =
        cachedLexicon
            ?: synchronized(this) {
                cachedLexicon ?: Lexicon(repository).also { cachedLexicon = it }
            }

    fun generate(
        constraints: GridConstraints,
        random: Random = Random.Default,
        metrics: GenerationMetrics? = null,
        timeoutMs: Long = DEFAULT_GENERATION_TIMEOUT_MS,
        cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
    ): Grid? {
        val w = constraints.width
        val h = constraints.height
        if (w < 2 || h < 2) return null
        val deadlineNs = clock.nanoTime() + timeoutMs * NS_PER_MS

        val lex = lexicon()
        val lUseful = lex.usefulLength
        if (lUseful < constraints.minWordLength) return null
        val lTarget = min(GenerationKnobs.DEFAULT_L_TARGET, lUseful)

        val perAttemptSec = perAttemptSeconds(w * h)
        val perAttemptNs = (perAttemptSec * 1e9).toLong()

        val layoutStart = clock.nanoTime()
        var cells =
            BlackCellLayout.seed(
                width = w,
                height = h,
                minLen = constraints.minWordLength,
                lTarget = lTarget,
                lUseful = lUseful,
                blackRatio = GenerationKnobs.DEFAULT_BLACK_RATIO,
                random = random,
            )
        metrics?.skeletonMs = (clock.nanoTime() - layoutStart) / NS_PER_MS

        val searchStart = clock.nanoTime()
        var consecFails = 0
        var attempts = 0
        var perturbations = 0

        while (attempts < GenerationKnobs.MAX_RESTARTS && clock.nanoTime() < deadlineNs) {
            attempts++
            val build = SlotRegistry.build(cells, lex, constraints.minWordLength)
            if (build == null) {
                consecFails++
                cells = reseedOrPerturb(
                    cells, w, h, constraints.minWordLength, lTarget, lUseful,
                    hotCells = emptyList(), consecFails = consecFails, random = random,
                )
                perturbations++
                continue
            }
            val acceptor = WordAcceptor(constraints.themeLimits, cooldownPolicy)
            val csp = BitmaskCsp(build.slots, lex, acceptor, clock, random)
            if (!csp.initialArcConsistency()) {
                consecFails++
                cells = reseedOrPerturb(
                    cells, w, h, constraints.minWordLength, lTarget, lUseful,
                    hotCells = emptyList(), consecFails = consecFails, random = random,
                )
                perturbations++
                continue
            }
            if (metrics?.fillFirstSlotDomainSize == -1) {
                val firstSid = csp.selectSlot()
                if (firstSid >= 0) {
                    metrics.fillFirstSlotDomainSize = lex.popcount(build.slots[firstSid].domain)
                }
            }
            val budget = GenerationKnobs.BASE_BUDGET_BACKTRACKS * luby(attempts)
            val attemptDeadline = min(deadlineNs, clock.nanoTime() + perAttemptNs)
            val result = csp.search(attemptDeadline, budget) { clock.nanoTime() > deadlineNs }
            metrics?.let { it.fillBacktracks = it.fillBacktracks + csp.backtracks }
            if (result == BitmaskCsp.Result.OK) {
                metrics?.fillMs = (clock.nanoTime() - searchStart) / NS_PER_MS
                metrics?.slotPlanMs = 0
                metrics?.slotPlanBacktracks = perturbations
                val placements = SlotRegistry.toPlacements(build.slots)
                if (placements.any { it.word.text.length < constraints.minWordLength }) return null
                return try {
                    val grid = Grid.fromPlacements(w, h, placements)
                    metrics?.succeeded = true
                    metrics?.attempts = attempts
                    grid
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            // Restart: perturb the layout toward easier topology.
            val hot = csp.hotSlotMiddleCells(GenerationKnobs.HOT_SLOTS_FOR_PERTURB)
            consecFails++
            cells = reseedOrPerturb(
                cells, w, h, constraints.minWordLength, lTarget, lUseful,
                hotCells = hot, consecFails = consecFails, random = random,
            )
            perturbations++
        }
        metrics?.fillMs = (clock.nanoTime() - searchStart) / NS_PER_MS
        metrics?.slotPlanBacktracks = perturbations
        metrics?.attempts = attempts
        return null
    }

    private fun reseedOrPerturb(
        cells: CellArray,
        w: Int,
        h: Int,
        minLen: Int,
        lTarget: Int,
        lUseful: Int,
        hotCells: List<Pair<Int, Int>>,
        consecFails: Int,
        random: Random,
    ): CellArray {
        if (consecFails > 0 && consecFails % GenerationKnobs.CONSEC_RESEED == 0) {
            return BlackCellLayout.seed(
                width = w,
                height = h,
                minLen = minLen,
                lTarget = lTarget,
                lUseful = lUseful,
                blackRatio = GenerationKnobs.DEFAULT_BLACK_RATIO,
                random = random,
            )
        }
        BlackCellLayout.perturb(
            cells = cells,
            minLen = minLen,
            lUseful = lUseful,
            hotCells = hotCells,
            intensity = GenerationKnobs.PERTURB_INTENSITY,
            random = random,
        )
        return cells
    }
}

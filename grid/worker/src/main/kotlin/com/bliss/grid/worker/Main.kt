// `bliss-worker` CLI (ADR-0013 §7).
// The clue-generation lane is fully local (mlx-lm + LoRA, see scripts/clue_generation/).
// The worker handles ingestion, DBnary derivation, and the runtime CSV export only.
package com.bliss.grid.worker

import com.bliss.grid.worker.clues.DeriveSynonymCluesCommand
import com.bliss.grid.worker.clues.IngestClueCandidatesCommand
import com.bliss.grid.worker.dbnary.IngestDbnaryCommand
import com.bliss.grid.worker.exporter.ExportWordsCommand
import com.bliss.grid.worker.importer.ImportFrequenciesCommand
import com.bliss.grid.worker.importer.ImportGrammalecteCommand
import com.bliss.grid.worker.importer.ImportWordsCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class BlissWorker : CliktCommand(name = "bliss-worker") {
    override fun run() = Unit
}

fun main(args: Array<String>) =
    BlissWorker()
        .subcommands(
            ImportWordsCommand(),
            ImportGrammalecteCommand(),
            ImportFrequenciesCommand(),
            IngestDbnaryCommand(),
            DeriveSynonymCluesCommand(),
            IngestClueCandidatesCommand(),
            ExportWordsCommand(),
        ).main(args)

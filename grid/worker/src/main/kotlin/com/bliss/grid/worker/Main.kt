// `bliss-worker` CLI (ADR-0013 §7).
// Sub-commands: import-words (PR2), generate-clues (PR3), export-words (PR4 — ADR-0013 §7, §8).
package com.bliss.grid.worker

import com.bliss.grid.worker.clues.GenerateCluesCommand
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
            GenerateCluesCommand(),
            ExportWordsCommand(),
        )
        .main(args)

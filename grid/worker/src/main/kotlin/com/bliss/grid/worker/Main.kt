// `bliss-worker` CLI (ADR-0013 §7). PR2 ships `import-words`; `generate-clues` lands in PR3.
package com.bliss.grid.worker

import com.bliss.grid.worker.importer.ImportWordsCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class BlissWorker : CliktCommand(name = "bliss-worker") {
    override fun run() = Unit
}

fun main(args: Array<String>) = BlissWorker().subcommands(ImportWordsCommand()).main(args)

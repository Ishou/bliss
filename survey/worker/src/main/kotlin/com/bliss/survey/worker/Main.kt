package com.bliss.survey.worker

import com.bliss.survey.application.csv.StyleGuideCsvParser
import com.bliss.survey.application.csv.StyleGuideCsvWriter
import com.bliss.survey.application.filters.FilterPipeline
import com.bliss.survey.application.ports.Clock
import com.bliss.survey.application.ports.IdGenerator
import com.bliss.survey.application.usecases.ExportDatasetUseCase
import com.bliss.survey.application.usecases.IngestBatchUseCase
import com.bliss.survey.application.usecases.RecomputeTrainingWeightUseCase
import com.bliss.survey.application.usecases.RetireSaturatedItemsUseCase
import com.bliss.survey.domain.model.Tier
import com.bliss.survey.domain.routing.KCoveragePolicy
import com.bliss.survey.domain.weight.GoldWindowPolicy
import com.bliss.survey.infrastructure.language.LinguaLanguageDetector
import com.bliss.survey.infrastructure.nats.UserDeletedConsumerConfig
import com.bliss.survey.infrastructure.nats.UserRoleChangedConsumerConfig
import com.bliss.survey.infrastructure.persistence.PgMaintainerRoleRepository
import com.bliss.survey.infrastructure.persistence.PgRatingRepository
import com.bliss.survey.infrastructure.persistence.PgSurveyItemRepository
import com.bliss.survey.infrastructure.persistence.PgWordMetaRepository
import com.bliss.survey.infrastructure.persistence.SurveyDatabase
import com.fasterxml.uuid.Generators
import io.nats.client.Nats
import io.nats.client.Options
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("survey-worker")

fun main(args: Array<String>) {
    val cmd =
        args.firstOrNull() ?: run {
            log.error("event=worker_no_arguments")
            printUsage()
            exitProcess(2)
        }
    val opts = parseOptions(args.drop(1))
    val exit =
        try {
            when (cmd) {
                "--help", "-h" -> {
                    printUsage()
                    0
                }
                "--ingest-batch" -> runIngest(opts)
                "--export-dataset" -> runExportCli(opts)
                "--retire-saturated" -> runRetire()
                "--recompute-training-weights" -> runRecompute()
                "--bootstrap-consumer" -> runBootstrapConsumer()
                "--delete-consumer" -> runDeleteConsumer()
                else -> {
                    log.error("event=worker_unknown_subcommand cmd={}", cmd)
                    printUsage()
                    2
                }
            }
        } catch (e: IllegalStateException) {
            log.error("event=worker_failed reason={}", e.message)
            1
        }
    exitProcess(exit)
}

private fun printUsage() {
    log.info(
        "usage: survey-worker --ingest-batch --csv <path> --source-batch <id> [--tier mid] | " +
            "--export-dataset --output <path> [--min-ratings 2] [--since ISO] " +
            "[--auth-weight 1.0] [--anon-weight 0.5] | " +
            "--retire-saturated | --recompute-training-weights | " +
            "--bootstrap-consumer | --delete-consumer | --help",
    )
}

internal fun parseOptions(rest: List<String>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < rest.size) {
        val key = rest[i]
        require(key.startsWith("--")) { "expected --flag, got '$key'" }
        val value =
            if (i + 1 < rest.size && !rest[i + 1].startsWith("--")) {
                i += 2
                rest[i - 1]
            } else {
                i += 1
                "true"
            }
        out[key.removePrefix("--")] = value
    }
    return out
}

private fun openDataSource(): DataSource {
    val jdbcUrl = System.getenv("SURVEY_JDBC_URL") ?: error("missing env SURVEY_JDBC_URL")
    val user = System.getenv("SURVEY_DB_USER") ?: error("missing env SURVEY_DB_USER")
    val password = System.getenv("SURVEY_DB_PASSWORD") ?: error("missing env SURVEY_DB_PASSWORD")
    return SurveyDatabase.create(jdbcUrl, user, password)
}

private fun runIngest(opts: Map<String, String>): Int {
    val csv = Path.of(opts["csv"] ?: error("missing --csv"))
    val sourceBatch = opts["source-batch"] ?: error("missing --source-batch")
    val tier = Tier.valueOf((opts["tier"] ?: "mid").uppercase())
    val ds = openDataSource()
    val report = runIngest(ds, csv, sourceBatch, tier)
    log.info(
        "event=ingest_done accepted={} alreadyPresent={} rejected={}",
        report.accepted,
        report.alreadyPresent,
        report.rejected.size,
    )
    for ((line, reason) in report.rejected) {
        log.warn("event=ingest_rejected line={} reason=\"{}\"", line, reason)
    }
    return 0
}

internal fun runIngest(
    ds: DataSource,
    csv: Path,
    sourceBatch: String,
    tier: Tier,
): IngestBatchUseCase.Report =
    runBlocking {
        val items = PgSurveyItemRepository(ds)
        val clock = Clock { Instant.now() }
        val ids = IdGenerator { Generators.timeBasedEpochGenerator().generate() }
        val detector = LinguaLanguageDetector()
        val pipeline = FilterPipeline.default(detector)
        val useCase = IngestBatchUseCase(StyleGuideCsvParser(), pipeline, items, ids, clock)
        useCase.execute(Files.readAllLines(csv), sourceBatch, tier)
    }

private fun runExportCli(opts: Map<String, String>): Int {
    val out = Path.of(opts["output"] ?: error("missing --output"))
    val minRatings = opts["min-ratings"]?.toInt() ?: 2
    val since = opts["since"]?.let { Instant.parse(it) }
    val authWeight = opts["auth-weight"]?.toDouble() ?: 1.0
    val anonWeight = opts["anon-weight"]?.toDouble() ?: 0.5
    val ds = openDataSource()
    runExport(ds, out, minRatings, since, authWeight, anonWeight)
    log.info("event=export_written path={}", out)
    return 0
}

internal fun runExport(
    ds: DataSource,
    out: Path,
    minRatings: Int,
    since: Instant? = null,
    authWeight: Double = 1.0,
    anonWeight: Double = 0.5,
) {
    runBlocking {
        val items = PgSurveyItemRepository(ds)
        val ratings = PgRatingRepository(ds)
        val wordMeta = PgWordMetaRepository(ds)
        val clock = Clock { Instant.now() }
        val useCase = ExportDatasetUseCase(items, ratings, StyleGuideCsvWriter(), clock, wordMeta)
        val csv = useCase.execute(minRatings, since, authWeight, anonWeight)
        Files.writeString(out, csv)
    }
}

private fun runRetire(): Int {
    val ds = openDataSource()
    val n = runRetire(ds)
    log.info("event=retire_done count={}", n)
    return 0
}

// Idempotent; pre-install Job only. See UserDeletedConsumerConfig.bootstrap().
private fun runBootstrapConsumer(): Int =
    withNatsConnection { conn ->
        UserDeletedConsumerConfig.bootstrap(conn)
        UserRoleChangedConsumerConfig.bootstrap(conn)
        log.info(
            "event=consumer_bootstrap_done stream={} durables={},{}",
            UserDeletedConsumerConfig.STREAM_NAME,
            UserDeletedConsumerConfig.DURABLE_NAME,
            UserRoleChangedConsumerConfig.DURABLE_NAME,
        )
        0
    }

private fun runRecompute(): Int {
    val ds = openDataSource()
    val cutoff = System.getenv("SURVEY_GOLD_CUTOFF")?.let(Instant::parse) ?: Instant.parse("2026-05-30T00:00:00Z")
    val multiplier = System.getenv("SURVEY_GOLD_MULTIPLIER")?.toDouble() ?: 3.0
    runBlocking {
        val items = PgSurveyItemRepository(ds)
        val roles = PgMaintainerRoleRepository(ds)
        RecomputeTrainingWeightUseCase(roles, items, GoldWindowPolicy(cutoff, multiplier)).recomputeAll()
    }
    log.info("event=recompute_training_weights_done")
    return 0
}

// Destructive; NOT wired into any chart hook — explicit operator action for immutable-field migrations.
private fun runDeleteConsumer(): Int =
    withNatsConnection { conn ->
        UserDeletedConsumerConfig.deleteConsumer(conn)
        log.info(
            "event=consumer_deleted stream={} durable={}",
            UserDeletedConsumerConfig.STREAM_NAME,
            UserDeletedConsumerConfig.DURABLE_NAME,
        )
        0
    }

private inline fun withNatsConnection(block: (io.nats.client.Connection) -> Int): Int {
    val natsUrl = System.getenv("NATS_URL") ?: error("missing env NATS_URL")
    val conn =
        Nats.connect(
            Options
                .Builder()
                .server(natsUrl)
                .connectionTimeout(Duration.ofSeconds(10))
                .build(),
        )
    try {
        return block(conn)
    } finally {
        conn.close()
    }
}

internal fun runRetire(ds: DataSource): Int =
    runBlocking {
        val items = PgSurveyItemRepository(ds)
        val clock = Clock { Instant.now() }
        val useCase = RetireSaturatedItemsUseCase(items, KCoveragePolicy.DEFAULT, clock)
        useCase.execute()
    }

package com.decisionrail.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backup and restore scripts' decisions, as the shell functions they actually call, plus the
 * orchestration around them with a fake {@code docker} that records what would have been destroyed.
 *
 * <h2>What went wrong before</h2>
 * {@code backup.sh} summed a Kafka CLI's "LAG" column with {@code awk}, turning an error message, an
 * empty answer, a header, or a {@code -} into zero and dumping. Reproduced here with the v0.10.1
 * script: five such inputs each exited 0, announced zero lag, ran the dump and wrote
 * {@code consumerLagAtSnapshot: 0}. And {@code restore.sh} warned about a missing manifest and then
 * renamed the live database and deleted the broker volume anyway.
 *
 * <h2>What holds now</h2>
 * Coherence is a single-row answer from the database - published events, how many lack each consumer
 * group's durable record, and the pending, claimed and failed counts - and anything that is not
 * exactly that is <em>unverified</em>, which is never coherent. A manifest is validated for format,
 * version, fields, its coherence claim and its checksum against the dump, and restore then
 * re-establishes coherence on the staged data before the first destructive step.
 */
class RecoveryCoherenceGuardTest {
    @TempDir Path sandbox;

    // ----- assess_coherence: the only input shapes that mean anything -----

    @ParameterizedTest(name = "[{index}] {2}")
    @CsvSource(delimiter = ';', textBlock = """
        '';                                                    2; empty answer
        'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG'; 2; a header and no data
        'ERROR:  relation "outbox_events" does not exist';    2; a database error message
        'psql: error: connection to server timed out';         2; a timeout message
        '-|-|-|0|0|0';                                          2; unknown values rendered as dashes
        '5|0';                                                  2; incomplete columns
        '5|x|0|0|0|0';                                          2; a malformed number
        '5|-1|0|0|0|0';                                         2; a negative count
        '5|6|0|0|0|0';                                          2; more missing than published (not self-consistent)
        '5|0|0|0|0|0\n5|0|0|0|0|0';                             2; two rows where one was asked for
        '5|1|0|0|0|0';                                          1; one published event without the projection receipt
        '5|0|2|0|0|0';                                          1; two without the shadow receipt
        '5|3|3|1|0|0';                                          1; several missing for both groups
        '5|0|0|2|1|0';                                          0; complete and coherent, with pending and claimed events
        '5|0|0|0|0|2';                                          0; complete and coherent, with terminally failed events
        '0|0|0|0|0|0';                                          0; an empty environment - nothing published yet
        """)
    void assessesOnlyACompleteSelfConsistentAnswerAsCoherent(String raw, int expectedExit, String meaning) throws Exception {
        Result result = shell("source \"$LIB\" && assess_coherence \"$1\"", raw.replace("\\n", "\n"));
        assertThat(result.exitCode()).as("%s -> %s", meaning, result.stdout()).isEqualTo(expectedExit);
        String verdict = result.stdout().trim();
        switch (expectedExit) {
            case 0 -> assertThat(verdict).startsWith("COHERENT published=");
            case 1 -> assertThat(verdict).startsWith("INCOHERENT published=").contains("missing_projection=").contains("missing_shadow=");
            default -> assertThat(verdict).startsWith("UNVERIFIED");
        }
        // Only one line, ever: the callers parse it.
        assertThat(result.stdout().trim().lines().count()).isEqualTo(1);
    }

    @Test
    void anEmptyEnvironmentIsReportedAsSuchNotAsAnUnreadOne() throws Exception {
        Result result = shell("source \"$LIB\" && assess_coherence \"$1\"", "0|0|0|0|0|0");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().trim()).isEqualTo("COHERENT published=0 pending=0 claimed=0 failed=0");
        // And an unread one is not zero.
        assertThat(shell("source \"$LIB\" && assess_coherence \"$1\"", "").exitCode()).isEqualTo(2);
    }

    // ----- validate_manifest -----

    @Test
    void aManifestIsCheckedForFormatVersionFieldsClaimAndChecksum() throws Exception {
        Path dump = sandbox.resolve("good.dump");
        Files.writeString(dump, "PGDMP-fixture-bytes");
        String sha = sha256(dump);
        String valid = """
                {"version":1,"recoveryPoint":"20260917T000000Z","schema":"V15","image":"x:sha-%s","dumpSha256":"%s",
                 "coherence":{"method":"durable-receipts","verified":true,"publishedEvents":14},
                 "pendingOutboxEventsAtSnapshot":0}""".formatted("0".repeat(40), sha);

        assertThat(validate(dump, null)).startsWith("INVALID no manifest");
        assertThat(validate(dump, "not json at all")).startsWith("INVALID manifest is not JSON");
        assertThat(validate(dump, valid.replace("\"version\":1", "\"version\":2"))).startsWith("INVALID unsupported manifest version");
        assertThat(validate(dump, valid.replace("\"dumpSha256\":\"" + sha + "\",", ""))).startsWith("INVALID manifest lacks required field(s): dumpSha256");
        assertThat(validate(dump, valid.replace("\"publishedEvents\":14", "\"publishedEvents\":\"14\""))).startsWith("INVALID manifest field(s) have the wrong type");
        assertThat(validate(dump, valid.replace("durable-receipts", "kafka-lag"))).startsWith("INVALID unknown coherence method");
        assertThat(validate(dump, valid.replace("\"verified\":true", "\"verified\":false"))).startsWith("INVALID manifest does not claim verified coherence");
        assertThat(validate(dump, valid.replace(sha, "f".repeat(64)))).startsWith("INVALID dump checksum").contains("different dump");
        assertThat(validate(dump, valid)).isEqualTo("VALID recoveryPoint=20260917T000000Z schema=V15 publishedEvents=14 pending=0");
    }

    // ----- orchestration, with a fake docker that records the destructive steps -----

    @Test
    void backupRefusesWithoutDumpingWhenCoherenceIsUnverifiedOrMissing() throws Exception {
        for (String answer : List.of(
                "ERROR: could not connect to server",     // the observation itself failed
                "",                                        // nothing came back
                "3|1|0|0|0|0")) {                          // a published event without its receipt
            Fixture fixture = fixture("backup.sh", Map.of("COHERENCE", answer));
            Result result = fixture.run(Map.of("BACKUP_QUIESCE_SECONDS", "0"));
            assertThat(result.exitCode()).as("answer %s", answer).isEqualTo(1);
            assertThat(result.stderr()).contains("Refusing").contains("No dump or manifest was written");
            assertThat(fixture.logText()).as("no dump for %s", answer).doesNotContain("pg_dump");
            assertThat(Files.list(fixture.backups()).count()).isZero();
            // The application is left running: the refusal's last act is to start it again.
            assertThat(fixture.logText()).endsWith("start app\n");
        }
    }

    @Test
    void backupDumpsAndWritesAVerifiedManifestOnlyForACoherentAnswer() throws Exception {
        Fixture fixture = fixture("backup.sh", Map.of("COHERENCE", "14|0|0|2|0|1"));
        Result result = fixture.run(Map.of());
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("COHERENT published=14 pending=2 claimed=0 failed=1");
        assertThat(fixture.logText()).contains("pg_dump").endsWith("start app\n");
        Path manifest = Files.list(fixture.backups()).filter(p -> p.toString().endsWith(".manifest.json")).findFirst().orElseThrow();
        String json = Files.readString(manifest);
        assertThat(json).contains("\"verified\": true").contains("\"publishedEvents\": 14").contains("\"pendingOutboxEventsAtSnapshot\": 2");
        // The manifest names the dump it belongs to, by its bytes.
        Path dump = Path.of(manifest.toString().replace(".manifest.json", ""));
        assertThat(json).contains(sha256(dump));
    }

    @Test
    void restoreRefusesBeforeAnyDestructiveStepUnlessTheSnapshotIsVerified() throws Exception {
        // A manifestless dump: refused before the application is even stopped.
        Fixture noManifest = fixture("restore.sh", Map.of("COHERENCE", "5|0|0|0|0|0"));
        Path legacy = noManifest.root().resolve("legacy.dump");
        Files.writeString(legacy, "PGDMP-legacy");
        Result result = noManifest.run(Map.of(), legacy.toString());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("no manifest").contains("--legacy-dump").contains("the application was not stopped");
        assertThat(noManifest.logText()).doesNotContain("stop app").doesNotContain("ALTER DATABASE").doesNotContain("BROKER");

        // A manifest that does not belong to this dump.
        Fixture wrongManifest = fixture("restore.sh", Map.of("COHERENCE", "5|0|0|0|0|0"));
        Path dump = wrongManifest.root().resolve("backup.dump");
        Files.writeString(dump, "PGDMP-bytes");
        Files.writeString(Path.of(dump + ".manifest.json"), manifestFor("f".repeat(64), 5));
        result = wrongManifest.run(Map.of(), dump.toString());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("checksum").contains("different dump");
        assertThat(wrongManifest.logText()).doesNotContain("stop app").doesNotContain("ALTER DATABASE").doesNotContain("BROKER");

        // A valid manifest whose dump, once staged, holds a published event without its receipt: the
        // live database and the broker are left alone and the application stays stopped.
        Fixture incoherent = fixture("restore.sh", Map.of("COHERENCE", "5|1|0|0|0|0"));
        dump = incoherent.root().resolve("backup.dump");
        Files.writeString(dump, "PGDMP-bytes");
        Files.writeString(Path.of(dump + ".manifest.json"), manifestFor(sha256(dump), 5));
        result = incoherent.run(Map.of(), dump.toString());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("not coherent").contains("missing_projection=1").contains("application is STOPPED");
        assertThat(incoherent.logText()).contains("stop app").contains("pg_restore")
                .doesNotContain("ALTER DATABASE").doesNotContain("BROKER");
        assertThat(incoherent.logText()).contains("DROP DATABASE IF EXISTS decisionrail_restore");

        // The same dump under --legacy-dump: the staging check still decides.
        Fixture legacyIncoherent = fixture("restore.sh", Map.of("COHERENCE", "5|0|1|0|0|0"));
        legacy = legacyIncoherent.root().resolve("legacy.dump");
        Files.writeString(legacy, "PGDMP-legacy");
        result = legacyIncoherent.run(Map.of(), legacy.toString(), "--legacy-dump");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("not coherent").contains("missing_shadow=1");
        assertThat(legacyIncoherent.logText()).doesNotContain("ALTER DATABASE").doesNotContain("BROKER");

        // A manifest that disagrees with the staged data about how much was published.
        Fixture mismatch = fixture("restore.sh", Map.of("COHERENCE", "7|0|0|0|0|0"));
        dump = mismatch.root().resolve("backup.dump");
        Files.writeString(dump, "PGDMP-bytes");
        Files.writeString(Path.of(dump + ".manifest.json"), manifestFor(sha256(dump), 5));
        result = mismatch.run(Map.of(), dump.toString());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("manifest says 5").contains("restored data has 7");
        assertThat(mismatch.logText()).doesNotContain("ALTER DATABASE").doesNotContain("BROKER");
    }

    @Test
    void restoreProceedsToReplacementAndBrokerResetOnlyAfterStagedCoherence() throws Exception {
        Fixture verified = fixture("restore.sh", Map.of("COHERENCE", "5|0|0|1|0|0"));
        Path dump = verified.root().resolve("backup.dump");
        Files.writeString(dump, "PGDMP-bytes");
        Files.writeString(Path.of(dump + ".manifest.json"), manifestFor(sha256(dump), 5));
        Result result = verified.run(Map.of(), dump.toString());
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        String log = verified.logText();
        // Staging and its coherence check come before the swap, which comes before the broker reset,
        // and nothing starts the application.
        assertThat(log.indexOf("pg_restore")).isLessThan(log.indexOf("ALTER DATABASE"));
        assertThat(log.indexOf("ALTER DATABASE")).isLessThan(log.indexOf("BROKER REMOVED"));
        assertThat(log.indexOf("BROKER REMOVED")).isLessThan(log.indexOf("BROKER VOLUME DELETED"));
        assertThat(log).doesNotContain("start app").doesNotContain("up --detach --remove-orphans");
        assertThat(result.stdout()).contains("STOPPED and was not started");
    }

    // ----- fixture -----

    private record Fixture(Path root, Path log, Path backups) {
        Result run(Map<String, String> environment, String... arguments) throws Exception {
            List<String> command = new java.util.ArrayList<>(List.of("bash", root.resolve("deploy/bin/" + scriptName()).toString()));
            command.addAll(List.of(arguments));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(root.toFile());
            builder.environment().put("PATH", root.resolve("bin") + ":/usr/bin:/bin:/usr/sbin:/sbin");
            builder.environment().put("DEPLOY_ENV_FILE", root.resolve("deploy/public.env").toString());
            builder.environment().put("R7_LOG", log.toString());
            builder.environment().putAll(environment);
            Path out = root.resolve("stdout"), err = root.resolve("stderr");
            builder.redirectOutput(out.toFile()); builder.redirectError(err.toFile());
            // Restore asks for the project name; supplying it is what lets the orchestration be observed.
            builder.redirectInput(ProcessBuilder.Redirect.from(root.resolve("confirm").toFile()));
            Process process = builder.start();
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the script must not hang").isTrue();
            return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
        }
        String scriptName() throws IOException { return Files.readString(root.resolve("script-name")).trim(); }
        String logText() throws IOException { return Files.exists(log) ? Files.readString(log) : ""; }
    }

    private record Result(int exitCode, String stdout, String stderr) {}

    /**
     * A copy of the real script and library beside a fake {@code docker} that answers the coherence
     * query with a fixed line, records every destructive step it is asked for, and does nothing else.
     */
    private Fixture fixture(String script, Map<String, String> answers) throws IOException {
        Path root = Files.createTempDirectory(sandbox, script.replace(".sh", "-"));
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path deployBin = Files.createDirectories(root.resolve("deploy/bin"));
        Path backups = Files.createDirectories(root.resolve("deploy/backups"));
        for (String file : List.of("lib.sh", script)) {
            Files.copy(Path.of("deploy", "bin", file), deployBin.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(root.resolve("deploy/public.env"), "DEMO_HOST=localhost\nAPP_IMAGE=x:sha-" + "0".repeat(40) + "\n");
        Files.writeString(root.resolve("script-name"), script);
        Files.writeString(root.resolve("confirm"), "decisionrail-public\n");
        Path log = root.resolve("docker.log");
        Path docker = bin.resolve("docker");
        Files.writeString(docker, """
                #!/usr/bin/env bash
                # A recording fake. The coherence query is answered with the fixture's line; every step
                # that would change something is logged; nothing real happens.
                args="$*"
                case "$args" in
                  *"config --format json"*) echo '{"name":"decisionrail-public"}';;
                  *"psql"*"outbox_events e WHERE e.status"*) printf '%s\\n' "$COHERENCE_ANSWER";;
                  *"psql"*"flyway_schema_history"*) echo "15";;
                  *"psql"*"status IN ('PENDING', 'CLAIMED')"*) echo "1";;
                  *"psql"*"DROP DATABASE IF EXISTS decisionrail_restore"*) echo "DROP DATABASE IF EXISTS decisionrail_restore" >> "$R7_LOG";;
                  *"psql"*"ALTER DATABASE"*) echo "ALTER DATABASE (live database swapped)" >> "$R7_LOG";;
                  *"psql"*) :;;
                  *"pg_dump"*) echo "PGDMP-stub"; echo "pg_dump" >> "$R7_LOG";;
                  *"pg_restore"*) cat >/dev/null; echo "pg_restore" >> "$R7_LOG";;
                  *"stop app"*) echo "stop app" >> "$R7_LOG";;
                  *"start app"*) echo "start app" >> "$R7_LOG";;
                  *"rm --stop --force broker"*) echo "BROKER REMOVED" >> "$R7_LOG";;
                  "volume rm"*) echo "BROKER VOLUME DELETED" >> "$R7_LOG";;
                  *"up --detach --wait broker"*) echo "broker recreated" >> "$R7_LOG";;
                  *"up --detach --remove-orphans"*) echo "up --detach --remove-orphans" >> "$R7_LOG";;
                  *) :;;
                esac
                """.replace("$COHERENCE_ANSWER", answers.getOrDefault("COHERENCE", "")));
        docker.toFile().setExecutable(true);
        return new Fixture(root, log, backups);
    }

    private String validate(Path dump, String manifestContent) throws Exception {
        Path manifest = Path.of(dump + ".manifest.json");
        if (manifestContent == null) Files.deleteIfExists(manifest); else Files.writeString(manifest, manifestContent);
        return shell("source \"$LIB\" && validate_manifest \"$1\" \"$2\"", dump.toString(), manifest.toString()).stdout().trim();
    }

    private static String manifestFor(String sha, int published) {
        return """
                {"version":1,"recoveryPoint":"20260917T000000Z","schema":"V15","image":"x:sha-%s","dumpSha256":"%s",
                 "coherence":{"method":"durable-receipts","verified":true,"publishedEvents":%d},
                 "pendingOutboxEventsAtSnapshot":1}""".formatted("0".repeat(40), sha, published);
    }

    private Result shell(String script, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("bash", "-c", script, "guard"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("LIB", Path.of("deploy", "bin", "lib.sh").toAbsolutePath().toString());
        Path out = Files.createTempFile(sandbox, "out", ".txt");
        Path err = Files.createTempFile(sandbox, "err", ".txt");
        builder.redirectOutput(out.toFile()); builder.redirectError(err.toFile());
        Process process = builder.start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}

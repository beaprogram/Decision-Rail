package com.decisionrail.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking {@code scripts/async-demo.sh} a question must not perform an action.
 *
 * <h2>Why this exists</h2>
 * The script took no arguments and ignored the ones it was given, so {@code --help} ran the demo:
 * it stopped the configured broker container and created synthetic payments against whichever stack
 * the environment pointed at. That happened during this project's own development, against the
 * development stack, from a stray {@code --help}.
 *
 * <h2>How it is checked, without running the demo</h2>
 * The script is copied into a temporary directory laid out like the repository, beside a fake
 * {@code .env} whose only content creates a marker file. Every operational command it could reach —
 * {@code docker}, {@code curl}, {@code psql}, {@code jq}, {@code openssl}, {@code mktemp} — is
 * shadowed on {@code PATH} by a stub that records the call and fails. Nothing here can touch a real
 * container, database or credential.
 *
 * <p>So a passing run means the help and invalid-argument paths reached neither the environment file
 * nor any command. A regression that moved the argument block below the {@code .env} source, or that
 * dropped it, fails on the marker rather than on a message.
 */
class AsyncDemoArgumentSafetyTest {
    @TempDir Path sandbox;

    private Path script;
    private Path markers;
    private Path stubs;

    @BeforeEach
    void buildSandbox() throws IOException {
        Path projectRoot = sandbox.resolve("project");
        Path scripts = Files.createDirectories(projectRoot.resolve("scripts"));
        markers = Files.createDirectories(sandbox.resolve("markers"));
        stubs = Files.createDirectories(sandbox.resolve("bin"));

        script = scripts.resolve("async-demo.sh");
        Files.copy(Path.of("scripts", "async-demo.sh"), script, StandardCopyOption.REPLACE_EXISTING);
        script.toFile().setExecutable(true);

        // Sourcing this leaves a trace. It also sets the credentials the script requires, so a run
        // that got this far would continue rather than stopping for a missing password - the marker,
        // not a later failure, is what this test reads.
        Files.writeString(projectRoot.resolve(".env"), """
                MERCHANT_DEMO_PASSWORD=sandbox-not-a-real-credential
                ADMIN_PASSWORD=sandbox-not-a-real-credential
                touch "%s/env-sourced"
                """.formatted(markers));

        for (String command : List.of("docker", "curl", "jq", "openssl", "psql", "mktemp", "sleep")) {
            Path stub = stubs.resolve(command);
            Files.writeString(stub, """
                    #!/usr/bin/env bash
                    printf '%s %%s\\n' "$*" >> "%s/commands"
                    exit 97
                    """.formatted(command, markers));
            stub.toFile().setExecutable(true);
        }
    }

    @Test
    void helpPrintsUsageAndTouchesNothing() throws Exception {
        for (String flag : List.of("--help", "-h")) {
            Result result = run(flag);

            assertThat(result.exitCode()).as("%s is a successful question, not a refused command", flag).isZero();
            assertThat(result.stdout())
                    .contains("Usage: scripts/async-demo.sh")
                    .contains("takes no arguments")
                    // The warning the incident would have needed.
                    .contains("CHANGES THE STACK IT RUNS AGAINST")
                    .contains("stops the selected broker")
                    .contains("creates synthetic payments")
                    .contains("BROKER_SERVICE")
                    .contains("COMPOSE_FILE_PATH");
            assertNothingHappened(flag);
        }
    }

    @Test
    void anUnknownArgumentFailsAndTouchesNothing() throws Exception {
        Result result = run("--dry-run");

        assertThat(result.exitCode()).as("a misunderstood invocation must not look like success").isEqualTo(2);
        assertThat(result.stderr())
                .contains("Unrecognised argument: --dry-run")
                .contains("takes no arguments")
                .contains("Usage: scripts/async-demo.sh");
        assertNothingHappened("--dry-run");
    }

    @Test
    void anUnsupportedCombinationFailsAndTouchesNothing() throws Exception {
        Result result = run("--help", "--force");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("cannot be combined");
        assertNothingHappened("--help --force");
    }

    /**
     * The one positive control: with no arguments the script proceeds as before, reaching the
     * environment file and then the stubbed dependencies. Without this, every assertion above would
     * also pass against a script that simply exited at the top for every input.
     */
    @Test
    void withNoArgumentsItStillProceedsPastTheArgumentBlock() throws Exception {
        Result result = run();

        assertThat(Files.exists(markers.resolve("env-sourced")))
                .as("the ordinary invocation is unchanged and still reads .env").isTrue();
        assertThat(result.exitCode()).as("it then fails on the stubbed dependencies, which is expected here")
                .isNotZero();
    }

    private void assertNothingHappened(String invocation) throws IOException {
        assertThat(Files.exists(markers.resolve("env-sourced")))
                .as("%s must not source .env: credentials are not needed to answer a question", invocation)
                .isFalse();
        Path commands = markers.resolve("commands");
        assertThat(Files.exists(commands) ? Files.readString(commands) : "")
                .as("%s must not run docker, curl, psql, jq, openssl or mktemp", invocation)
                .isEmpty();
    }

    private Result run(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", script.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        environment.clear();
        // The stub directory comes first, so every command that could reach a container, a database,
        // an HTTP endpoint or a temporary resource resolves to a stub that records the call and fails.
        // The ordinary system paths follow it, because printing usage legitimately needs cat and the
        // fixture's own marker needs touch; shadowing the dangerous commands is what matters, not
        // starving the shell.
        environment.put("PATH", stubs + ":/usr/bin:/bin:/usr/sbin:/sbin");
        Path out = sandbox.resolve("stdout");
        Path err = sandbox.resolve("stderr");
        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        Process process = builder.start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the script must not hang").isTrue();
        return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}

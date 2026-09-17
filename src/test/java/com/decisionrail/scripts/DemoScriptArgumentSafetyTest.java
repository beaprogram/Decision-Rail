package com.decisionrail.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asking any demo script a question must not perform an action.
 *
 * <h2>Why this exists</h2>
 * The scripts took no arguments and ignored the ones they were given, so {@code --help} ran the demo.
 * For {@code async-demo.sh} that meant stopping the configured broker container and creating
 * synthetic payments against whichever stack the environment pointed at - which happened to this
 * project's own development stack, from a stray {@code --help}. The other three had the same shape
 * and the same hazard: payments created, an account's balance skewed, or an entire Compose stack built.
 *
 * <h2>How it is checked, without running any demo</h2>
 * Each script is copied into a temporary directory laid out like the repository, beside a fake
 * {@code .env} whose only content creates a marker file. Every operational command a script could
 * reach - {@code docker}, {@code curl}, {@code psql}, {@code jq}, {@code openssl}, {@code mktemp} - is
 * shadowed on {@code PATH} by a stub that records the call and fails. Nothing here can touch a real
 * container, database or credential.
 *
 * <p>A passing run means the help and invalid-argument paths reached neither the environment file
 * nor any command. The no-argument case is the positive control: the script must still get past the
 * argument block and into its real work, which for three of them is sourcing {@code .env} and for
 * {@code recovery-demo.sh} - which reads no {@code .env} - is its first {@code openssl} call. Without
 * that control the other cases would also pass against a script that exited at the top for everything.
 */
class DemoScriptArgumentSafetyTest {
    private static final List<String> STUBBED = List.of("docker", "curl", "jq", "openssl", "psql", "mktemp", "sleep");

    @TempDir Path sandbox;

    @ParameterizedTest(name = "{0} --help")
    @ValueSource(strings = {"demo.sh", "lifecycle-demo.sh", "async-demo.sh", "recovery-demo.sh"})
    void helpPrintsUsageAndTouchesNothing(String script) throws Exception {
        Sandbox box = sandbox(script);
        for (String flag : List.of("--help", "-h")) {
            Result result = box.run(flag);
            assertThat(result.exitCode()).as("%s %s is a successful question, not a refused command", script, flag).isZero();
            assertThat(result.stdout())
                    .contains("Usage: scripts/" + script)
                    .contains("takes no arguments")
                    // Every script says what a real run does to the stack it targets.
                    .containsIgnoringCase("this script")
                    .contains("Environment:");
            box.assertNothingHappened(script + " " + flag);
        }
    }

    @ParameterizedTest(name = "{0} unknown argument")
    @ValueSource(strings = {"demo.sh", "lifecycle-demo.sh", "async-demo.sh", "recovery-demo.sh"})
    void anUnknownArgumentFailsAndTouchesNothing(String script) throws Exception {
        Sandbox box = sandbox(script);
        Result result = box.run("--dry-run");
        assertThat(result.exitCode()).as("a misunderstood invocation must not look like success").isEqualTo(2);
        assertThat(result.stderr())
                .contains("Unrecognised argument: --dry-run")
                .contains("takes no arguments")
                .contains("Usage: scripts/" + script);
        box.assertNothingHappened(script + " --dry-run");
    }

    @ParameterizedTest(name = "{0} --help with extra")
    @ValueSource(strings = {"demo.sh", "lifecycle-demo.sh", "async-demo.sh", "recovery-demo.sh"})
    void anUnsupportedCombinationFailsAndTouchesNothing(String script) throws Exception {
        Sandbox box = sandbox(script);
        Result result = box.run("--help", "--force");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("cannot be combined");
        box.assertNothingHappened(script + " --help --force");
    }

    @ParameterizedTest(name = "{0} no arguments still runs")
    @ValueSource(strings = {"demo.sh", "lifecycle-demo.sh", "async-demo.sh", "recovery-demo.sh"})
    void withNoArgumentsItStillProceedsPastTheArgumentBlock(String script) throws Exception {
        Sandbox box = sandbox(script);
        Result result = box.run();
        if (script.equals("recovery-demo.sh")) {
            // Reads no .env; its first act of real work is naming the run with openssl, which the stub
            // records and refuses.
            assertThat(box.recordedCommands()).as("recovery-demo.sh reached its first real command").contains("openssl");
        } else {
            assertThat(Files.exists(box.markers.resolve("env-sourced")))
                    .as("%s still reads .env when invoked normally", script).isTrue();
        }
        assertThat(result.exitCode()).as("it then fails on the stubbed dependencies, which is expected here").isNotZero();
    }

    // ----- fixture -----

    private Sandbox sandbox(String script) throws IOException {
        Path root = Files.createDirectories(sandbox.resolve(script.replace(".sh", "")));
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Path scripts = Files.createDirectories(projectRoot.resolve("scripts"));
        Path markers = Files.createDirectories(root.resolve("markers"));
        Path stubs = Files.createDirectories(root.resolve("bin"));

        Path copy = scripts.resolve(script);
        Files.copy(Path.of("scripts", script), copy, StandardCopyOption.REPLACE_EXISTING);
        copy.toFile().setExecutable(true);

        // Sourcing this leaves a trace. It also supplies the credentials the scripts require, so a
        // run that got this far continues rather than stopping for a missing password: the marker,
        // not a later failure, is what the tests read.
        Files.writeString(projectRoot.resolve(".env"), """
                MERCHANT_DEMO_PASSWORD=sandbox-not-a-real-credential
                ADMIN_PASSWORD=sandbox-not-a-real-credential
                touch "%s/env-sourced"
                """.formatted(markers));
        for (String command : STUBBED) {
            Path stub = stubs.resolve(command);
            Files.writeString(stub, """
                    #!/usr/bin/env bash
                    printf '%s %%s\\n' "$*" >> "%s/commands"
                    exit 97
                    """.formatted(command, markers));
            stub.toFile().setExecutable(true);
        }
        return new Sandbox(copy, markers, stubs, root);
    }

    private record Sandbox(Path script, Path markers, Path stubs, Path root) {
        Result run(String... arguments) throws Exception {
            List<String> command = new ArrayList<>(List.of("bash", script.toString()));
            command.addAll(List.of(arguments));
            ProcessBuilder builder = new ProcessBuilder(command);
            Map<String, String> environment = builder.environment();
            environment.clear();
            // The stub directory comes first, so every command that could reach a container, a
            // database, an HTTP endpoint or a temporary resource resolves to a stub that records the
            // call and fails. The ordinary system paths follow, because printing usage legitimately
            // needs cat and the fixture's own marker needs touch; shadowing the dangerous commands is
            // what matters, not starving the shell.
            environment.put("PATH", stubs + ":/usr/bin:/bin:/usr/sbin:/sbin");
            Path out = root.resolve("stdout");
            Path err = root.resolve("stderr");
            builder.redirectOutput(out.toFile());
            builder.redirectError(err.toFile());
            Process process = builder.start();
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the script must not hang").isTrue();
            return new Result(process.exitValue(), Files.readString(out), Files.readString(err));
        }

        String recordedCommands() throws IOException {
            Path commands = markers.resolve("commands");
            return Files.exists(commands) ? Files.readString(commands) : "";
        }

        void assertNothingHappened(String invocation) throws IOException {
            assertThat(Files.exists(markers.resolve("env-sourced")))
                    .as("%s must not source .env: credentials are not needed to answer a question", invocation)
                    .isFalse();
            assertThat(recordedCommands())
                    .as("%s must not run docker, curl, psql, jq, openssl or mktemp", invocation)
                    .isEmpty();
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {}
}

package com.decisionrail.deploy;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployment scripts' image-pin guard, as the shell function the scripts actually call.
 *
 * <p>The documented guarantee is that a deployment names a revision. An earlier guard rejected a
 * short list of tags (`latest`, `main`, a placeholder) and accepted anything else - `stable` walked
 * through it - which is a guarantee about three strings, not about revisions. The guard now accepts
 * exactly the forms that name one: a `sha-<full commit>` tag, a `@sha256:` digest, or both, and
 * refuses everything else, including the release's own version tag, which is a convenience name and
 * not a pin.
 */
class ImagePinGuardTest {

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {
            "ghcr.io/beaprogram/decision-rail:sha-19770842ab47837fbf0e035c0ae1964b840c4583",
            "ghcr.io/beaprogram/decision-rail@sha256:bfc7f868307d9ed7bc227af4908f33a0fbb930e00f46f8a6d63035bfc3d92b52",
            "ghcr.io/beaprogram/decision-rail:sha-19770842ab47837fbf0e035c0ae1964b840c4583@sha256:bfc7f868307d9ed7bc227af4908f33a0fbb930e00f46f8a6d63035bfc3d92b52",
            "local/decision-rail:sha-0000000000000000000000000000000000000000"
    })
    void acceptsAReferenceThatNamesARevision(String image) throws Exception {
        assertThat(guard(image)).as("%s names a revision", image).isZero();
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
            "ghcr.io/beaprogram/decision-rail:latest",
            "ghcr.io/beaprogram/decision-rail:main",
            "ghcr.io/beaprogram/decision-rail:stable",
            "ghcr.io/beaprogram/decision-rail:v0.10.0",
            "ghcr.io/beaprogram/decision-rail:sha-1977084",
            "ghcr.io/beaprogram/decision-rail:sha-19770842ab47837fbf0e035c0ae1964b840c458X",
            "ghcr.io/beaprogram/decision-rail",
            "ghcr.io/beaprogram/decision-rail:stable@sha256:bfc7f868307d9ed7bc227af4908f33a0fbb930e00f46f8a6d63035bfc3d92b52",
            "ghcr.io/beaprogram/decision-rail@sha256:notadigest",
            "ghcr.io/beaprogram/decision-rail:REPLACE-WITH-sha-TAG"
    })
    void refusesAnythingMutableOrMalformed(String image) throws Exception {
        assertThat(guard(image)).as("%s must be refused", image).isNotZero();
    }

    /** Runs the real function from deploy/bin/lib.sh and answers its exit status. */
    private static int guard(String image) throws Exception {
        Process process = new ProcessBuilder(List.of("bash", "-c",
                "source " + Path.of("deploy", "bin", "lib.sh").toAbsolutePath() + " && require_pinned_image \"$1\"",
                "guard", image))
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return process.exitValue();
    }
}

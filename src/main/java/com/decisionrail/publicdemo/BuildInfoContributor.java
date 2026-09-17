package com.decisionrail.publicdemo;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * Identifies the running revision, and nothing else.
 *
 * <p>{@code GET /actuator/info} is public so that anyone looking at the demo can tell which commit and
 * image they are looking at - the same identity the release notes and the CI run name - without being
 * told anything about how the instance is configured. The values arrive as environment variables set
 * by the image build and the deployment; an instance started without them says so rather than
 * guessing.
 */
@Component
public class BuildInfoContributor implements InfoContributor {
    private final String commit;
    private final String image;
    private final String builtAt;

    public BuildInfoContributor(@Value("${app.build.commit:}") String commit,
                                @Value("${app.build.image:}") String image,
                                @Value("${app.build.built-at:}") String builtAt) {
        this.commit = commit;
        this.image = image;
        this.builtAt = builtAt;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("commit", commit.isBlank() ? "unknown" : commit);
        build.put("image", image.isBlank() ? "unknown" : image);
        build.put("builtAt", builtAt.isBlank() ? "unknown" : builtAt);
        build.put("latestMigration", latestMigration());
        build.put("synthetic", true);
        builder.withDetail("decisionrail", build);
    }

    /**
     * The newest schema migration this build carries. An operator planning a rollback needs to know
     * whether an older image can run against the current database, and this is the half of that
     * answer the image owns; the other half is in {@code flyway_schema_history}.
     */
    static String latestMigration() {
        try {
            int highest = 0;
            for (org.springframework.core.io.Resource resource
                    : new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*__*.sql")) {
                String name = resource.getFilename();
                if (name == null) continue;
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^V(\\d+)__").matcher(name);
                if (matcher.find()) highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
            }
            return highest == 0 ? "unknown" : "V" + highest;
        } catch (java.io.IOException unreadable) {
            return "unknown";
        }
    }
}

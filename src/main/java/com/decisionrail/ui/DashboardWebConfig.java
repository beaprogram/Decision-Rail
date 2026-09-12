package com.decisionrail.ui;

import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the compiled dashboard from the application itself, on the same origin as the browser API.
 *
 * <h2>Why the fallback is scoped to one prefix</h2>
 * A single-page app needs unknown paths to return its shell so client-side routing works after a
 * refresh or a deep link. Done carelessly that turns every unmatched request into HTML with status 200,
 * which would mean an unknown or forbidden API call silently looked successful to a client and
 * impossible to diagnose.
 *
 * <p>The fallback therefore lives only under {@code /dashboard/**}. {@code /ui/**}, {@code /v1/**} and
 * {@code /actuator/**} are outside it and are never rewritten, so an unknown API path stays a 404 from
 * the API and a denied one stays a 403. Within the prefix, only a request that looks like a route is
 * given the shell: a missing asset still returns 404 rather than HTML, which keeps a broken build
 * visible instead of silently serving the page in place of its own script.
 */
@Configuration
public class DashboardWebConfig implements WebMvcConfigurer {
    private static final String ASSETS = "classpath:/static/dashboard/";
    private static final List<String> ASSET_SUFFIXES = List.of(
            ".js", ".mjs", ".css", ".map", ".json", ".svg", ".png", ".jpg", ".jpeg", ".gif",
            ".ico", ".webp", ".woff", ".woff2", ".ttf", ".txt", ".webmanifest");

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Entering at the root lands on the dashboard rather than on an empty 404.
        registry.addRedirectViewController("/", "/dashboard/");
        registry.addRedirectViewController("/dashboard", "/dashboard/");
        // The resource handler never sees an empty path: Spring rejects it before any resolver runs.
        // Naming the shell explicitly is what makes the dashboard's own entry point work.
        registry.addViewController("/dashboard/").setViewName("forward:/dashboard/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/dashboard/**")
                .addResourceLocations(ASSETS)
                // Hashed asset filenames make long caching safe; the shell is revalidated so a new
                // build is picked up immediately.
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaResourceResolver());
    }

    /** Returns the requested file when it exists, and the shell only for route-shaped paths. */
    private static final class SpaResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // A directory-shaped path is a route, not a file to serve, so it falls through to the shell.
            if (!resourcePath.isEmpty() && !resourcePath.endsWith("/")) {
                Resource requested = location.createRelative(resourcePath);
                if (requested.exists() && requested.isReadable()) {
                    return requested;
                }
            }
            // An asset request that missed is a real 404: serving HTML for a missing script would
            // disguise a broken build as a working page.
            String lowered = resourcePath.toLowerCase(java.util.Locale.ROOT);
            if (ASSET_SUFFIXES.stream().anyMatch(lowered::endsWith)) {
                return null;
            }
            Resource shell = new ClassPathResource("static/dashboard/index.html");
            return shell.exists() ? shell : null;
        }
    }
}

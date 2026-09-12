package com.decisionrail.ui;

import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Who the browser is signed in as, and what that identity may do.
 *
 * <p>Capabilities are derived here, on the server, from the granted roles. They exist so the dashboard
 * can avoid offering an action that would be refused, which is a usability concern and nothing more.
 * They are not authorisation: every endpoint enforces its own rule independently, and hiding a button
 * protects nothing. A caller who forges a capability flag gains no access.
 *
 * @param authenticated false for an anonymous caller, so the page can tell "not signed in" from an
 *                      error and show a sign-in screen rather than a failure
 */
public record IdentityView(
        boolean authenticated,
        String username,
        List<String> roles,
        Capabilities capabilities) {

    /**
     * What the signed-in identity may do.
     *
     * <p>The three identities stay exactly as the API defines them. MERCHANT works with its own
     * payments, accounts, replay jobs and shadow comparisons, and can read policies. ADMIN registers
     * policies and operates delivery, and deliberately gets no merchant payment or replay access, so an
     * administrator cannot move a tenant's money. OPERATIONS keeps metrics access only and therefore has
     * every flag below false: the dashboard being called an operator console is not a reason to widen it.
     */
    public record Capabilities(
            boolean viewPayments,
            boolean createPayments,
            boolean viewAccounts,
            boolean viewPolicies,
            boolean registerPolicies,
            boolean viewReplay,
            boolean createReplay,
            boolean viewShadowComparisons,
            boolean administerDelivery,
            boolean configureShadow,
            boolean viewMetrics) {

        static Capabilities forRoles(Set<String> roles) {
            boolean merchant = roles.contains("ROLE_MERCHANT");
            boolean admin = roles.contains("ROLE_ADMIN");
            boolean operations = roles.contains("ROLE_OPERATIONS");
            return new Capabilities(
                    merchant, merchant, merchant,
                    merchant || admin, admin,
                    merchant, merchant,
                    merchant,
                    admin, admin,
                    operations);
        }
    }

    public static IdentityView anonymous() {
        return new IdentityView(false, null, List.of(), Capabilities.forRoles(Set.of()));
    }

    public static IdentityView of(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return anonymous();
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new IdentityView(true, authentication.getName(),
                authorities.stream().sorted().toList(), Capabilities.forRoles(authorities));
    }
}

package com.decisionrail.ui;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the browser currently is.
 *
 * <p>Reachable without authentication on purpose: it is how the page distinguishes "not signed in" from
 * "something went wrong", and it is the request during which the CSRF cookie is issued so a later login
 * can be verified. It discloses nothing about an anonymous caller beyond that they are anonymous.
 */
@RestController
@RequestMapping("/ui")
public class UiIdentityController {

    @GetMapping("/identity")
    public ResponseEntity<IdentityView> identity(Authentication authentication) {
        // Never cached: a stale identity would let one user's session state decorate another's screen.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(IdentityView.of(authentication));
    }
}

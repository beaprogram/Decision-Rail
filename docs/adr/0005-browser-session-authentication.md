# ADR 0005: Give the browser its own session-authenticated API rather than cookies on the existing one

- Status: accepted for checkpoint 7
- Date: 2026-09-11

## Context

Everything before this milestone was consumed by scripts. The API is stateless HTTP Basic with CSRF
and CORS disabled and every unlisted path denied, which is correct for that audience: a request
carrying an `Authorization` header cannot be forged by another origin, because a browser will not
attach that header cross-site.

A dashboard changes the threat model entirely. A session cookie is attached automatically by the
browser on any request to the origin, including one triggered by a page the user did not write. The
moment cookies authenticate an endpoint, CSRF protection stops being optional.

## Decision

**Serve the browser API from its own path prefix with its own security chain.** `/ui/**` is
session-authenticated with CSRF enforced. `/v1/**` keeps stateless Basic exactly as it was, with not a
line changed. A third chain at higher precedence serves the compiled bundle under `/dashboard/**`.

The alternative was to make one chain accept both credentials and require CSRF only for
cookie-authenticated requests. That is implementable, but the rule "CSRF applies unless an
`Authorization` header is present" is a subtle thing to have to re-derive during a later review, and
getting it wrong once opens every payment mutation to forgery. Two chains state their rules
independently and each can be read in one place. It also means a session cookie cannot reach
`/v1/**` at all: a browser session is not an alternative way into the scripted API, and neither chain
can silently borrow the other's protections.

**Use Spring Security's own login and logout filters** rather than authenticating by hand. Session
fixation protection and CSRF token rotation on authentication come from the framework, which is where
they belong; a hand-written login is exactly the place those two steps get forgotten.

**Put the CSRF token in a cookie the page can read, and require it back in a header.** That is the
documented single-page pattern. The token is not a credential: it proves a request came from our own
page. Authentication stays in the session cookie, which is `HttpOnly` and unreadable by script. Tokens
are masked per response when written to the cookie, and the header value is compared unmasked, which
is what makes the BREACH mitigation and the SPA flow line up.

**`SameSite=Lax`, not `Strict`.** The dashboard is same-origin with its API, so `Lax` already blocks
the cross-site state-changing requests that matter. `Strict` would additionally drop the cookie on an
ordinary inbound link, presenting a signed-in user with a sign-in screen until they reload, for no
gain here.

**Resolve capabilities on the server and treat them as presentation only.** The identity response
carries what the signed-in role may do, and the dashboard uses it to avoid offering actions that would
be refused. Every endpoint still enforces its own rule, so a forged capability flag buys nothing.
Hidden navigation is not access control and is not relied on as any.

**Keep the three identities exactly as the API defines them.** MERCHANT works with its own data;
ADMIN registers policies and operates delivery and deliberately gets no merchant payment access, so an
administrator cannot move a tenant's money; OPERATIONS stays metrics-only and is told plainly that it
has no dashboard workspace. The product being called an operator console is not a reason to widen it.

**Scope the single-page fallback to one prefix.** A dashboard needs unknown paths to return its shell
so a deep link survives a refresh. Done globally that turns every unmatched request into HTML with
status 200, which would make an unknown or forbidden API call look successful. The fallback therefore
applies only under `/dashboard/**`; `/ui/**`, `/v1/**` and `/actuator/**` are outside it and keep their
own status codes. Within the prefix a missing asset is still a 404, so a broken build cannot disguise
itself as a working page.

## Alternatives considered

| Alternative | Reason not chosen |
| --- | --- |
| Cookies on the existing `/v1/**` chain | One chain would have to behave two ways, and the rule deciding when CSRF applies would be the only thing standing between a session cookie and a forged payment. |
| A bearer token held in JavaScript | Readable by any script that reaches the page, and needs its own refresh and revocation story. An `HttpOnly` cookie is strictly better here. |
| Storing a token in `localStorage` | Same exposure, plus it survives the tab and has to be cleared correctly on sign-out. A lint rule now forbids touching browser storage at all. |
| A separate origin for the dashboard | Requires credentialed CORS, which is the configuration most likely to be widened carelessly later. Same-origin needs none of it. |
| An external identity provider | Out of scope for this phase, adds a paid or hosted dependency, and would not change any of the decisions above. |

## Consequences

**Benefits.** The browser gets session authentication with CSRF protection, session fixation
protection, and a real server-side logout, while the scripted API and every existing test and demo
continue unchanged. Each chain's rules are readable in one place.

**Tradeoffs.** The browser API is a second surface over the same services, so a new merchant-facing
capability has to be exposed twice if both audiences need it. Thin delegating controllers keep that
duplication to routing rather than logic, and no payment command is implemented twice.

**Limitations, stated plainly.**

- Sessions are in memory. A restart signs everyone out, and more than one instance would need shared
  session storage. That is fine for a single local instance and is not a deployment design.
- Credentials are the four environment-configured accounts in memory. There is no user management, no
  password change, no lockout, and no multi-factor anything.
- The session cookie is `Secure` only when `SESSION_COOKIE_SECURE` is set. It defaults to false because
  the documented local stack is loopback HTTP, where a `Secure` cookie would never be sent and sign-in
  would be impossible. Any deployment over HTTPS must set it, and this is the one deliberate local
  exception in the configuration.
- There is no rate limiting on sign-in. Nothing here resists a determined online password-guessing
  attempt.
- Session timeout is a fixed idle window with no renewal prompt: the dashboard discovers expiry when a
  request is refused, clears the screen, and asks for sign-in again.

**Not claimed.** That this is a production authentication design. It is a locally-scoped operator
console over synthetic data, and the limitations above are the reasons why.

**Revisit when:** the dashboard needs to run as more than one instance, real user accounts exist, or a
deployment target requires an external identity provider.

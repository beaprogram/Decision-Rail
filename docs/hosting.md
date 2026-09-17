# Hosting assessment: a public demo on a zero budget

Assessed **2026-09-17** against the providers' own current documentation, linked below. The budget
for ongoing hosting is **zero** - not "low", and not "free for the first year". That rules out more
than it might seem to, because this is not a static site: it is a Java application, PostgreSQL and a
Kafka broker, all of which have to be running for the demo to be the real system rather than a
picture of it.

## What has to run

| Component | Memory it actually needs | Storage | Notes |
| --- | --- | --- | --- |
| Spring Boot application (JVM 21) | ~600 MB steady, 1 GB limit | none | serves the dashboard and both APIs |
| PostgreSQL 16 | ~200-400 MB | persistent, small (tens of MB for the demo) | the system of record |
| Kafka 3.9 (KRaft, single node) | ~1 GB (768 MB heap) | persistent, small | real asynchronous delivery |
| Caddy (TLS, reverse proxy) | ~30 MB | certificates | the only published port |

Roughly **2.5-3 GB of RAM, two cores, a few GB of disk, and a public IPv4 address with 80/443 open**.
The image is built for `linux/arm64` and `linux/amd64`, so processor architecture is not a constraint.

## The options, against the actual requirement

| Provider | Official terms (as of the date above) | Verdict |
| --- | --- | --- |
| **Oracle Cloud Always Free** | Ampere A1 compute: for an Always Free tenancy, **2 OCPUs and 12 GB** of memory; 200 GB block storage total; 10 TB/month outbound; "free of charge ... for the life of the account"; **"your credit card will not be charged unless you upgrade your account"** - a Free Tier account that never upgrades cannot create paid resources. Card and mobile number required to sign up. Idle instances may be **stopped** by Oracle after 7 days below 20% CPU (95th percentile), network and memory, and can be restarted if the shape is available. Ampere capacity is sometimes unavailable in a region. | **Chosen.** The only option where the whole stack fits, the zero is enforced by the account type rather than by hoping, and nothing expires. |
| Google Cloud Free Tier | One `e2-micro` (2 shared vCPU, **1 GB** RAM) in three US regions, 30 GB disk, 1 GB egress/month; requires a billing account; a paid billing account is charged for anything beyond the allowance. | Does not fit: 1 GB cannot hold the JVM, PostgreSQL and Kafka. And the account that can run it is one that can be billed. |
| AWS Free Tier | New accounts: a "free plan" with up to $200 of credits that **"closes on its own 6 months after you open it"**; a "paid plan" is ordinary pay-as-you-go with no spending cap. The account already available here is not a free-plan account. | A six-month trial is not zero-cost hosting, and the existing account has no enforceable cap. Nothing was started there. |
| Render | Free web services spin down after **15 minutes** without traffic (about a minute to come back); 750 free instance-hours a month; free PostgreSQL is 1 GB and **expires 30 days after creation**; no Kafka; services are suspended rather than billed when limits are hit. | The database expiry alone disqualifies it, and there is nowhere to run the broker. |
| Fly.io | No permanent free tier: usage-based billing from about $2/month per machine, with a time-limited trial. | Not zero. |
| Railway, Koyeb and similar | One-time or monthly credits, then paid; small always-free tiers are single web services with sleep, no broker. | Not zero, or not the whole stack. |
| GitHub Pages | Free static hosting. | Fine for documents; cannot run the application. |

Supporting free services, also on their own terms:

- **GitHub Container Registry** - "GitHub Packages usage is free for public packages", so the image
  built by the release workflow costs nothing to store or pull.
- **DuckDNS** - free `*.duckdns.org` subdomains with an HTTPS update endpoint, signed in with a GitHub
  (or Google) account. Gives the demo a stable hostname that Caddy can obtain a public certificate for.

## The decision

**One Oracle Cloud Always Free Ampere A1 instance, running the four containers with Docker Compose,
with Caddy terminating HTTPS for a DuckDNS hostname, and the image pulled from GHCR.** It is the
simplest thing that runs the real system permanently for nothing, and every piece of it is documented
in `deploy/` with a script that does it.

What it costs to accept:

- **Idle reclamation.** A demo nobody visits for a week may be stopped. The stack's own background
  workers keep some load on the machine, but that is not a guarantee. If it happens, the instance is
  restarted from the console and the data is still on its volume; the deployment is also fully
  reproducible from the bundle if the instance is ever gone. The documented alternative - upgrading
  the account so instances are never reclaimed - is exactly the paid step this project does not take.
- **Capacity.** Creating an Ampere instance can fail with "out of capacity" in a busy region. The
  remedy is retrying, or a different home region at sign-up; there is no cost to either.
- **Single instance, single broker.** Restarting the application ends every session (sessions are in
  memory), and the broker is one node with replication factor 1. Neither is hidden: the README and
  `deploy/README.md` say so, and the health endpoints show it.
- **It is a shared sandbox.** Every visitor is the same synthetic merchant. That is disclosed on the
  sign-in page rather than papered over with per-visitor isolation the budget does not buy.

## What is and is not done here

Everything under `deploy/` was built and rehearsed on this machine in the production shape - the same
Compose file, Caddy with a locally issued certificate, the same image - and the checks are recorded in
[verification.md](verification.md). What it has **not** been run on is the Oracle host itself, because
creating that account and the DNS name are owner actions:

1. **Create an Oracle Cloud Free Tier account** at cloud.oracle.com/free. It asks for a mobile number
   and a payment card for identity verification; the card is not charged unless the account is
   upgraded, which this deployment never requires. Do not upgrade it.
2. **Create a DuckDNS subdomain** at duckdns.org, signed in with GitHub, and keep its token.
3. **Create the instance** - shape `VM.Standard.A1.Flex`, 2 OCPU / 12 GB (or 1 OCPU / 6 GB, which is
   enough and keeps utilisation above the reclamation threshold more easily), Ubuntu 24.04 - pasting
   `deploy/cloud-init.yaml` with `DEPLOY_REVISION` replaced, and add ingress rules for TCP 80 and 443
   to the VCN's default security list.
4. Sign in over SSH once and run the three commands in `deploy/README.md`.

None of those steps needs a secret pasted anywhere other than the host itself. Until they are done,
the public URL does not exist, and this document does not pretend otherwise.

## Sources

- Oracle, *Always Free Resources* - <https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm>
- Oracle, *Oracle Cloud Infrastructure Free Tier* (trial vs Always Free, upgrading, card not charged) - <https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier.htm>
- Google Cloud, *Free Tier features* - <https://docs.cloud.google.com/free/docs/free-cloud-features>
- AWS, *Free Tier* - <https://aws.amazon.com/free/>
- Render, *Free instances and databases* - <https://render.com/docs/free>
- Fly.io, *Pricing* - <https://fly.io/docs/about/pricing/>
- GitHub, *About billing for GitHub Packages* - <https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-packages/about-billing-for-github-packages>
- DuckDNS, *Specification* - <https://www.duckdns.org/spec.jsp>

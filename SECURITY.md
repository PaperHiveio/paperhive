# Security Policy

## Supported versions

Paperhive is in early development. Only the latest commit on the default branch receives security fixes.

| Version                   | Supported |
|---------------------------|-----------|
| `0.0.1-SNAPSHOT` (latest) | Yes       |
| Anything older            | No        |

## Reporting a vulnerability

**Please don't open a public issue for security problems.**

Report it privately through GitHub: [**Report a vulnerability**](https://github.com/paperhiveio/paperhive/security/advisories/new)
(the repository's **Security** tab). Include:

- what is affected (module, command, config key, plugin API) and the Paperhive version or commit
- steps to reproduce, or a proof of concept
- the impact as you see it

You can expect an acknowledgement within a week. Once the issue is confirmed, a fix is prepared privately and released,
and you're credited in the advisory unless you'd rather not be.

## Scope

In scope, for example:

- the host's control socket (`paperhive.sock`) and the `hive` client
- downloading and verifying server jars (mirror URLs, SHA-256 checks, the cache)
- isolation between servers in one hive, and between Hive plugins and the host
- the Gradle plugin and the artifacts it resolves

Out of scope:

- vulnerabilities in Paper, Velocity, Minecraft or third-party plugins themselves (report those upstream)
- anything that needs the attacker to already control the host machine, its `paperhive.yml` or its `plugins/` folder:
  Hive and Bukkit plugins run with the host's full permissions by design
- running several untrusted parties' servers in one hive: servers share a JVM and are not a security boundary

# RamCleaner

Lightweight Paper plugin for measured JVM heap cleanup and adaptive mob-AI throttling.

## Commands

- `/ramclean` — performs two explicit JVM GC requests off the main thread and reports the **measured** before/after heap reduction. It never fabricates a freed-memory number.
- `/ramstatus` — shows current used heap, allocated heap, free heap, max heap (`-Xmx`), tracked mobs, AI-throttled mobs, and whether `-XX:+DisableExplicitGC` is present.

## Adaptive mob AI

RamCleaner tracks mobs through entity lifecycle events. Every 10 ticks it checks Paper's entity tracking state:

1. If no player is tracking the mob, its awareness/AI is disabled.
2. If players are tracking it, at least one tracking player must have line of sight for AI to stay enabled.
3. When a mob becomes relevant again, its original `Mob#isAware()` state is restored.
4. The plugin restores modified mob states on shutdown.

This avoids a full mob × player distance scan and keeps the hot path small.

## Important memory behavior

Java does **not** provide a guaranteed way for a plugin to force a specific number of megabytes to be reclaimed. `System.gc()` is a request, and JVM flags such as `-XX:+DisableExplicitGC` can prevent explicit GC processing. RamCleaner therefore measures the actual used-heap difference after cleanup and reports zero when no measurable reduction occurred.

This means the plugin has real memory accounting rather than a fake "freed RAM" counter.

## Build

Requires Java 21 and Paper 1.21.11 API.

```bash
mvn clean package
```

The resulting jar is in `target/ramcleaner-1.0.0.jar`.

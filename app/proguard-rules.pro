# R8 / ProGuard rules — Pyrycode mobile (Phase 0 baseline, ticket #81).
#
# Policy: only rules sourced from each library's official documentation, on the
# version pinned in gradle/libs.versions.toml. -dontwarn entries must name the
# library and the reason. Empty sections are intentional and mean "the library's
# consumer-rules cover everything we hit in this app at this version" — re-test
# after every version bump.

# --- Koin (io.insert-koin:koin-androidx-compose, BoM 4.0.4) ---
# https://insert-koin.io/docs/reference/koin-mp/android — Koin 4.x with the
# runtime DSL (single { } / viewModel { }) needs no R8 rules. There is no
# koin-annotations / KSP wiring in this project, so the reflective surface is
# limited to KClass-based lookup of types Koin itself does not need to keep.
# ViewModels (ChannelListViewModel, DiscussionListViewModel) are reached via
# koinViewModel<T>() in composables and survive R8 because the type token is
# materialized at call sites the compiler already keeps.

# --- Kotlin Coroutines (org.jetbrains.kotlinx:kotlinx-coroutines-core 1.10.2) ---
# Consumer-rules in kotlinx-coroutines-core / kotlinx-coroutines-android cover
# the runtime. The debug agent is a tooling-only entry point that doesn't ship
# in our release classpath; suppress its missing-class warning.
-dontwarn kotlinx.coroutines.debug.AgentPremain

# --- AndroidX DataStore Preferences (1.1.7) ---
# Preferences flavor uses no Proto codegen and no reflection; consumer-rules
# shipped by the library suffice.

# --- AndroidX Lifecycle / ViewModel / Navigation Compose, Compose BoM, kotlinx-datetime ---
# All ship consumer-rules. Navigation Compose registers destinations by string
# route, not by reflective class lookup, so no -keep is needed for screens.

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", "**/.gradle/**")
        ktlint(libs.versions.ktlint.get())
    }
    format("misc") {
        target("**/*.md", "**/*.json", "**/*.yml", "**/*.yaml")
        targetExclude(
            "**/build/**",
            "**/.gradle/**",
            "**/node_modules/**",
            "gradle/wrapper/**",
            ".codegraph/**",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

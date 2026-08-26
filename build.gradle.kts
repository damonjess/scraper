// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    id("idea")
}

idea {
    module {
        excludeDirs.addAll(
            listOf(
                file(".gradle"),
                file(".idea"),
                file(".kotlin"),
                file(".artifacts"),
                file("build"),
                file("app/build")
            )
        )
    }
}
// Top-level build file. Keep empty except plugin declarations.
// Rams #10: the file that defines the build shouldn't itself contain build logic.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}

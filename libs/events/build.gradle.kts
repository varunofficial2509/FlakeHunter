// Event shapes shared by every Java producer and consumer (tech doc section 6).
// Plain records with no dependencies, so any module can use them.
plugins {
    id("flakehunter.java-conventions")
    `java-library`
}

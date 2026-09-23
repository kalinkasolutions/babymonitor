package ch.kalinka.babymonitor.ui

/**
 * Mirrors the backend's `Password.RequiredLength`, so the obvious mistake is caught before a round
 * trip. It has to be kept in step by hand — the server does not publish its rules — so this is the
 * production value even though a Development backend accepts less.
 */
const val MinPasswordLength = 10

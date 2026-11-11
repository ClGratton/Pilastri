package xyz.grtlabs.pilastri.data

/** Completion is delivered after durable storage accepts or rejects the entire candidate. */
typealias ArchiveSave = (Archive, (Boolean) -> Unit) -> Unit

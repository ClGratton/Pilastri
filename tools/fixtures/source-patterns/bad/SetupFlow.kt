// Fixture of the user's case (2026-11-10, "i pulsanti sotto commettono 1s a comparire"): the dock is gated on the animated inset.
val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

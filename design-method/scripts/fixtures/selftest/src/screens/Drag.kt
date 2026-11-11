fun Swipe() {
  Modifier.draggable(state)
}
fun Fine() {
  Modifier.draggable(state); rememberCommitHaptics()
}

package dev.sluide.games.pipepuzzle

/**
 * NETWORK: every tile belongs to one tree with a single solution. PATH: lead the flow from the source to the drain
 * without leaks; tiles off the route are decoys.
 */
enum class Mode { NETWORK, PATH }

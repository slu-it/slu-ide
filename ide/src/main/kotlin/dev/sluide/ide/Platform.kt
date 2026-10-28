package dev.sluide.ide

/** Alt+digit types characters on macOS, so window shortcuts with digits use Cmd there. */
internal val isMac = System.getProperty("os.name").lowercase().contains("mac")

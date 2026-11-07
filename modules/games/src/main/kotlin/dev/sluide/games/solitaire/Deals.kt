package dev.sluide.games.solitaire

/**
 * The deals of every mode, numbered 1..[MAX_NUMBER]. A deal is a Fisher–Yates shuffle (from the last position down)
 * of the ordered deck ([orderedDeck]) driven by [SplitMix64], seeded from the deal number and a constant per mode.
 * Never change the order, the seeds or the shuffle: the deal of a number must stay the same forever (ADR 0138).
 */
internal object Deals {
    const val MAX_NUMBER = 1_000_000

    /**
     * The shuffled deck of deal [number] (1..[MAX_NUMBER]) of [mode]: 52 cards, Spider 104 (1 suit: 8×spades; 2
     * suits: 4×spades + 4×hearts; 4 suits: 2 full decks). Deterministic forever.
     */
    fun deck(
        mode: Mode,
        number: Int,
    ): List<Card> {
        require(number in 1..MAX_NUMBER) { "deal number must be 1..$MAX_NUMBER: $number" }
        val deck = orderedDeck(mode).toMutableList()
        // The number goes into the upper half: seeds of two numbers differ by a multiple of 2^32, and the generator
        // steps by an odd constant, so the streams of two deals cannot overlap within the values a shuffle draws.
        val random = SplitMix64(salt(mode) xor (number.toLong() shl 32))
        for (i in deck.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val swap = deck[i]
            deck[i] = deck[j]
            deck[j] = swap
        }
        return deck
    }

    /** The unshuffled deck of [mode]: by suit in the order of [Suit], then by rank, copies of a card side by side. */
    internal fun orderedDeck(mode: Mode): List<Card> =
        Suit.entries.flatMap { suit ->
            val copies = copies(mode, suit)
            (1..13).flatMap { rank -> List(copies) { Card(rank, suit) } }
        }

    private fun copies(
        mode: Mode,
        suit: Suit,
    ): Int =
        when (mode) {
            Mode.KLONDIKE_DRAW_1, Mode.KLONDIKE_DRAW_3, Mode.FREECELL -> 1
            Mode.SPIDER_1 -> if (suit == Suit.SPADES) 8 else 0
            Mode.SPIDER_2 -> if (suit == Suit.SPADES || suit == Suit.HEARTS) 4 else 0
            Mode.SPIDER_4 -> 2
        }

    /** Fixed random constants, one per mode, so that the modes get different deals for the same number. */
    private fun salt(mode: Mode): Long =
        when (mode) {
            Mode.KLONDIKE_DRAW_1 -> 0x2C5E_91A7_D3F0_4B68
            Mode.KLONDIKE_DRAW_3 -> 0x6B1D_E83C_0F72_A95E
            Mode.FREECELL -> 0x13F8_A4C6_5E09_D7B2
            Mode.SPIDER_1 -> 0x4A97_0D2E_B6C1_F385
            Mode.SPIDER_2 -> 0x7E26_C95B_1A83_04DF
            Mode.SPIDER_4 -> 0x58D3_3F6A_E70C_92B1
        }
}

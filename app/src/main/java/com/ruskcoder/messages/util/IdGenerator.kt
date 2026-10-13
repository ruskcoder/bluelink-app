package com.ruskcoder.messages.util

import kotlin.random.Random

private var counter = 0

/** Time-ordered, collision-safe enough for local message and chat row ids. */
@Synchronized
fun makeId(prefix: String = "m"): String {
    counter = (counter + 1) % 1_000
    val time = java.lang.Long.toString(System.currentTimeMillis(), 36)
    val seq = Integer.toString(counter, 36)
    val rand = Integer.toString(Random.nextInt(0, 46_656), 36)
    return "${prefix}_$time$seq$rand"
}

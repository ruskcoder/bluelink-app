package com.ruskcoder.messages.data.model

enum class DeliveryStatus {
    SENT,
    DELIVERED,
    READ,
    /**
     * An audio message was listened to — Apple's played receipt, command 105. Past [READ] because
     * it implies it: nobody plays a voice message they have not seen, and the status only ever
     * moves forward by ordinal.
     */
    PLAYED,
    ;

    /** Read, or played — which is read and then some. What every "has it been seen" check means. */
    val isRead: Boolean get() = this >= READ
}

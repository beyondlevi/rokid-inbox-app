package com.rokid.inbox.glasses.transport

import com.rokid.inbox.contracts.GlassesToPhoneMessage
import com.rokid.inbox.contracts.PhoneToGlassesMessage

interface PhoneBridge {
    fun send(message: GlassesToPhoneMessage)
    fun subscribe(listener: (PhoneToGlassesMessage) -> Unit): () -> Unit
}

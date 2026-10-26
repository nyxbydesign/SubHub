package com.subhub.app.remote

import computer.iroh.EndpointTicket

/** Keep vendor class versions behind the same Kotlin boundary as the production adapter. */
object IrohTestRoutes {
    @JvmStatic fun hasRelay(route: String): Boolean = EndpointTicket.fromString(route).use { ticket ->
        ticket.endpointAddr().use { address -> address.relayUrl() != null }
    }
}

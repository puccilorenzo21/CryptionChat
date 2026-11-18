package com.lollo1344.cryptionchat

/**
 * Un oggetto singleton per mantenere lo stato della chat visibile a livello di processo.
 * Questo sopravvive al riavvio del servizio (se il processo dell'app non viene ucciso)
 * e risolve il problema della perdita di stato quando il servizio viene ricreato
 * mentre una ChatActivity è ancora in primo piano.
 */
object ChatStatusHolder {
    var currentlyVisibleGroupId: Int = -1
}
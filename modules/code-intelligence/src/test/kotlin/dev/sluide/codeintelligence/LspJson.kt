package dev.sluide.codeintelligence

import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.eclipse.lsp4j.services.LanguageServer

/**
 * What lsp4j's message parser (the one the connection uses) makes of the `result` JSON [result] of a [method] answer:
 * the shapes lsp4j really delivers, `null` elements and absent fields included.
 */
internal fun parsedResult(
    method: String,
    result: String,
): Any? {
    val handler = MessageJsonHandler(LinkedHashMap(ServiceEndpoints.getSupportedMethods(LanguageServer::class.java)))
    handler.setMethodProvider { method }
    return (handler.parseMessage("""{"jsonrpc":"2.0","id":1,"result":$result}""") as ResponseMessage).result
}

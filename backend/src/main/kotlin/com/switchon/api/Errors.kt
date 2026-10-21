package com.switchon.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** A failure with a stable machine code and a message safe to show the customer. */
class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

fun badRequest(message: String, code: String = "invalid_request") = ApiException(HttpStatusCode.BadRequest, code, message)
fun notFound(message: String = "Not found.") = ApiException(HttpStatusCode.NotFound, "not_found", message)
fun conflict(code: String, message: String) = ApiException(HttpStatusCode.Conflict, code, message)
fun unprocessable(code: String, message: String) = ApiException(HttpStatusCode.UnprocessableEntity, code, message)
fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Your session has expired. Restart the app to continue.")

@Serializable data class ErrorBody(val error: ErrorDetail)
@Serializable data class ErrorDetail(val code: String, val message: String)

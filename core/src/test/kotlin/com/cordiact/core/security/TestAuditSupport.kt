package com.cordiact.core.security

object TestAuditSupport {
    fun log(): InMemoryAuditLog = InMemoryAuditLog(
        HmacAuditSigner("0123456789abcdef".toByteArray()),
    )
}

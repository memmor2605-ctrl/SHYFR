package com.shyfr.app.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode

/** Argon2id: hasło + login -> 32-bajtowy klucz. Parametry celują w ok. 0,5-1 s na S24 FE. */
object Kdf {
    private const val ITERATIONS = 3
    private const val MEMORY_KIB = 64 * 1024 // 64 MiB

    fun derive(login: String, password: CharArray, salt: ByteArray): ByteArray {
        val input = login.trim().lowercase().toByteArray() + 0 + String(password).toByteArray()
        try {
            val res = Argon2Kt().hash(
                mode = Argon2Mode.ARGON2_ID,
                password = input,
                salt = salt,
                tCostInIterations = ITERATIONS,
                mCostInKibibyte = MEMORY_KIB,
                hashLengthInBytes = 32
            )
            return res.rawHashAsByteArray()
        } finally {
            input.fill(0)
        }
    }
}

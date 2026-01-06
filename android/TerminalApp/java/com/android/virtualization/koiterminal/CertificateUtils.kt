/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.virtualization.terminal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception
import java.lang.RuntimeException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate

object CertificateUtils {
    private const val ALIAS = "ttyd"

    fun createOrGetKey(): KeyStore.PrivateKeyEntry {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)

            if (!ks.containsAlias(ALIAS)) {
                Log.d(MainActivity.TAG, "there is no keypair, will generate it")
                createKey()
            } else if (ks.getCertificate(ALIAS) !is X509Certificate) {
                Log.d(MainActivity.TAG, "certificate isn't X509Certificate or it is invalid")
                createKey()
            } else {
                try {
                    (ks.getCertificate(ALIAS) as X509Certificate).checkValidity()
                } catch (e: CertificateExpiredException) {
                    Log.d(MainActivity.TAG, "certificate is invalid", e)
                    createKey()
                } catch (e: CertificateNotYetValidException) {
                    Log.d(MainActivity.TAG, "certificate is invalid", e)
                    createKey()
                }
            }
            return ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        } catch (e: Exception) {
            throw RuntimeException("cannot generate or get key", e)
        }
    }

    @Throws(
        NoSuchAlgorithmException::class,
        NoSuchProviderException::class,
        InvalidAlgorithmParameterException::class,
    )
    private fun createKey() {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .build()
        )

        kpg.generateKeyPair()
    }

    fun writeCertificateToFile(context: Context, cert: Certificate) {
        val certFile = File(context.getFilesDir(), "ca.crt")
        try {
            FileOutputStream(certFile).use { writer ->
                val certBegin = "-----BEGIN CERTIFICATE-----\n"
                val certEnd = "-----END CERTIFICATE-----\n"
                val output =
                    (certBegin +
                        Base64.encodeToString(cert.encoded, Base64.DEFAULT)
                            .replace("(.{64})".toRegex(), "$1\n") +
                        certEnd)
                writer.write(output.toByteArray())
            }
        } catch (e: IOException) {
            throw RuntimeException("cannot write certs", e)
        } catch (e: CertificateEncodingException) {
            throw RuntimeException("cannot write certs", e)
        }
    }
}

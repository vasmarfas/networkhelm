package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import com.vasmarfas.networkhelm.keenetic.sha256
import com.vasmarfas.networkhelm.model.DeviceException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3ServiceTest {
    // the four examples of a request in a single chunk from the documentation of SigV4 for S3, recomputed by hand in Python as well
    @Test
    fun theSignaturesAreThoseOfTheExamplesOfAws() {
        val time = "20130524T000000Z"
        val empty = hex(sha256(ByteArray(0)))
        val host = "examplebucket.s3.amazonaws.com"
        fun authorization(method: String, path: String, query: String, headers: Map<String, String>, payload: String) =
            sigV4(method, path, query, headers + mapOf("host" to host, "x-amz-content-sha256" to payload, "x-amz-date" to time), payload, time, "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            authorization("GET", "/test.txt", "", mapOf("range" to "bytes=0-9"), empty),
        )
        val put = hex(sha256("Welcome to Amazon S3.".encodeToByteArray()))
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", put)
        assertEquals(
            "98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
            authorization("PUT", "/test%24file.text", "", mapOf("date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-storage-class" to "REDUCED_REDUNDANCY"), put).substringAfter("Signature="),
        )
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543", authorization("GET", "/", "lifecycle=", emptyMap(), empty).substringAfter("Signature="))
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7", authorization("GET", "/", "max-keys=2&prefix=J", emptyMap(), empty).substringAfter("Signature="))
    }

    // the bucket gives two keys a page; the token of the next page, base64 with = and / in it, is signed as the bucket reads it
    @Test
    fun aListOfSeveralPagesIsReadWhole() = runTest {
        val bucket = FakeS3()
        val service = bucket.service()
        val devices = listOf("1", "2", "3", "4", "5").map { it.padStart(32, 'e') }
        for (device in devices) service.write(statePath(device), device.encodeToByteArray())
        assertEquals(devices.map(::statePath).toSet(), service.list().map { it.path }.toSet())
        assertEquals(3, bucket.requests.count { it.url.parameters["list-type"] == "2" })
    }

    // the secret key signs and is never sent, and a refusal names the code of S3 and not the key
    @Test
    fun aRefusalNamesTheCodeOfS3AndNoRequestCarriesTheSecretKey() = runTest {
        val bucket = FakeS3()
        bucket.service().write(VAULT, "one".encodeToByteArray())
        val error = assertFailsWith<DeviceException> { bucket.service(secret = "a key that is not the one").read(VAULT) }
        assertEquals("s3.example.org answered HTTP 403 SignatureDoesNotMatch", error.message)
        assertFalse("a key that is not the one" in error.toString())
        assertTrue(bucket.requests.none { request -> request.headers.entries().any { (_, values) -> values.any { S3_SECRET in it || "a key that is not the one" in it } } })
    }
}

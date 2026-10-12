package pw.binom.tls.testutil

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Native actual. Hardcoded pre-generated self-signed RSA-2048 cert (CN=test)
 * emitted by jvmTest/TestCertificateGenerator and dumped to /tmp/cert.der
 * and /tmp/key.der by the PrintCertTest helper. Valid until year 2126, so
 * it is safe to bake into the test source. The e2e test only needs a cert
 * the client (trustAll=true) will accept.
 */
@OptIn(ExperimentalEncodingApi::class)
actual object TestCertificateGenerator {
    actual fun generateSelfSigned(subject: String): TestCert {
        if (subject != "CN=test") {
            throw UnsupportedOperationException("native actual only supports CN=test, got '" + subject + "'")
        }
        return TestCert(
            certDer = Base64.decode(CERT_B64),
            keyDer = Base64.decode(KEY_B64),
        )
    }

    private val CERT_B64 = """
        MIICuzCCAaOgAwIBAgIBATANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDDAR0ZXN0
        MCAXDTcwMDEwMTAwMDAwMFoYDzIxMjYwOTE4MDQxNjExWjAPMQ0wCwYDVQQDDAR0
        ZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArJAV2IKJxJIh4ekb
        wea0DoY9speungx5r1zLdI0PxLuUf66gDCQoJ7K/gcmjl1+YUVfRaoHMQxxsAxf2
        ke+J5h8x76qNWDMFIv72sfvYvQ54DGmtWstI/cgM6L5nEi1pDV/7TbXk9kWZN82a
        VYMMblykm3UpVLURDPs8NJD1bNQrr6HnHDMq34e5O94Fa+u2T3MHqMnWjZ1kbnXp
        vSSuE51GbGU/Qhj5ANqoExGdvooS0pJmP/Lr23PriZhNIBtdaVUPjip6p3KT3woZ
        F6Z1HlN4970MaQmOMfwinpNQfSZv9HvOSBRIqSaoMjfGMVOnyfea8S+wYpHYcbVL
        xN8qsQIDAQABoyAwHjAMBgNVHRMBAf8EAjAAMA4GA1UdDwEB/wQEAwIFoDANBgkq
        hkiG9w0BAQsFAAOCAQEAi7BpGIvPgChfFdphzzQU71MJaliRCRlvPAO5bH+XpA+e
        GZI97MexmnzFH79n08vPpBpX+/CBT2t6F3E+90uunh4q1g6Stq6w2iXtjbEl5jIJ
        2Vt3rmggSZvSh+C3ZRt0Dbm5xzRJ4z+pwFowVBNYL33PGXhqkElHv4rHk7q7lcb1
        /i89y/XkI8KXHDXIzUXCIcvhPDYjQPmEpF8qZjDhJWzH2BOb42b1cq7sNR3paf9v
        QoffC7Qcy+MZt8PzZoLn2BBSI/ceqYBQ4vqi7/sycKQhBOAvJwJh8zaYLOa8V1h6
        RKXyyPWS7GuebAvi35qnBKSZipWfGkrj6fwS9bWbDw==
    """.trimIndent().replace(Regex("\\s+"), "")

    private val KEY_B64 = """
        MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCskBXYgonEkiHh
        6RvB5rQOhj2yl66eDHmvXMt0jQ/Eu5R/rqAMJCgnsr+ByaOXX5hRV9FqgcxDHGwD
        F/aR74nmHzHvqo1YMwUi/vax+9i9DngMaa1ay0j9yAzovmcSLWkNX/tNteT2RZk3
        zZpVgwxuXKSbdSlUtREM+zw0kPVs1CuvoeccMyrfh7k73gVr67ZPcweoydaNnWRu
        dem9JK4TnUZsZT9CGPkA2qgTEZ2+ihLSkmY/8uvbc+uJmE0gG11pVQ+OKnqncpPf
        ChkXpnUeU3j3vQxpCY4x/CKek1B9Jm/0e85IFEipJqgyN8YxU6fJ95rxL7Bikdhx
        tUvE3yqxAgMBAAECggEAAzGXMnxpt+ciePjSI59ZzsIm2OLTEd7D88dFz9d6gilB
        KkLvnPDE8TMl7FrwgsygDvwzvV5kCudbsB4cxuw88MUpvxPdNop2DKmMEgKAvXne
        AaMXWOgMr/9xoJOvQ/OlD06q9DkqzpV1USG4qHE2QHjtwY1fxfOkOlvrtULHjcJn
        ZBnKCeldyv9axb4nVapMltzzH7OOIjO5xZLbJ5pUlnEeOVuizlA4gNk4c31hWqdM
        Qz7Snz1zqBxpU5d7QSCRDWYx5ZcVnegBEp/TjJCaeGoR/LQ1UaTuvBRC8kgo5l92
        dYuPCjW8+a57DcpcZVEP0N3WOx9ReHvmeey9MSvJaQKBgQDrGoHyk7lImCeIKuQv
        W3fzwC6zt4Xyv0hqOFi1Be3ydCRqtVQKShRACa4Ds4IOPky1OM67WbIgkSlnxjzI
        qNIMy3fTW5EE9AUMhpr4YJo27D5cOv9TDvZx5aI4DxkBo7sOEJ+nuSpGZzPTd/JN
        M4DBwzzjCnGnL9K6HHfRei8nfQKBgQC75opnvocAdXnmJlqPeRRK1vGI+heG3tbf
        +hIjLR+v7VMedc3UTJo4r+UDviqC2GZ6xytXPxA6HQEjtyBc4mv3VKZXqmjnDozB
        PTLVb1xobxXL37DE6nYrDgtWIZ0vVZEEPQOCpOGHADKgcsFa9/AtfCqx6Or8rpNB
        tQ+XxMJ+RQKBgFCFfahFbBou8qsydYC6xTSYx+YJnzjqxrSKo+csJrj5ild5z1TO
        1mywZAckfhC+yarvz+5e5CE/Vn0SalMXbwmuzlV3cQ3PPcNyfrU+8NKKyzjvg+9A
        mqubyJmGojvrhe9lRik9w/rk8d0loblLQHgclEf2lcJ7IKNZiLgYLvpJAoGBAItE
        2esBJdDqQbqbTN4wkSDTZdzv+QYnHr6N/GmEJYtg7Q2TpUaorFo9zRHUHnAy8uon
        u3EENgPMwa52Kg+OZxW/8TbjALPZbexAbvARhT2GsHIWz090tLc+xppFedq+oiix
        DGwFx38nX17bD4NcZ/FeqcqJ57n3rv+xsuf7l2i9AoGBAIOjY2m6ZORAFMhBKj/W
        AzsLvdj261yViuc6NuubNPkhAzLuKDmFCI75rUMFdpS+v0ZuT/bHVaYnhYBprMLV
        j7Xvmnk6HvFg+z8wDG0TyWKylBTORRb94IT5w36tU4/VvS5IH6+T9UyHtalAcdJe
        Rpg9lKDxCfQWYX0ZCzo+8zGU
    """.trimIndent().replace(Regex("\\s+"), "")
}


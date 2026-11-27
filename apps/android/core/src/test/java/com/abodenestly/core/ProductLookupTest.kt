package com.abodenestly.core

import com.abodenestly.core.api.HttpApiService
import com.abodenestly.core.api.ProductLookupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NUTELLA = """{"barcode":"3017620422003","name":"Nutella","brand":"Ferrero","category":null,"image_url":"https://img.test/n.jpg","default_unit":null}"""

/** SHOP-10: how `GET /products/{barcode}` maps to a [ProductLookupResult]; same table as iOS `productResult`. */
class ProductLookupTest {
    private fun map(status: Int, code: String? = null, body: String = "") = HttpApiService.productResult(status, code, body)

    @Test
    fun `200 with a product is found`() {
        val found = map(200, body = NUTELLA) as ProductLookupResult.Found
        assertEquals("Nutella", found.product.name)
        assertEquals("Ferrero", found.product.brand)
        assertEquals("3017620422003", found.product.barcode)
    }

    @Test
    fun `a product that omits its null fields is still found`() {
        val found = map(200, body = """{"barcode":"12345678","name":"Salt"}""") as ProductLookupResult.Found
        assertEquals(null, found.product.brand)
    }

    @Test
    fun `an unreadable 200 body fails`() {
        assertEquals(ProductLookupResult.Failed, map(200, body = "not json"))
    }

    @Test
    fun `404 and 400 are not found`() {
        assertEquals(ProductLookupResult.NotFound, map(404))
        assertEquals(ProductLookupResult.NotFound, map(404, "not_found"))
        assertEquals(ProductLookupResult.NotFound, map(400))
        assertEquals(ProductLookupResult.NotFound, map(400, "invalid_barcode"))
    }

    @Test
    fun `only a 502 can pass by itself`() {
        assertEquals(ProductLookupResult.Offline, map(502))
        assertEquals(ProductLookupResult.Offline, map(502, "upstream_unavailable"))
    }

    @Test
    fun `anything else fails`() {
        for (status in listOf(401, 403, 500, 503)) assertEquals("status $status", ProductLookupResult.Failed, map(status))
        assertTrue(map(500, "internal") is ProductLookupResult.Failed)
    }
}

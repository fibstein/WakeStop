package com.fibstein.wakestop.data

import com.fibstein.wakestop.data.geocoding.PhotonClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotonParseTest {
    @Test
    fun parsesFeatures() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[29.0255,41.0422]},
               "properties":{"name":"Beşiktaş İskele","district":"Sinanpaşa","city":"İstanbul","country":"Türkiye"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[29.1,41.1]},
               "properties":{"street":"Barbaros Bulvarı","housenumber":"12","city":"İstanbul"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[29.2,41.2]},"properties":{}}
            ]}
        """.trimIndent()
        val r = PhotonClient.parse(json)
        assertEquals(2, r.size)
        assertEquals("Beşiktaş İskele", r[0].name)
        assertEquals("Sinanpaşa, İstanbul, Türkiye", r[0].detail)
        assertEquals(41.0422, r[0].point.lat, 1e-9)
        assertEquals(29.0255, r[0].point.lon, 1e-9)
        assertEquals("Barbaros Bulvarı 12", r[1].name)
        assertEquals("İstanbul", r[1].detail)
    }

    @Test
    fun emptyResponse() {
        assertTrue(PhotonClient.parse("""{"features":[]}""").isEmpty())
        assertTrue(PhotonClient.parse("""{}""").isEmpty())
    }
}

package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostFailoverLogicTest {
    private val g = listOf("http://main.tld:8080", "http://backup1.tld:8080", "http://backup2.tld")

    @Test fun normalize_adds_scheme_and_strips_slash() {
        assertEquals("http://b.tld:8080", HostFailoverLogic.normalize(" b.tld:8080/ "))
        assertEquals("https://b.tld", HostFailoverLogic.normalize("https://b.tld"))
        assertNull(HostFailoverLogic.normalize(""))
        assertNull(HostFailoverLogic.normalize("   "))
        assertNull(HostFailoverLogic.normalize("http://b tld"))
    }

    @Test fun baseIndex_matches_whole_addresses_only() {
        assertEquals(0, HostFailoverLogic.baseIndex("http://main.tld:8080/live/u/p/1.ts", g))
        assertEquals(2, HostFailoverLogic.baseIndex("http://BACKUP2.tld/player_api.php?x=1", g))
        // "main.tld:80801" is a different server
        assertEquals(-1, HostFailoverLogic.baseIndex("http://main.tld:80801/live/1.ts", g))
        assertEquals(-1, HostFailoverLogic.baseIndex("http://other.tld/live/1.ts", g))
    }

    @Test fun candidates_start_on_active_server() {
        val u = "http://main.tld:8080/live/u/p/1.ts"
        assertEquals(
            listOf("http://main.tld:8080/live/u/p/1.ts", "http://backup1.tld:8080/live/u/p/1.ts", "http://backup2.tld/live/u/p/1.ts"),
            HostFailoverLogic.candidates(u, g, 0)
        )
        assertEquals(
            listOf("http://backup2.tld/live/u/p/1.ts", "http://main.tld:8080/live/u/p/1.ts", "http://backup1.tld:8080/live/u/p/1.ts"),
            HostFailoverLogic.candidates(u, g, 2)
        )
        // Address on a backup server (e.g. from a playlist loaded through it) belongs to the same group
        assertEquals("http://main.tld:8080/movie/1.mp4", HostFailoverLogic.candidates("http://backup1.tld:8080/movie/1.mp4", g, 0)[0])
        // index out of range (backup server removed) is clamped
        assertEquals("http://backup2.tld/x", HostFailoverLogic.candidates("http://main.tld:8080/x", g, 9)[0])
    }

    @Test fun candidates_without_group_only_the_url() {
        assertEquals(listOf("http://other.tld/x"), HostFailoverLogic.candidates("http://other.tld/x", g, 0))
        assertEquals(listOf("http://main.tld:8080/x"), HostFailoverLogic.candidates("http://main.tld:8080/x", listOf("http://main.tld:8080"), 0))
    }

    @Test fun group_any_number_without_empty_or_duplicates() {
        assertEquals(
            listOf("http://main.tld:8080", "http://b1.tld", "http://b2.tld", "http://b3.tld", "http://b4.tld"),
            HostFailoverLogic.group("http://main.tld:8080/", listOf("b1.tld", "", "http://MAIN.tld:8080", "b2.tld", "b1.tld", "b3.tld", "b4.tld", "broken address"))
        )
        assertEquals(listOf("http://main.tld:8080"), HostFailoverLogic.group("http://main.tld:8080", listOf("", " ")))
    }

    // ---- pickGroup: credentials of one playlist must only go to servers entered for that playlist

    private val providerA = listOf("http://a.tld:8080", "http://a-backup.tld")
    private val providerB = listOf("http://b.tld")   // playlist without backups

    @Test fun pickGroup_main_server_of_playlist_without_backups_gets_no_failover() {
        // b.tld was entered by mistake as a backup of provider A as well
        val groups = listOf(providerA + "http://b.tld", providerB)
        assertNull(HostFailoverLogic.pickGroup("http://b.tld/player_api.php?username=bob&password=x", groups))
    }

    @Test fun pickGroup_prefers_the_playlist_whose_main_server_matches() {
        val bWithBackup = listOf("http://b.tld", "http://b2.tld")
        val groups = listOf(providerA + "http://b.tld", bWithBackup)
        assertEquals(bWithBackup, HostFailoverLogic.pickGroup("http://b.tld/live/bob/x/1.ts", groups))
        assertEquals(providerA + "http://b.tld", HostFailoverLogic.pickGroup("http://a.tld:8080/live/al/y/1.ts", groups))
    }

    @Test fun pickGroup_backup_address_belongs_to_its_group() {
        assertEquals(providerA, HostFailoverLogic.pickGroup("http://a-backup.tld/live/al/y/1.ts", listOf(providerA, providerB)))
    }

    @Test fun pickGroup_backup_address_shared_by_different_providers_is_ambiguous() {
        val c = listOf("http://c.tld", "http://a-backup.tld")
        assertNull(HostFailoverLogic.pickGroup("http://a-backup.tld/live/u/p/1.ts", listOf(providerA, c)))
    }

    @Test fun pickGroup_same_provider_twice_is_not_ambiguous() {
        // two accounts on the same main server, both with the same backup
        val second = listOf("http://a.tld:8080", "http://a-backup.tld")
        assertEquals(providerA, HostFailoverLogic.pickGroup("http://a-backup.tld/x", listOf(providerA, second)))
    }

    @Test fun pickGroup_unknown_host_gets_no_failover() {
        assertNull(HostFailoverLogic.pickGroup("http://other.tld/x", listOf(providerA, providerB)))
    }
}

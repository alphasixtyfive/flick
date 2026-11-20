package app.flick.tv.home

import org.junit.Assert.*
import org.junit.Test

class HomeFavoritesTest {
    @Test fun addingAtTheLimitNeverTruncatesExistingFavorites() {
        val original = (0 until HomePolicy.MAX_FAVORITES).map { "app.example.p$it" }
        assertNull(HomePolicy.addFavorite(original, "app.example.extra"))
        assertEquals(100, original.size)
        assertEquals("app.example.p99", original.last())
    }

    @Test fun anAlreadyPinnedPackageIsNotDuplicatedOrRejectedAtTheLimit() {
        val original = (0 until HomePolicy.MAX_FAVORITES).map { "app.example.p$it" }
        assertEquals(original, HomePolicy.addFavorite(original, original.first()))
    }

    @Test fun aNewFavoriteIsAppendedWithoutChangingTheExistingOrder() {
        assertEquals(listOf("app.example.a", "app.example.b"), HomePolicy.addFavorite(listOf("app.example.a"), "app.example.b"))
    }
}

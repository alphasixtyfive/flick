package app.flick.tv.apps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppCatalogueCacheTest {
    private val installed = listOf(AppEntry("org.example.video", "Video", null))

    @Test fun repeatedReadsReuseTheSameArtworkAndList() = runTest {
        var reads = 0
        val cache = AppCatalogueCache { reads++; installed }
        repeat(20) { assertSame(installed, cache.apps()) }
        assertEquals(1, reads)
    }

    @Test fun invalidationRefreshesOnceAndPublishesANewRevision() = runTest {
        var reads = 0
        var source = installed
        val cache = AppCatalogueCache { reads++; source }
        cache.apps()
        source = emptyList()
        cache.invalidate()
        assertEquals(1L, cache.revision.value)
        assertTrue(cache.apps().isEmpty())
        cache.apps()
        assertEquals(2, reads)
    }

    @Test fun simultaneousReadersShareOneLoad() = runTest {
        var reads = 0
        val ready = CompletableDeferred<Unit>()
        val cache = AppCatalogueCache { reads++; ready.await(); installed }
        val first = async { cache.apps() }
        val second = async { cache.apps() }
        runCurrent()
        ready.complete(Unit)
        assertSame(first.await(), second.await())
        assertEquals(1, reads)
    }

    @Test fun aChangeDuringLoadingDoesNotMarkOldArtworkAsCurrent() = runTest {
        var reads = 0
        val ready = CompletableDeferred<Unit>()
        val cache = AppCatalogueCache { if (++reads == 1) ready.await(); installed }
        val first = async { cache.apps() }
        runCurrent()
        cache.invalidate()
        ready.complete(Unit)
        first.await()
        cache.apps()
        assertEquals(2, reads)
    }

    @Test fun aCancelledLoadDoesNotPoisonTheCacheOrBlockTheNextReader() = runTest {
        var reads = 0
        val cache = AppCatalogueCache {
            if (++reads == 1) CompletableDeferred<Unit>().await()
            installed
        }
        val cancelled = launch { cache.apps() }
        runCurrent()
        cancelled.cancelAndJoin()
        assertSame(installed, cache.apps())
        assertEquals(2, reads)
    }

    @Test fun aFailedReadCanBeRetried() = runTest {
        var reads = 0
        val cache = AppCatalogueCache { if (++reads == 1) error("Package manager unavailable"); installed }
        try { cache.apps(); fail("Read should fail") } catch (_: IllegalStateException) { }
        assertSame(installed, cache.apps())
        assertEquals(2, reads)
    }

    @Test fun cachedReadsStillRespectCancellation() = runTest {
        val cache = AppCatalogueCache { installed }
        cache.apps()
        var returned = false
        launch {
            currentCoroutineContext().cancel()
            cache.apps()
            returned = true
        }.join()
        assertFalse(returned)
    }
}

/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.cookies.impl

import android.webkit.CookieManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.duckduckgo.app.fire.DatabaseLocator
import com.duckduckgo.app.fire.FireproofRepository
import com.duckduckgo.app.fire.WebViewDatabaseLocator
import com.duckduckgo.app.fire.fireproofwebsite.data.FireproofWebsiteEntity
import com.duckduckgo.app.fire.fireproofwebsite.data.FireproofWebsiteRepository
import com.duckduckgo.app.global.DefaultDispatcherProvider
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.global.db.AppDatabase
import com.duckduckgo.app.global.exception.RootExceptionFinder
import com.duckduckgo.app.statistics.pixels.ExceptionPixel
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.statistics.store.OfflinePixelCountDataStore
import com.duckduckgo.cookies.impl.CookiesPixelName.COOKIE_DATABASE_EXCEPTION_OPEN_ERROR
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*

@ExperimentalCoroutinesApi
class SQLCookieRemoverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val cookieManager = CookieManager.getInstance()
    private val fireproofWebsiteDao = db.fireproofWebsiteDao()
    private val mockPixel = mock<Pixel>()
    private val mockOfflinePixelCountDataStore = mock<OfflinePixelCountDataStore>()
    private val webViewDatabaseLocator = WebViewDatabaseLocator(context)

    @After
    fun after() = runBlocking {
        removeExistingCookies()
        db.close()
    }

    @Test
    fun whenCookiesStoredAndRemoveExecutedThenResultTrue() = runTest {
        givenDatabaseWithCookies()
        val sqlCookieRemover = givenSQLCookieRemover()

        val success = sqlCookieRemover.removeCookies()

        assertTrue(success)
    }

    @Test
    fun whenNoCookiesStoredAndRemoveExecutedThenResultTrue() = runTest {
        val sqlCookieRemover = givenSQLCookieRemover()

        val success = sqlCookieRemover.removeCookies()

        assertTrue(success)
    }

    @Test
    fun whenUserHasFireproofWebsitesAndRemoveExecutedThenResultTrue() = runTest {
        val sqlCookieRemover = givenSQLCookieRemover()
        givenDatabaseWithCookies()
        givenFireproofWebsitesStored()

        val success = sqlCookieRemover.removeCookies()

        assertTrue(success)
    }

    @Test
    fun whenDatabasePathNotFoundThenPixelFired() = runTest {
        val mockDatabaseLocator = mock<DatabaseLocator> {
            on { getDatabasePath() } doReturn ""
        }
        val sqlCookieRemover = givenSQLCookieRemover(databaseLocator = mockDatabaseLocator)

        sqlCookieRemover.removeCookies()

        verify(mockOfflinePixelCountDataStore).cookieDatabaseNotFoundCount = 1
    }

    @Test
    fun whenUnableToOpenDatabaseThenPixelFiredAndSaveOfflineCount() = runTest {
        val mockDatabaseLocator = mock<DatabaseLocator> {
            on { getDatabasePath() } doReturn "fakePath"
        }
        val sqlCookieRemover = givenSQLCookieRemover(databaseLocator = mockDatabaseLocator)

        sqlCookieRemover.removeCookies()

        verify(mockOfflinePixelCountDataStore).cookieDatabaseOpenErrorCount = 1
        verify(mockPixel).fire(eq(COOKIE_DATABASE_EXCEPTION_OPEN_ERROR), any(), any())
    }

    private fun givenFireproofWebsitesStored() {
        fireproofWebsiteDao.insert(FireproofWebsiteEntity("example.com"))
    }

    private fun givenDatabaseWithCookies() {
        cookieManager.setCookie("example.com", "da=da")
        cookieManager.flush()
    }

    private suspend fun removeExistingCookies() {
        withContext(Dispatchers.Main) {
            suspendCoroutine<Unit> { continuation ->
                cookieManager.removeAllCookies { continuation.resume(Unit) }
            }
        }
    }

    private fun givenSQLCookieRemover(
        databaseLocator: DatabaseLocator = webViewDatabaseLocator,
        repository: FireproofRepository = FireproofWebsiteRepository(fireproofWebsiteDao, DefaultDispatcherProvider(), mock()),
        offlinePixelCountDataStore: OfflinePixelCountDataStore = mockOfflinePixelCountDataStore,
        exceptionPixel: ExceptionPixel = ExceptionPixel(mockPixel, RootExceptionFinder()),
        dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    ): SQLCookieRemover {
        return SQLCookieRemover(
            databaseLocator,
            repository,
            offlinePixelCountDataStore,
            exceptionPixel,
            dispatcherProvider,
        )
    }
}

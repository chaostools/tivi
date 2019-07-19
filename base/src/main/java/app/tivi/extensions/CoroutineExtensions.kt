/*
 * Copyright 2018 Google LLC
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

package app.tivi.extensions

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex

suspend fun <A, B> Collection<A>.parallelMap(
    concurrency: Int = defaultConcurrency,
    block: suspend (A) -> B
): List<B> = coroutineScope {
    val semaphore = Channel<Unit>(concurrency)
    map { item ->
        async {
            semaphore.send(Unit) // Acquire concurrency permit
            try {
                block(item)
            } finally {
                semaphore.receive() // Release concurrency permit
            }
        }
    }.awaitAll()
}

suspend fun <A> Collection<A>.parallelForEach(
    concurrency: Int = defaultConcurrency,
    block: suspend (A) -> Unit
): Unit = supervisorScope {
    val semaphore = Channel<Unit>(concurrency)
    forEach { item ->
        launch {
            semaphore.send(Unit) // Acquire concurrency permit
            try {
                block(item)
            } finally {
                semaphore.receive() // Release concurrency permit
            }
        }
    }
}

private val defaultConcurrency by lazy(LazyThreadSafetyMode.NONE) {
    Runtime.getRuntime().availableProcessors().coerceAtLeast(3)
}

private val inFlightDeferreds = mutableMapOf<Any, Deferred<*>>()
private val inFlightDeferredsLock = Mutex()

private val inFlightJobs = mutableMapOf<Any, Job>()
private val inFlightJobsLock = Mutex()

private var newCalls = 0
private var cachedCalls = 0

@Suppress("UNCHECKED_CAST")
suspend fun <T> singleAsync(key: Any, action: suspend () -> T) = coroutineScope {
    inFlightDeferredsLock.lock()
    try {
        val inflight = inFlightDeferreds[key]
                ?.takeIf { it.isActive }
                .also { cachedCalls++ }
                ?: async {
                    action()
                }.also {
                    newCalls++
                    inFlightDeferreds[key] = it
                }
        return@coroutineScope inflight as Deferred<T>
    } finally {
        inFlightDeferredsLock.unlock()
    }
}

suspend fun <T> doSingleAsync(key: Any, action: suspend () -> T) = singleAsync(key, action).await()

suspend fun singleLaunch(key: Any, action: suspend () -> Unit) = coroutineScope {
    inFlightJobsLock.lock()
    try {
        return@coroutineScope inFlightJobs[key]
                ?.takeIf { it.isActive }
                .also { cachedCalls++ }
                ?: launch {
                    action()
                }.also {
                    newCalls++
                    inFlightJobs[key] = it
                }
    } finally {
        inFlightJobsLock.unlock()
    }
}

suspend fun doSingleLaunch(key: Any, action: suspend () -> Unit) = singleLaunch(key, action).join()
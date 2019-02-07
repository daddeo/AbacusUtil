/*
 * Copyright (C) 2019 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.http.okhttp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.landawn.abacus.http.ContentFormat;
import com.landawn.abacus.http.HTTP;
import com.landawn.abacus.http.HttpHeaders;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Try;

import okhttp3.CacheControl;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Util;

/**
 * 
 * @since 1.3
 * 
 * @author Haiyang Li
 */
public class OkHttpRequest {
    private static final Logger logger = LoggerFactory.getLogger(OkHttpRequest.class);

    private static final ExecutorService commonPool = new ThreadPoolExecutor(8, 64, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                logger.warn("Starting to shutdown task in ContinuableFuture");

                try {
                    commonPool.shutdown();

                    while (commonPool.isTerminated() == false) {
                        N.sleepUninterruptibly(100);
                    }
                } finally {
                    logger.warn("Completed to shutdown task in ContinuableFuture");
                }
            }
        });
    }

    final OkHttpClient httpClient;
    final Request.Builder builder = new Request.Builder();
    RequestBody body;

    OkHttpRequest(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public static OkHttpRequest create(OkHttpClient httpClient) {
        return new OkHttpRequest(httpClient);
    }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if {@code url} is not a valid HTTP or HTTPS URL. Avoid this
     * exception by calling {@link HttpUrl#parse}; it returns null for invalid URLs.
     */
    public OkHttpRequest url(String url) {
        builder.url(url);
        return this;
    }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if the scheme of {@code url} is not {@code http} or {@code
     * https}.
     */
    public OkHttpRequest url(URL url) {
        builder.url(url);
        return this;
    }

    public OkHttpRequest url(HttpUrl url) {
        builder.url(url);

        return this;
    }

    /**
     * Sets this request's {@code Cache-Control} header, replacing any cache control headers already
     * present. If {@code cacheControl} doesn't define any directives, this clears this request's
     * cache-control headers.
     */
    public OkHttpRequest cacheControl(CacheControl cacheControl) {
        builder.cacheControl(cacheControl);
        return this;
    }

    /**
     * Attaches {@code tag} to the request. It can be used later to cancel the request. If the tag
     * is unspecified or null, the request is canceled by using the request itself as the tag.
     */
    public OkHttpRequest tag(Object tag) {
        builder.tag(tag);
        return this;
    }

    /**
     * Sets the header named {@code name} to {@code value}. If this request already has any headers
     * with that name, they are all replaced.
     */
    public OkHttpRequest header(String name, String value) {
        builder.header(name, value);
        return this;
    }

    public OkHttpRequest headers(String name1, String value1, String name2, String value2) {
        builder.header(name1, value1);
        builder.header(name2, value2);

        return this;
    }

    public OkHttpRequest headers(String name1, String value1, String name2, String value2, String name3, String value3) {
        builder.header(name1, value1);
        builder.header(name2, value2);
        builder.header(name3, value3);

        return this;
    }

    /** 
     * Removes all headers on this builder and adds {@code headers}.
     */
    public OkHttpRequest headers(Headers headers) {
        builder.headers(headers);
        return this;
    }

    /**
     * Adds a header with {@code name} and {@code value}. Prefer this method for multiply-valued
     * headers like "Cookie".
     *
     * <p>Note that for some headers including {@code Content-Length} and {@code Content-Encoding},
     * OkHttp may replace {@code value} with a header derived from the request body.
     */
    public OkHttpRequest addHeader(String name, String value) {
        builder.addHeader(name, value);
        return this;
    }

    public OkHttpRequest removeHeader(String name) {
        builder.removeHeader(name);
        return this;
    }

    public OkHttpRequest body(RequestBody body) {
        this.body = body;
        return this;
    }

    public Response get() throws IOException {
        return execute("GET");
    }

    public <T> T get(Class<T> resultClass) throws IOException {
        return execute(resultClass, "GET");
    }

    public Response post() throws IOException {
        return execute("POST");
    }

    public <T> T post(Class<T> resultClass) throws IOException {
        return execute(resultClass, "POST");
    }

    public Response put() throws IOException {
        return execute("PUT");
    }

    public <T> T put(Class<T> resultClass) throws IOException {
        return execute(resultClass, "PUT");
    }

    public Response delete() throws IOException {
        return execute("DELETE");
    }

    public <T> T delete(Class<T> resultClass) throws IOException {
        return execute(resultClass, "DELETE");
    }

    public Response head() throws IOException {
        return execute("HEAD");
    }

    public Response patch() throws IOException {
        return execute("PATCH");
    }

    protected Response execute(String method) throws IOException {
        body = (body == null && "DELETE".equals(method)) ? Util.EMPTY_REQUEST : body;

        return httpClient.newCall(builder.method(method, body).build()).execute();
    }

    protected <T> T execute(Class<T> resultClass, String method) throws IOException {
        N.checkArgNotNull(resultClass, "resultClass");

        try (Response resp = execute(method)) {
            if (resp.isSuccessful()) {
                final ContentFormat responseContentFormat = HTTP.getContentFormat(resp.header(HttpHeaders.CONTENT_TYPE),
                        resp.header(HttpHeaders.CONTENT_ENCODING));

                final InputStream is = HTTP.wrapInputStream(resp.body().byteStream(), responseContentFormat);

                final Type<Object> type = N.typeOf(resultClass);

                if (byte[].class.equals(resultClass)) {
                    return (T) IOUtil.readBytes(is);
                } else if (type.isSerializable()) {
                    return (T) type.valueOf(IOUtil.readString(is));
                } else {
                    return HTTP.getParser(responseContentFormat).deserialize(resultClass, is);
                }
            } else {
                throw new IOException(resp.code() + ": " + resp.message());
            }
        }
    }

    public ContinuableFuture<Response> asyncGet() throws IOException {
        return asyncGet(commonPool);
    }

    public ContinuableFuture<Response> asyncGet(final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<Response, IOException>() {
            @Override
            public Response call() throws IOException {
                return get();
            }

        }, executor);
    }

    public <T> ContinuableFuture<T> asyncGet(final Class<T> resultClass) throws IOException {
        return asyncGet(resultClass, commonPool);
    }

    public <T> ContinuableFuture<T> asyncGet(final Class<T> resultClass, final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<T, IOException>() {
            @Override
            public T call() throws IOException {
                return get(resultClass);
            }

        }, executor);
    }

    public ContinuableFuture<Response> asyncPost() throws IOException {
        return asyncPost(commonPool);
    }

    public ContinuableFuture<Response> asyncPost(final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<Response, IOException>() {
            @Override
            public Response call() throws IOException {
                return post();
            }

        }, executor);
    }

    public <T> ContinuableFuture<T> asyncPost(final Class<T> resultClass) throws IOException {
        return asyncPost(resultClass, commonPool);
    }

    public <T> ContinuableFuture<T> asyncPost(final Class<T> resultClass, final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<T, IOException>() {
            @Override
            public T call() throws IOException {
                return post(resultClass);
            }

        }, executor);
    }

    public ContinuableFuture<Response> asyncPut() throws IOException {
        return asyncPut(commonPool);
    }

    public ContinuableFuture<Response> asyncPut(final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<Response, IOException>() {
            @Override
            public Response call() throws IOException {
                return put();
            }

        }, executor);
    }

    public <T> ContinuableFuture<T> asyncPut(final Class<T> resultClass) throws IOException {
        return asyncPut(resultClass, commonPool);
    }

    public <T> ContinuableFuture<T> asyncPut(final Class<T> resultClass, final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<T, IOException>() {
            @Override
            public T call() throws IOException {
                return put(resultClass);
            }

        }, executor);
    }

    public ContinuableFuture<Response> asyncDelete() throws IOException {
        return asyncDelete(commonPool);
    }

    public ContinuableFuture<Response> asyncDelete(final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<Response, IOException>() {
            @Override
            public Response call() throws IOException {
                return delete();
            }

        }, executor);
    }

    public <T> ContinuableFuture<T> asyncDelete(final Class<T> resultClass) throws IOException {
        return asyncDelete(resultClass, commonPool);
    }

    public <T> ContinuableFuture<T> asyncDelete(final Class<T> resultClass, final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<T, IOException>() {
            @Override
            public T call() throws IOException {
                return delete(resultClass);
            }

        }, executor);
    }

    public ContinuableFuture<Response> asyncHead() throws IOException {
        return asyncHead(commonPool);
    }

    public ContinuableFuture<Response> asyncHead(final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<Response, IOException>() {
            @Override
            public Response call() throws IOException {
                return head();
            }

        }, executor);
    }

    public ContinuableFuture<Response> asyncPatch() throws IOException {
        return asyncPatch(commonPool);
    }

    public ContinuableFuture<Response> asyncPatch(final Executor executor) throws IOException {
        return ContinuableFuture.call(new Try.Callable<Response, IOException>() {
            @Override
            public Response call() throws IOException {
                return patch();
            }

        }, executor);
    }
}

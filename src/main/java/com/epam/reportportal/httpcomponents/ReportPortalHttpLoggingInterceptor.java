/*
 * Copyright 2022 EPAM Systems
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

package com.epam.reportportal.httpcomponents;

import com.epam.reportportal.formatting.AbstractHttpFormatter;
import com.epam.reportportal.formatting.http.converters.DefaultCookieConverter;
import com.epam.reportportal.formatting.http.converters.DefaultHttpHeaderConverter;
import com.epam.reportportal.formatting.http.converters.DefaultUriConverter;
import com.epam.reportportal.formatting.http.entities.BodyType;
import com.epam.reportportal.formatting.http.entities.Cookie;
import com.epam.reportportal.formatting.http.entities.Header;
import com.epam.reportportal.httpcomponents.support.HttpEntityFactory;
import com.epam.reportportal.listeners.LogLevel;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.protocol.HttpContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

public class ReportPortalHttpLoggingInterceptor extends AbstractHttpFormatter<ReportPortalHttpLoggingInterceptor>
		implements HttpRequestInterceptor, HttpResponseInterceptor {

	private final List<Predicate<HttpRequest>> requestFilters = new CopyOnWriteArrayList<>();
	private final List<Predicate<HttpResponse>> responseFilters = new CopyOnWriteArrayList<>();

	/**
	 * Create a Logging Interceptor with the specific log level and converters.
	 *
	 * @param defaultLogLevel           log level on which Apache HttpComponents requests/responses will appear on
	 *                                  Report Portal
	 * @param headerConvertFunction     if you want to preprocess your HTTP Headers before they appear on Report Portal
	 *                                  provide this custom function for the class, default function formats it like
	 *                                  that: <code>header.getName() + ": " + header.getValue()</code>
	 * @param partHeaderConvertFunction the same as for HTTP Headers, but for parts in Multipart request
	 * @param cookieConvertFunction     the same as 'headerConvertFunction' param but for Cookies, default function
	 *                                  formats Cookies with <code>toString</code> method
	 * @param uriConverterFunction      the same as 'headerConvertFunction' param but for URI, default function returns
	 *                                  URI "as is"
	 */
	protected ReportPortalHttpLoggingInterceptor(@Nonnull LogLevel defaultLogLevel,
			@Nullable Function<Header, String> headerConvertFunction,
			@Nullable Function<Header, String> partHeaderConvertFunction,
			@Nullable Function<Cookie, String> cookieConvertFunction,
			@Nullable Function<String, String> uriConverterFunction) {
		super(defaultLogLevel,
				headerConvertFunction,
				partHeaderConvertFunction,
				cookieConvertFunction,
				uriConverterFunction
		);
	}

	/**
	 * Create a Logging Interceptor with the specific log level and converters.
	 *
	 * @param defaultLogLevel           log level on which Apache HttpComponents requests/responses will appear on
	 *                                  Report Portal
	 * @param headerConvertFunction     if you want to preprocess your HTTP Headers before they appear on Report Portal
	 *                                  provide this custom function for the class, default function formats it like
	 *                                  that: <code>header.getName() + ": " + header.getValue()</code>
	 * @param partHeaderConvertFunction the same as for HTTP Headers, but for parts in Multipart request
	 * @param cookieConvertFunction     the same as 'headerConvertFunction' param but for Cookies, default function
	 *                                  formats Cookies with <code>toString</code> method
	 */
	public ReportPortalHttpLoggingInterceptor(@Nonnull LogLevel defaultLogLevel,
			@Nullable Function<Header, String> headerConvertFunction,
			@Nullable Function<Header, String> partHeaderConvertFunction,
			@Nullable Function<Cookie, String> cookieConvertFunction) {
		this(defaultLogLevel,
				headerConvertFunction,
				partHeaderConvertFunction,
				cookieConvertFunction,
				DefaultUriConverter.INSTANCE
		);
	}

	/**
	 * Create a Logging Interceptor with the specific log level and header converters.
	 *
	 * @param defaultLogLevel           log level on which Apache HttpComponents requests/responses will appear on
	 *                                  Report Portal
	 * @param headerConvertFunction     if you want to preprocess your HTTP Headers before they appear on Report Portal
	 *                                  provide this custom function for the class, default function formats it like
	 *                                  that: <code>header.getName() + ": " + header.getValue()</code>
	 * @param partHeaderConvertFunction the same as for HTTP Headers, but for parts in Multipart request
	 */
	public ReportPortalHttpLoggingInterceptor(@Nonnull LogLevel defaultLogLevel,
			@Nullable Function<Header, String> headerConvertFunction,
			@Nullable Function<Header, String> partHeaderConvertFunction) {
		this(defaultLogLevel, headerConvertFunction, partHeaderConvertFunction, DefaultCookieConverter.INSTANCE);
	}

	/**
	 * Create a Logging Interceptor with the specific log level.
	 *
	 * @param defaultLogLevel log level on which Apache HttpComponents requests/responses will appear on Report Portal
	 */
	public ReportPortalHttpLoggingInterceptor(@Nonnull LogLevel defaultLogLevel) {
		this(defaultLogLevel, DefaultHttpHeaderConverter.INSTANCE, DefaultHttpHeaderConverter.INSTANCE);
	}

	@Override
	public void process(HttpRequest request, HttpContext context) {
		if (requestFilters.stream().anyMatch(f -> f.test(request))) {
			return;
		}
		emitLog(HttpEntityFactory.createHttpRequestFormatter(request,
				context,
				uriConverter,
				headerConverter,
				cookieConverter,
				contentPrettiers,
				partHeaderConverter,
				bodyTypeMap
		));
	}

	@Override
	public void process(HttpResponse response, HttpContext context) {
		if (responseFilters.stream().anyMatch(f -> f.test(response))) {
			return;
		}
		if (!response.getEntity().isRepeatable()) {
			try {
				BufferedHttpEntity bufferedEntity = new BufferedHttpEntity(response.getEntity());
				response.setEntity(bufferedEntity);
			} catch (Exception ignore) {
				// will be logged on reading attempt inside HttpEntityFactory class
			}
		}
		emitLog(HttpEntityFactory.createHttpResponseFormatter(response,
				context,
				headerConverter,
				cookieConverter,
				contentPrettiers,
				bodyTypeMap
		));
	}

	public ReportPortalHttpLoggingInterceptor setBodyTypeMap(@Nonnull Map<String, BodyType> typeMap) {
		this.bodyTypeMap = Collections.unmodifiableMap(new HashMap<>(typeMap));
		return this;
	}

	public ReportPortalHttpLoggingInterceptor setContentPrettiers(
			@Nonnull Map<String, Function<String, String>> contentPrettiers) {
		this.contentPrettiers = Collections.unmodifiableMap(new HashMap<>(contentPrettiers));
		return this;
	}

	public ReportPortalHttpLoggingInterceptor addRequestFilter(@Nonnull Predicate<HttpRequest> requestFilter) {
		requestFilters.add(requestFilter);
		return this;
	}

	public ReportPortalHttpLoggingInterceptor addResponseFilter(@Nonnull Predicate<HttpResponse> responseFilter) {
		responseFilters.add(responseFilter);
		return this;
	}
}

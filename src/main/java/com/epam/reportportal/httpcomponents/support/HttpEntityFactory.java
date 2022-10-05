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

package com.epam.reportportal.httpcomponents.support;

import com.epam.reportportal.formatting.http.HttpFormatter;
import com.epam.reportportal.formatting.http.HttpRequestFormatter;
import com.epam.reportportal.formatting.http.HttpResponseFormatter;
import com.epam.reportportal.formatting.http.entities.BodyType;
import com.epam.reportportal.formatting.http.entities.Cookie;
import com.epam.reportportal.formatting.http.entities.Header;
import com.epam.reportportal.formatting.http.entities.Param;
import com.epam.reportportal.listeners.LogLevel;
import com.epam.reportportal.service.ReportPortal;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.*;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.epam.reportportal.formatting.http.HttpFormatUtils.*;
import static java.util.Optional.ofNullable;

public class HttpEntityFactory {

	@Nonnull
	private static Charset getCharset(@Nonnull HttpEntity httpEntity) {
		return ofNullable(httpEntity.getContentType()).flatMap(h -> toKeyValue(h.getValue()).filter(p -> "charset".equalsIgnoreCase(
				p.getKey())).findAny()).map(Pair::getValue).map(Charset::forName).orElse(StandardCharsets.UTF_8);
	}

	@Nullable
	private static byte[] toBytes(@Nonnull HttpEntity httpEntity) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			httpEntity.writeTo(baos);
			return baos.toByteArray();
		} catch (IOException e) {
			ReportPortal.emitLog("Unable to read HTTP entity: " + ExceptionUtils.getStackTrace(e),
					LogLevel.WARN.name(),
					Calendar.getInstance().getTime()
			);
			return null;
		}
	}

	@Nullable
	private static String toString(@Nonnull HttpEntity httpEntity, @Nonnull Charset charset) {
		return ofNullable(toBytes(httpEntity)).map(b -> new String(b, charset)).orElse(null);
	}

	@Nullable
	private static String toString(@Nonnull HttpEntity httpEntity) {
		Charset charset = getCharset(httpEntity);
		return toString(httpEntity, charset);
	}

	@Nullable
	private static List<Param> toParams(@Nonnull HttpEntity httpEntity) {
		Charset charset = getCharset(httpEntity);
		String body = toString(httpEntity, StandardCharsets.ISO_8859_1);

		return ofNullable(body).map(b -> b.split("&")).map(elements -> Arrays.stream(elements).map(element -> {
			String[] param = element.split("=", 2);
			try {
				if (param.length > 1) {
					return new Param(URLDecoder.decode(param[0], charset.name()),
							URLDecoder.decode(param[1], charset.name())
					);
				}
				return new Param(URLDecoder.decode(param[0], charset.name()), "");
			} catch (UnsupportedEncodingException ignore) {
			}
			return null;
		}).filter(Objects::nonNull).collect(Collectors.toList())).orElse(Collections.emptyList());
	}

	@Nonnull
	public static HttpFormatter createHttpRequestFormatter(@Nonnull HttpRequest request, @Nonnull HttpContext context,
			@Nullable Function<String, String> uriConverter, @Nullable Function<Header, String> headerConverter,
			@Nullable Function<Cookie, String> cookieConverter,
			@Nullable Map<String, Function<String, String>> contentPrettiers,
			@Nullable Function<Header, String> partHeaderConverter, @Nonnull Map<String, BodyType> bodyTypeMap) {
		HttpRequestFormatter.Builder builder = new HttpRequestFormatter.Builder(request.getRequestLine().getMethod(),
				ofNullable(context.getAttribute(HttpCoreContext.HTTP_TARGET_HOST)).orElse("") + request.getRequestLine()
						.getUri()
		);
		ofNullable(request.getAllHeaders()).ifPresent(headers -> Arrays.stream(headers)
				.filter(h -> !isCookie(h.getName()))
				.forEach(h -> builder.addHeader(h.getName(), h.getValue())));
		ofNullable(request.getAllHeaders()).ifPresent(headers -> Arrays.stream(headers)
				.filter(h -> isCookie(h.getName()))
				.flatMap(h -> toKeyValue(h.getValue()))
				.forEach(h -> builder.addCookie(h.getKey(), h.getValue())));
		builder.uriConverter(uriConverter)
				.headerConverter(headerConverter)
				.cookieConverter(cookieConverter)
				.prettiers(contentPrettiers);

		if (!(request instanceof HttpEntityEnclosingRequest)) {
			return builder.build();
		}

		HttpEntity httpEntity = ((HttpEntityEnclosingRequest) request).getEntity();
		if (httpEntity == null) {
			return builder.build();
		}

		String contentType = ofNullable(httpEntity.getContentType()).map(NameValuePair::getValue).orElse(null);
		String type = getMimeType(contentType);
		BodyType bodyType = getBodyType(contentType, bodyTypeMap);
		switch (bodyType) {
			case TEXT:
				builder.bodyText(type, toString(httpEntity));
				break;
			case FORM:
				builder.bodyParams(toParams(httpEntity));

		}
		return builder.build();
	}

	@Nonnull
	public static HttpFormatter createHttpResponseFormatter(@Nonnull HttpResponse response,
			@Nonnull HttpContext context, @Nullable Function<Header, String> headerConverter,
			@Nullable Function<Cookie, String> cookieConverter,
			@Nullable Map<String, Function<String, String>> contentPrettiers,
			@Nonnull Map<String, BodyType> bodyTypeMap) {
		StatusLine statusLine = response.getStatusLine();
		HttpResponseFormatter.Builder builder = new HttpResponseFormatter.Builder(statusLine.getStatusCode(),
				statusLine.getProtocolVersion().toString() + " " + statusLine.getStatusCode() + " "
						+ statusLine.getReasonPhrase()
		);
		ofNullable(response.getAllHeaders()).ifPresent(headers -> Arrays.stream(headers)
				.filter(h -> !isSetCookie(h.getName()))
				.forEach(h -> builder.addHeader(h.getName(), h.getValue())));
		ofNullable(response.getAllHeaders()).ifPresent(headers -> Arrays.stream(headers)
				.filter(h -> isSetCookie(h.getName()))
				.forEach(h -> builder.addCookie(toCookie(h.getValue()))));
		builder.headerConverter(headerConverter).cookieConverter(cookieConverter).prettiers(contentPrettiers);

		HttpEntity httpEntity = response.getEntity();
		if (httpEntity == null) {
			return builder.build();
		}

		String contentType = ofNullable(httpEntity.getContentType()).map(NameValuePair::getValue).orElse(null);
		String type = getMimeType(contentType);
		BodyType bodyType = getBodyType(contentType, bodyTypeMap);
		if (BodyType.TEXT == bodyType) {
			builder.bodyText(type, toString(httpEntity));
		} else {
			builder.bodyBytes(type, toBytes(httpEntity));
		}
		return builder.build();
	}
}

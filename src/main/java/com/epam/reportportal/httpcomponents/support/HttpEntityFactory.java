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

import com.epam.reportportal.formatting.http.*;
import com.epam.reportportal.formatting.http.entities.BodyType;
import com.epam.reportportal.formatting.http.entities.Cookie;
import com.epam.reportportal.formatting.http.entities.Header;
import com.epam.reportportal.formatting.http.entities.Param;
import com.epam.reportportal.listeners.LogLevel;
import com.epam.reportportal.service.ReportPortal;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.*;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

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

	@Nonnull
	private static List<Param> toParams(@Nonnull HttpEntity httpEntity) {
		String body = toString(httpEntity, StandardCharsets.ISO_8859_1);
		return HttpFormatUtils.toForm(body,
				ofNullable(httpEntity.getContentType()).map(NameValuePair::getValue).orElse(null)
		);
	}

	@Nullable
	private static String getBoundary(@Nonnull HttpEntity httpEntity) {
		return ofNullable(httpEntity.getContentType()).flatMap(h -> toKeyValue(h.getValue()).filter(p -> "boundary".equalsIgnoreCase(
				p.getKey())).findAny()).map(Pair::getValue).orElse(null);
	}

	public static boolean isMatch(@Nonnull byte[] pattern, @Nonnull byte[] input, int pos) {
		for (int i = 0; i < pattern.length; i++) {
			if (pattern[i] != input[pos + i]) {
				return false;
			}
		}
		return true;
	}

	public static List<byte[]> split(byte[] pattern, byte[] input) {
		List<byte[]> l = new ArrayList<>();
		int blockStart = 0;
		for (int i = 0; i < input.length; i++) {
			if (isMatch(pattern, input, i)) {
				l.add(Arrays.copyOfRange(input, blockStart, i));
				blockStart = i + pattern.length;
				i = blockStart;
			}
		}
		l.add(Arrays.copyOfRange(input, blockStart, input.length));
		return l;
	}

	@Nonnull
	private static List<HttpPartFormatter> toParts(@Nonnull HttpEntity httpEntity) {
		return ofNullable(getBoundary(httpEntity)).map(boundary -> {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try {
				httpEntity.writeTo(baos);
			} catch (IOException ignore) {
				// The entity should be already cached at this point
			}
			ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());

			return Collections.<HttpPartFormatter>emptyList();
		}).orElse(Collections.emptyList());
	}

	@Nullable
	private static HttpEntity cacheEntity(@Nonnull HttpEntity httpEntity) {
		if (!httpEntity.isRepeatable()) {
			try {
				return new BufferedHttpEntity(httpEntity);
			} catch (IOException e) {
				ReportPortal.emitLog("Unable to read HTTP entity: " + ExceptionUtils.getStackTrace(e),
						LogLevel.WARN.name(),
						Calendar.getInstance().getTime()
				);
				return null;
			}
		}
		return httpEntity;
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

		HttpEntity httpEntity = cacheEntity(((HttpEntityEnclosingRequest) request).getEntity());
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
				break;
			case MULTIPART:
				toParts(httpEntity).forEach(builder::addBodyPart);
				break;
			default:
				builder.bodyBytes(type, toBytes(httpEntity));
		}
		return builder.build();
	}

	@Nonnull
	@SuppressWarnings("unused")
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

		HttpEntity httpEntity = cacheEntity(response.getEntity());
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

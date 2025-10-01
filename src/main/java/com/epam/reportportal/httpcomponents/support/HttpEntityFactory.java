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

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.epam.reportportal.formatting.http.HttpFormatUtils.*;
import static java.util.Optional.ofNullable;

public class HttpEntityFactory {

	@Nonnull
	private static Charset getCharset(@Nullable String contentTypeValue) {
		return ofNullable(contentTypeValue).flatMap(h -> toKeyValue(h).filter(p -> "charset".equalsIgnoreCase(p.getKey())).findAny())
				.map(Pair::getValue)
				.map(Charset::forName)
				.orElse(StandardCharsets.UTF_8);
	}

	@Nonnull
	private static Charset getCharset(@Nonnull HttpEntity httpEntity) {
		return ofNullable(httpEntity.getContentType()).map(h -> getCharset(h.getValue())).orElse(StandardCharsets.UTF_8);
	}

	@Nullable
	private static byte[] toBytes(@Nonnull HttpEntity httpEntity) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			httpEntity.writeTo(baos);
			return baos.toByteArray();
		} catch (IOException e) {
			ReportPortal.emitLog(
					"Unable to read HTTP entity: " + ExceptionUtils.getStackTrace(e),
					LogLevel.WARN.name(),
					java.time.Instant.now()
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
		return HttpFormatUtils.toForm(body, ofNullable(httpEntity.getContentType()).map(NameValuePair::getValue).orElse(null));
	}

	@Nullable
	private static String getBoundary(@Nonnull HttpEntity httpEntity) {
		return ofNullable(httpEntity.getContentType()).flatMap(h -> toKeyValue(h.getValue()).filter(p -> "boundary".equalsIgnoreCase(p.getKey()))
				.findAny()).map(Pair::getValue).orElse(null);
	}

	private static boolean isMatch(@Nonnull byte[] pattern, @Nonnull byte[] input, int pos) {
		for (int i = 0; i < pattern.length; i++) {
			if (pattern[i] != input[pos + i]) {
				return false;
			}
		}
		return true;
	}

	private static List<byte[]> split(byte[] pattern, byte[] input, int limit) {
		List<byte[]> result = new ArrayList<>();
		if (limit == 0) {
			return result;
		}
		int blockStart = 0;
		int matchCount = 0;
		for (int i = 0; i < input.length; i++) {
			if (isMatch(pattern, input, i)) {
				result.add(Arrays.copyOfRange(input, blockStart, i));
				blockStart = i + pattern.length;
				i = blockStart;
				if (limit > 0) {
					if (++matchCount >= limit) {
						break;
					}
				}
			}
		}
		result.add(Arrays.copyOfRange(input, blockStart, input.length));
		return result;
	}

	@Nonnull
	private static List<HttpPartFormatter> toParts(@Nonnull HttpEntity httpEntity, @Nonnull Map<String, BodyType> bodyTypeMap,
			@Nullable Function<Header, String> partHeaderConverter) {
		return ofNullable(getBoundary(httpEntity)).map(boundary -> {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			try {
				httpEntity.writeTo(baos);
			} catch (IOException ignore) {
				// The entity should be already cached at this point
			}
			List<byte[]> parts = split(("--" + boundary).getBytes(Charset.defaultCharset()), baos.toByteArray(), -1);
			if (Arrays.equals(parts.get(parts.size() - 1), new byte[] { '-', '-', '\r', '\n' })) {
				parts = parts.subList(0, parts.size() - 1);
			}
			return parts.stream().skip(1).map(bytes -> Arrays.copyOfRange(bytes, 2, bytes.length - 2)).map(bytes -> {
				List<byte[]> headerBody = split("\r\n\r\n".getBytes(Charset.defaultCharset()), bytes, 1);
				List<Header> headers = split("\r\n".getBytes(Charset.defaultCharset()), headerBody.get(0), -1).stream()
						.map(headerBytes -> new String(headerBytes, StandardCharsets.ISO_8859_1))
						.map(HttpFormatUtils::toHeader)
						.collect(Collectors.toList());
				return Pair.of(headers, headerBody.get(1));
			}).map(part -> {
				String contentType = part.getKey()
						.stream()
						.filter(h -> HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(h.getName()))
						.findAny()
						.map(Header::getValue)
						.orElse(null);
				Charset charset = getCharset(contentType);
				String mimeType = getMimeType(contentType);
				BodyType bodyType = getBodyType(contentType, bodyTypeMap);
				HttpPartFormatter.Builder partBuilder;
				if (BodyType.TEXT == bodyType) {
					partBuilder = new HttpPartFormatter.Builder(
							HttpPartFormatter.PartType.TEXT,
							mimeType,
							new String(part.getValue(), charset)
					);
				} else {
					partBuilder = new HttpPartFormatter.Builder(HttpPartFormatter.PartType.BINARY, mimeType, part.getValue());
				}
				part.getKey().forEach(partBuilder::addHeader);
				partBuilder.charset(charset.name());
				partBuilder.headerConverter(partHeaderConverter);
				return partBuilder.build();
			}).collect(Collectors.toList());
		}).orElse(Collections.emptyList());
	}

	@Nullable
	private static HttpEntity cacheEntity(@Nullable HttpEntity httpEntity) {
		if (httpEntity == null) {
			return null;
		}
		if (!httpEntity.isRepeatable()) {
			try {
				return new BufferedHttpEntity(httpEntity);
			} catch (IOException e) {
				ReportPortal.emitLog(
						"Unable to read HTTP entity: " + ExceptionUtils.getStackTrace(e),
						LogLevel.WARN.name(),
						java.time.Instant.now()
				);
				return null;
			}
		}
		return httpEntity;
	}

	@Nonnull
	private static HttpResponse cacheEntity(@Nonnull HttpResponse response) {
		response.setEntity(cacheEntity(response.getEntity()));
		return response;
	}

	@Nonnull
	private static HttpEntityEnclosingRequest cacheEntity(@Nonnull HttpEntityEnclosingRequest request) {
		request.setEntity(cacheEntity(request.getEntity()));
		return request;
	}

	@Nonnull
	public static HttpFormatter createHttpRequestFormatter(@Nonnull HttpRequest request, @Nonnull HttpContext context,
			@Nullable Function<String, String> uriConverter, @Nullable Function<Header, String> headerConverter,
			@Nullable Function<Cookie, String> cookieConverter, @Nullable Function<Param, String> paramConverter,
			@Nullable Map<String, Function<String, String>> contentPrettifiers, @Nullable Function<Header, String> partHeaderConverter,
			@Nonnull Map<String, BodyType> bodyTypeMap) {
		HttpRequestFormatter.Builder builder = new HttpRequestFormatter.Builder(
				request.getRequestLine().getMethod(),
				ofNullable(context.getAttribute(HttpCoreContext.HTTP_TARGET_HOST)).orElse("") + request.getRequestLine().getUri()
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
				.paramConverter(paramConverter)
				.prettifiers(contentPrettifiers);

		if (!(request instanceof HttpEntityEnclosingRequest)) {
			return builder.build();
		}

		HttpEntity httpEntity = cacheEntity(((HttpEntityEnclosingRequest) request)).getEntity();
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
				toParts(httpEntity, bodyTypeMap, partHeaderConverter).forEach(builder::addBodyPart);
				break;
			default:
				builder.bodyBytes(type, toBytes(httpEntity));
		}
		return builder.build();
	}

	@Nonnull
	@SuppressWarnings("unused")
	public static HttpFormatter createHttpResponseFormatter(@Nonnull HttpResponse response, @Nonnull HttpContext context,
			@Nullable Function<Header, String> headerConverter, @Nullable Function<Cookie, String> cookieConverter,
			@Nullable Map<String, Function<String, String>> contentPrettifiers, @Nonnull Map<String, BodyType> bodyTypeMap) {
		StatusLine statusLine = response.getStatusLine();
		HttpResponseFormatter.Builder builder = new HttpResponseFormatter.Builder(
				statusLine.getStatusCode(),
				statusLine.getProtocolVersion().toString() + " " + statusLine.getStatusCode() + " " + statusLine.getReasonPhrase()
		);
		ofNullable(response.getAllHeaders()).ifPresent(headers -> Arrays.stream(headers)
				.filter(h -> !isSetCookie(h.getName()))
				.forEach(h -> builder.addHeader(h.getName(), h.getValue())));
		ofNullable(response.getAllHeaders()).ifPresent(headers -> Arrays.stream(headers)
				.filter(h -> isSetCookie(h.getName()))
				.forEach(h -> builder.addCookie(toCookie(h.getValue()))));
		builder.headerConverter(headerConverter).cookieConverter(cookieConverter).prettifiers(contentPrettifiers);

		HttpEntity httpEntity = cacheEntity(response).getEntity();
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

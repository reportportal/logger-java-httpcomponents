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

import com.epam.reportportal.formatting.http.prettiers.JsonPrettier;
import com.epam.reportportal.formatting.http.prettiers.XmlPrettier;
import com.epam.reportportal.listeners.ItemStatus;
import com.epam.reportportal.listeners.LogLevel;
import com.epam.reportportal.message.ReportPortalMessage;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.service.step.StepReporter;
import com.epam.reportportal.utils.files.Utils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.http.*;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.FormBodyPart;
import org.apache.http.entity.mime.FormBodyPartBuilder;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.epam.reportportal.formatting.http.Constants.*;
import static java.util.Optional.ofNullable;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ReportPortalHttpLoggingInterceptorTest {

	private static final String IMAGE = "pug/lucky.jpg";
	private static final String HTML_TYPE = "text/html";
	private static final String JSON_TYPE = "application/json";
	private static final String METHOD = "POST";
	private static final String HOST = "http://docker.local:8080";
	private static final String URI = "/app";
	private static final String URL = HOST + URI;
	private static final int STATUS_CODE = 201;
	private static final String EMPTY_REQUEST = "**>>> REQUEST**\n" + METHOD + " to " + URL;
	private static final String EMPTY_RESPONSE = "**<<< RESPONSE**\nHTTP/1.1 " + STATUS_CODE + " Created";
	private static final String HTTP_HEADER = HttpHeaders.CONTENT_TYPE;
	private static final String HTTP_HEADER_VALUE = JSON_TYPE;

	private static final HttpContext CONTEXT = new BasicHttpContext();

	static {
		CONTEXT.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, HOST);
	}

	public static Iterable<Object[]> requestData() {
		return Arrays.asList(new Object[] { JSON_TYPE, "{\"object\": {\"key\": \"value\"}}", "{\"object\": {\"key\": \"value\"}}",
						JsonPrettier.INSTANCE, null, null },
				new Object[] { "application/xml", "<test><key><value>value</value></key></test>",
						"<test><key><value>value</value></key></test>", XmlPrettier.INSTANCE, null, null }
		);
	}

	private void runChain(HttpRequest request, HttpResponse response, Consumer<MockedStatic<ReportPortal>> mocks,
			ReportPortalHttpLoggingInterceptor interceptor) {
		try (MockedStatic<ReportPortal> utilities = Mockito.mockStatic(ReportPortal.class)) {
			mocks.accept(utilities);
			interceptor.process(request, CONTEXT);
			interceptor.process(response, CONTEXT);
		}
	}

	private void runChain(HttpRequest request, HttpResponse response, Consumer<MockedStatic<ReportPortal>> mocks) {
		runChain(request, response, mocks, new ReportPortalHttpLoggingInterceptor(LogLevel.INFO));
	}

	private List<String> runChainTextMessageCapture(HttpRequest request, HttpResponse response) {
		ArgumentCaptor<String> logCapture = ArgumentCaptor.forClass(String.class);
		runChain(request,
				response,
				mock -> mock.when(() -> ReportPortal.emitLog(logCapture.capture(), anyString(), any(Date.class))).thenReturn(Boolean.TRUE)
		);
		return logCapture.getAllValues();
	}

	private List<ReportPortalMessage> runChainBinaryMessageCapture(HttpRequest request, HttpResponse response) {
		ArgumentCaptor<ReportPortalMessage> logCapture = ArgumentCaptor.forClass(ReportPortalMessage.class);
		runChain(request,
				response,
				mock -> mock.when(() -> ReportPortal.emitLog(logCapture.capture(), anyString(), any(Date.class))).thenReturn(Boolean.TRUE)
		);
		return logCapture.getAllValues();
	}

	private Triple<List<String>, List<String>, List<ReportPortalMessage>> runChainComplexMessageCapture(HttpRequest request,
			HttpResponse response, ReportPortalHttpLoggingInterceptor interceptor) {
		ArgumentCaptor<String> stepCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> stringArgumentCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<ReportPortalMessage> messageArgumentCaptor = ArgumentCaptor.forClass(ReportPortalMessage.class);
		try (MockedStatic<Launch> utilities = Mockito.mockStatic(Launch.class)) {
			Launch launch = mock(Launch.class);
			StepReporter reporter = mock(StepReporter.class);
			utilities.when(Launch::currentLaunch).thenReturn(launch);
			when(launch.getStepReporter()).thenReturn(reporter);
			doNothing().when(reporter).sendStep(any(ItemStatus.class), stepCaptor.capture());
			runChain(request, response, mock -> {
				mock.when(() -> ReportPortal.emitLog(stringArgumentCaptor.capture(), anyString(), any(Date.class)))
						.thenReturn(Boolean.TRUE);
				mock.when(() -> ReportPortal.emitLog(messageArgumentCaptor.capture(), anyString(), any(Date.class)))
						.thenReturn(Boolean.TRUE);
			}, interceptor);
		}
		return Triple.of(stepCaptor.getAllValues(), stringArgumentCaptor.getAllValues(), messageArgumentCaptor.getAllValues());
	}

	@SuppressWarnings("SameParameterValue")
	private Triple<List<String>, List<String>, List<ReportPortalMessage>> runChainComplexMessageCapture(HttpRequest request,
			HttpResponse response) {
		return runChainComplexMessageCapture(request, response, new ReportPortalHttpLoggingInterceptor(LogLevel.INFO));
	}

	private static HttpRequest mockBasicRequest(@Nonnull Collection<Pair<String, String>> headers, @Nullable HttpEntity body) {
		HttpPost request = new HttpPost(URI);
		headers.forEach(h -> request.addHeader(h.getKey(), h.getValue()));
		if (body != null) {
			request.setEntity(body);
		}
		return request;
	}

	private static HttpRequest mockBasicRequest(@Nonnull Collection<Pair<String, String>> headers) {
		return mockBasicRequest(headers, null);
	}

	private static HttpRequest mockBasicRequest() {
		return mockBasicRequest(Collections.emptyList());
	}

	private static HttpResponse createBasicResponse(@Nonnull Collection<Pair<String, String>> headers, @Nullable HttpEntity body) {
		StatusLine statusLine = mock(StatusLine.class);
		when(statusLine.getStatusCode()).thenReturn(STATUS_CODE);
		when(statusLine.getReasonPhrase()).thenReturn("Created");
		when(statusLine.getProtocolVersion()).thenReturn(new ProtocolVersion("HTTP", 1, 1));
		HttpResponse response = new BasicHttpResponse(statusLine);
		headers.forEach(h -> response.addHeader(h.getKey(), h.getValue()));
		if (body != null) {
			response.setEntity(body);
		}
		return response;
	}

	private static HttpResponse createBasicResponse(@Nonnull Collection<Pair<String, String>> headers) {
		return createBasicResponse(headers, null);
	}

	private static HttpResponse createBasicResponse() {
		return createBasicResponse(Collections.emptyList());
	}

	@Test
	public void test_logger_null_values() {
		HttpRequest request = mockBasicRequest();
		HttpResponse response = createBasicResponse();

		List<Object> logs = new ArrayList<>();
		logs.addAll(runChainBinaryMessageCapture(request, response));
		logs.addAll(runChainTextMessageCapture(request, response));
		assertThat(logs, hasSize(2)); // Request + Response

		assertThat(logs.get(0), equalTo(EMPTY_REQUEST));
		assertThat(logs.get(1), equalTo(EMPTY_RESPONSE));
	}

	@ParameterizedTest
	@MethodSource("requestData")
	public void test_logger_text_body(String mimeType, String requestBodyStr, String responseBodyStr, Function<String, String> prettier) {
		Charset charset = StandardCharsets.UTF_8;
		HttpEntity requestBody = new ByteArrayEntity(requestBodyStr.getBytes(charset), ContentType.create(mimeType, charset));
		HttpRequest request = mockBasicRequest(Collections.emptyList(), requestBody);

		HttpEntity responseBody = new ByteArrayEntity(requestBodyStr.getBytes(charset), ContentType.create(mimeType, charset));
		HttpResponse response = createBasicResponse(Collections.emptyList(), responseBody);

		List<String> logs = runChainTextMessageCapture(request, response);
		assertThat(logs, hasSize(2)); // Request + Response

		String expectedRequest = EMPTY_REQUEST + "\n\n**Body**\n```\n" + prettier.apply(requestBodyStr) + "\n```";
		String requestLog = logs.get(0);
		assertThat(requestLog, equalTo(expectedRequest));

		String expectedResponse = EMPTY_RESPONSE + "\n\n**Body**\n```\n" + prettier.apply(responseBodyStr) + "\n```";
		String responseLog = logs.get(1);
		assertThat(responseLog, equalTo(expectedResponse));
	}

	public static Iterable<Object[]> testTypes() {
		return Arrays.asList(new Object[] { HTML_TYPE }, new Object[] { null });
	}

	@ParameterizedTest
	@MethodSource("testTypes")
	public void test_logger_headers(String contentType) {
		Collection<Pair<String, String>> headers = Collections.singletonList(Pair.of(HTTP_HEADER, HTTP_HEADER_VALUE));
		HttpRequest request = mockBasicRequest(headers);
		HttpResponse response = createBasicResponse(headers);

		List<Object> logs = new ArrayList<>();
		logs.addAll(runChainBinaryMessageCapture(request, response));
		logs.addAll(runChainTextMessageCapture(request, response));
		assertThat(logs, hasSize(2)); // Request + Response

		String headerString = "\n\n**Headers**\n" + HTTP_HEADER + ": " + HTTP_HEADER_VALUE;

		if (contentType == null) {
			assertThat(logs.get(0), equalTo(EMPTY_REQUEST + headerString));
		} else {
			assertThat(logs.get(0), equalTo(EMPTY_REQUEST + headerString));
		}
		assertThat(logs.get(1), equalTo(EMPTY_RESPONSE + headerString));
	}

	@Test
	public void test_logger_cookies() {
		Collection<Pair<String, String>> requestHeaders = Collections.singletonList(Pair.of("Cookie", "test=value"));
		HttpRequest request = mockBasicRequest(requestHeaders);
		String expiryDate = "Tue, 06 Sep 2022 09:32:51 UTC";
		Collection<Pair<String, String>> responseHeaders = Collections.singletonList(Pair.of("Set-cookie",
				"test=value; expires=" + expiryDate + "; path=/; secure; httponly"
		));
		HttpResponse response = createBasicResponse(responseHeaders);

		List<String> logs = runChainTextMessageCapture(request, response);
		assertThat(logs, hasSize(2)); // Request + Response

		String requestHeaderString = "\n\n**Cookies**\n" + "test: value";
		String responseHeaderString = "\n\n**Cookies**\n" + "test: value; Path=/; Secure=true; HttpOnly=true; Expires=" + expiryDate;

		assertThat(logs.get(0), equalTo(EMPTY_REQUEST + requestHeaderString));
		assertThat(logs.get(1), equalTo(EMPTY_RESPONSE + responseHeaderString));
	}

	@ParameterizedTest
	@MethodSource("testTypes")
	public void test_logger_headers_and_cookies(String contentType) {

		Collection<Pair<String, String>> headers = Arrays.asList(Pair.of(HTTP_HEADER, HTTP_HEADER_VALUE),
				Pair.of("Cookie", "test=value; tz=Europe%2FMinsk")
		);
		String expiryDate1 = "Tue, 06 Sep 2022 09:32:51 UTC";
		String expiryDate2 = "Tue, 06 Sep 2022 09:32:51 UTC";
		Collection<Pair<String, String>> responseHeaders = Arrays.asList(Pair.of(HTTP_HEADER, HTTP_HEADER_VALUE),
				Pair.of("Set-cookie", "test=value; comment=test comment; expires=" + expiryDate1 + "; path=/; version=1"),
				Pair.of("Set-cookie", "tz=Europe%2FMinsk; path=/; expires=" + expiryDate2 + "; secure; HttpOnly; SameSite=Lax")
		);
		HttpRequest request = mockBasicRequest(headers);
		HttpResponse response = createBasicResponse(responseHeaders);

		List<Object> logs = new ArrayList<>();
		logs.addAll(runChainBinaryMessageCapture(request, response));
		logs.addAll(runChainTextMessageCapture(request, response));
		assertThat(logs, hasSize(2)); // Request + Response

		String requestHeaderString =
				"\n\n**Headers**\n" + HTTP_HEADER + ": " + HTTP_HEADER_VALUE + "\n\n**Cookies**\n" + "test: value\n" + "tz: Europe/Minsk";

		String responseHeaderString = "\n\n**Headers**\n" + HTTP_HEADER + ": " + HTTP_HEADER_VALUE + "\n\n**Cookies**\n"
				+ "test: value; Comment=test comment; Path=/; Expires=" + expiryDate1 + "; Version=1\n"
				+ "tz: Europe/Minsk; Path=/; Secure=true; HttpOnly=true; Expires=" + expiryDate2 + "; SameSite=Lax";

		if (contentType == null) {
			assertThat(logs.get(0), equalTo(EMPTY_REQUEST + requestHeaderString));
		} else {
			assertThat(logs.get(0), equalTo(EMPTY_REQUEST + requestHeaderString));
		}
		assertThat(logs.get(1), equalTo(EMPTY_RESPONSE + responseHeaderString));
	}

	@Test
	public void test_logger_empty_image_body() {
		HttpEntity requestBody = new ByteArrayEntity(new byte[0], ContentType.IMAGE_JPEG);
		HttpRequest request = mockBasicRequest(Collections.emptyList(), requestBody);

		HttpEntity responseBody = new ByteArrayEntity(new byte[0], ContentType.IMAGE_JPEG);
		HttpResponse response = createBasicResponse(Collections.emptyList(), responseBody);

		List<Object> logs = new ArrayList<>();
		logs.addAll(runChainBinaryMessageCapture(request, response));
		logs.addAll(runChainTextMessageCapture(request, response));
		assertThat(logs, hasSize(2)); // Request + Response
		assertThat(((ReportPortalMessage) logs.get(0)).getMessage(), equalTo(EMPTY_REQUEST));
		assertThat(((ReportPortalMessage) logs.get(1)).getMessage(), equalTo(EMPTY_RESPONSE));
	}

	@SuppressWarnings("SameParameterValue")
	private byte[] getResource(String imagePath) {
		return ofNullable(this.getClass().getClassLoader().getResourceAsStream(imagePath)).map(is -> {
			try {
				return Utils.readInputStreamToBytes(is);
			} catch (IOException e) {
				return null;
			}
		}).orElse(null);
	}

	private static final String IMAGE_TYPE = "image/jpeg";
	private static final String WILDCARD_TYPE = "*/*";

	@ParameterizedTest
	@ValueSource(strings = { IMAGE_TYPE, WILDCARD_TYPE })
	public void test_logger_image_body(String mimeType) throws IOException {
		byte[] image = getResource(IMAGE);
		HttpEntity requestBody = new ByteArrayEntity(image, ContentType.create(mimeType));
		HttpRequest request = mockBasicRequest(Collections.emptyList(), requestBody);

		HttpEntity responseBody = new ByteArrayEntity(image, ContentType.create(mimeType));
		HttpResponse response = createBasicResponse(Collections.emptyList(), responseBody);

		List<ReportPortalMessage> logs = runChainBinaryMessageCapture(request, response);
		assertThat(logs, hasSize(2)); // Request + Response
		assertThat(logs.get(0).getMessage(), equalTo(EMPTY_REQUEST));
		assertThat(logs.get(1).getMessage(), equalTo(EMPTY_RESPONSE));

		assertThat(logs.get(0).getData().getMediaType(), equalTo(mimeType));
		assertThat(logs.get(1).getData().getMediaType(), equalTo(mimeType));

		assertThat(logs.get(0).getData().read(), equalTo(image));
		assertThat(logs.get(1).getData().read(), equalTo(image));
	}

	@Test
	public void test_logger_null_response() {
		HttpRequest request = mockBasicRequest();

		List<String> logs = runChainTextMessageCapture(request, createBasicResponse());
		assertThat(logs, hasSize(2)); // Request + Response
		assertThat(logs.get(0), equalTo(EMPTY_REQUEST));
		assertThat(logs.get(1), equalTo(EMPTY_RESPONSE));
	}

	@Test
	public void test_logger_empty_multipart() {
		HttpRequest request = mockBasicRequest(Collections.emptyList(), MultipartEntityBuilder.create().build());

		List<String> logs = runChainTextMessageCapture(request, createBasicResponse());
		assertThat(logs, hasSize(2)); // Request + Response
		assertThat(logs.get(0), equalTo(EMPTY_REQUEST));
	}

	private FormBodyPart getBinaryPart(ContentType contentType, String filePath) {
		byte[] data = getResource(filePath);
		FormBodyPartBuilder part = FormBodyPartBuilder.create();
		part.setName("file");
		part.setBody(new ByteArrayBody(data, contentType, filePath));
		return part.build();
	}

	@SuppressWarnings("SameParameterValue")
	private HttpEntity getBinaryBody(ContentType contentType, String filePath) {
		return MultipartEntityBuilder.create().addPart(getBinaryPart(contentType, filePath)).build();
	}

	@Test
	public void test_logger_image_multipart() throws IOException {
		byte[] image = getResource(IMAGE);
		ContentType imageType = ContentType.IMAGE_JPEG;
		HttpEntity body = getBinaryBody(imageType, IMAGE);
		HttpRequest request = mockBasicRequest(Collections.emptyList(), body);

		Triple<List<String>, List<String>, List<ReportPortalMessage>> logs = runChainComplexMessageCapture(request, createBasicResponse());
		assertThat(logs.getLeft(), hasSize(1));
		assertThat(logs.getMiddle(), hasSize(1));
		assertThat(logs.getRight(), hasSize(1));

		assertThat(logs.getLeft().get(0), equalTo(EMPTY_REQUEST));
		assertThat(logs.getMiddle().get(0), equalTo(EMPTY_RESPONSE));
		assertThat(logs.getRight().get(0).getMessage(),
				equalTo(HEADERS_TAG + LINE_DELIMITER + "Content-Disposition: form-data; name=\"file\"; filename=\"pug/lucky.jpg\""
						+ LINE_DELIMITER + "Content-Type: image/jpeg" + LINE_DELIMITER + "Content-Transfer-Encoding: binary"
						+ LINE_DELIMITER + LINE_DELIMITER + BODY_PART_TAG + LINE_DELIMITER + imageType.getMimeType())
		);
		assertThat(logs.getRight().get(0).getData().getMediaType(), equalTo(imageType.getMimeType()));
		assertThat(logs.getRight().get(0).getData().read(), equalTo(image));
	}

	@SuppressWarnings("SameParameterValue")
	private HttpEntity getBinaryTextBody(ContentType textType, String text, ContentType binaryType, String filePath) {
		FormBodyPart textPart = FormBodyPartBuilder.create().setName("text").setBody(new StringBody(text, textType)).build();
		FormBodyPart binaryPart = getBinaryPart(binaryType, filePath);

		return MultipartEntityBuilder.create().addPart(binaryPart).addPart(textPart).build();
	}

	@Test
	public void test_logger_text_and_image_multipart() throws IOException {
		byte[] image = getResource(IMAGE);
		ContentType imageType = ContentType.IMAGE_JPEG;
		ContentType textType = ContentType.TEXT_PLAIN;

		String message = "test_message";
		HttpEntity requestBody = getBinaryTextBody(textType, message, imageType, IMAGE);
		HttpRequest requestSpecification = mockBasicRequest(Collections.emptyList(), requestBody);
		Triple<List<String>, List<String>, List<ReportPortalMessage>> logs = runChainComplexMessageCapture(requestSpecification,
				createBasicResponse()
		);
		assertThat(logs.getLeft(), hasSize(1));
		assertThat(logs.getMiddle(), hasSize(2));
		assertThat(logs.getRight(), hasSize(1));

		assertThat(logs.getLeft().get(0), equalTo(EMPTY_REQUEST));

		assertThat(logs.getMiddle().get(0),
				equalTo(HEADERS_TAG + LINE_DELIMITER + "Content-Disposition: form-data; name=\"text\"" + LINE_DELIMITER
						+ "Content-Type: text/plain; charset=ISO-8859-1" + LINE_DELIMITER + "Content-Transfer-Encoding: 8bit"
						+ LINE_DELIMITER + LINE_DELIMITER + BODY_PART_TAG + "\n```\n" + message + "\n```")
		);
		assertThat(logs.getMiddle().get(1), equalTo(EMPTY_RESPONSE));

		assertThat(logs.getRight().get(0).getMessage(),
				equalTo(HEADERS_TAG + LINE_DELIMITER + "Content-Disposition: form-data; name=\"file\"; filename=\"pug/lucky.jpg\""
						+ LINE_DELIMITER + "Content-Type: image/jpeg" + LINE_DELIMITER + "Content-Transfer-Encoding: binary"
						+ LINE_DELIMITER + LINE_DELIMITER + BODY_PART_TAG + LINE_DELIMITER + imageType.getMimeType())
		);
		assertThat(logs.getRight().get(0).getData().getMediaType(), equalTo(imageType.getMimeType()));
		assertThat(logs.getRight().get(0).getData().read(), equalTo(image));
	}

	public static Iterable<Object[]> invalidContentTypes() {
		return Arrays.asList(
				new Object[] { "", ContentType.APPLICATION_OCTET_STREAM.getMimeType(), ContentType.APPLICATION_OCTET_STREAM.getMimeType() },
				new Object[] { "*/*", ContentType.APPLICATION_OCTET_STREAM.getMimeType(),
						ContentType.APPLICATION_OCTET_STREAM.getMimeType() },
				new Object[] { "something invalid", ContentType.APPLICATION_OCTET_STREAM.getMimeType(),
						ContentType.APPLICATION_OCTET_STREAM.getMimeType() },
				new Object[] { "/", ContentType.APPLICATION_OCTET_STREAM.getMimeType(),
						ContentType.APPLICATION_OCTET_STREAM.getMimeType() },
				new Object[] { "#*'\\`%^!@/\"$;", ContentType.APPLICATION_OCTET_STREAM.getMimeType(),
						ContentType.APPLICATION_OCTET_STREAM.getMimeType() },
				new Object[] { "a/a;F#%235f\\=f324$%^&", ContentType.APPLICATION_OCTET_STREAM.getMimeType(),
						ContentType.APPLICATION_OCTET_STREAM.getMimeType() }
		);
	}

	@ParameterizedTest
	@MethodSource("invalidContentTypes")
	public void test_logger_invalid_content_type(String mimeType, String expectedRequestType, String expectedResponseType)
			throws IOException {
		byte[] image = getResource(IMAGE);

		HttpRequest request = mockBasicRequest(Collections.singletonList(Pair.of(HttpHeaders.CONTENT_TYPE, mimeType)),
				new ByteArrayEntity(image)
		);
		HttpResponse response = createBasicResponse(Collections.singleton(Pair.of(HttpHeaders.CONTENT_TYPE, mimeType)),
				new ByteArrayEntity(image)
		);

		Triple<List<String>, List<String>, List<ReportPortalMessage>> logs = runChainComplexMessageCapture(request, response);
		String escapedMimeType = mimeType.replace("*", "\\*");
		assertThat(logs.getRight(), hasSize(2)); // Request + Response
		assertThat(logs.getRight().get(0).getMessage(),
				equalTo(EMPTY_REQUEST + LINE_DELIMITER + LINE_DELIMITER + HEADERS_TAG + LINE_DELIMITER + HttpHeaders.CONTENT_TYPE + ": "
						+ escapedMimeType)
		);
		assertThat(logs.getRight().get(1).getMessage(),
				equalTo(EMPTY_RESPONSE + LINE_DELIMITER + LINE_DELIMITER + HEADERS_TAG + LINE_DELIMITER + HttpHeaders.CONTENT_TYPE + ": "
						+ escapedMimeType)
		);

		assertThat(logs.getRight().get(0).getData().getMediaType(), equalTo(expectedRequestType));
		assertThat(logs.getRight().get(1).getData().getMediaType(), equalTo(expectedResponseType));

		assertThat(logs.getRight().get(0).getData().read(), equalTo(image));
		assertThat(logs.getRight().get(1).getData().read(), equalTo(image));
	}

	@Test
	public void test_request_log_filter_type() {
		HttpRequest requestSpecification = mockBasicRequest();
		HttpResponse responseObject = createBasicResponse();
		Triple<List<String>, List<String>, List<ReportPortalMessage>> logs = runChainComplexMessageCapture(requestSpecification,
				responseObject,
				new ReportPortalHttpLoggingInterceptor(LogLevel.INFO).addRequestFilter(r -> true)
		);
		assertThat(logs.getMiddle(), hasSize(1));
		assertThat(logs.getMiddle().get(0), startsWith(RESPONSE_TAG));
	}

	@Test
	public void test_response_log_filter_type() {
		HttpRequest requestSpecification = mockBasicRequest();
		HttpResponse responseObject = createBasicResponse();
		Triple<List<String>, List<String>, List<ReportPortalMessage>> logs = runChainComplexMessageCapture(requestSpecification,
				responseObject,
				new ReportPortalHttpLoggingInterceptor(LogLevel.INFO).addResponseFilter(r -> true)
		);
		assertThat(logs.getMiddle(), hasSize(1));
		assertThat(logs.getMiddle().get(0), startsWith(REQUEST_TAG));
	}
}

# Report Portal logger for Apache HttpComponents client

[![Maven Central](https://img.shields.io/maven-central/v/com.epam.reportportal/logger-java-httpcomponents.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.epam.reportportal/logger-java-httpcomponents)
[![CI Build](https://github.com/reportportal/logger-java-httpcomponents/actions/workflows/ci.yml/badge.svg)](https://github.com/reportportal/logger-java-httpcomponents/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/reportportal/logger-java-httpcomponents/branch/develop/graph/badge.svg?token=X7YWAPK1X5)](https://codecov.io/gh/reportportal/logger-java-httpcomponents)
[![Join Slack chat!](https://img.shields.io/badge/slack-join-brightgreen.svg)](https://slack.epmrpp.reportportal.io/)
[![stackoverflow](https://img.shields.io/badge/reportportal-stackoverflow-orange.svg?style=flat)](http://stackoverflow.com/questions/tagged/reportportal)
[![Build with Love](https://img.shields.io/badge/build%20with-❤%EF%B8%8F%E2%80%8D-lightgrey.svg)](http://reportportal.io?style=flat)

The latest version: 5.1.4. Please use `Maven Central` link above to get the logger.

## Overview

Apache HttpComponents Request/Response logging interceptor for Report Portal

The logger intercept and logs all Requests and Responses issued by Apache HttpComponents into Report Portal in Markdown
format, including multipart requests. It recognizes payload types and attach them in corresponding manner: image types
will be logged as images with thumbnails, binary types will be logged as entry attachments, text types will be formatted
and logged in Markdown code blocks.

## Configuration

### Build system configuration

You need to add the logger as one of your dependencies in Maven or Gradle.

#### Maven

`pom.xml`

```xml

<project>
    <!-- project declaration omitted -->

    <dependencies>
        <dependency>
            <groupId>com.epam.reportportal</groupId>
            <artifactId>logger-java-httpcomponents</artifactId>
            <version>5.1.4</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <!-- build config omitted -->
</project>
```

#### Gradle

`build.gradle`

```groovy
dependencies {
    testImplementation 'com.epam.reportportal:logger-java-httpcomponents:5.1.4'
}
```

### HTTP Client configuration

To start getting Request and Response logging in Report Portal you need to add the logger as one of your HTTP Client
interceptors. The best place for it is one before hook methods. E.G. `@BeforeClass` method for TestNG or `@BeforeAll`
method for JUnit 5:

```java
public class BaseTest {
	private final ReportPortalHttpLoggingInterceptor logger = new ReportPortalHttpLoggingInterceptor(LogLevel.INFO);

	private HttpClient client;

	@BeforeClass
	public void setupHttpClient() {
		client = HttpClientBuilder.create()
				.addInterceptorLast((HttpRequestInterceptor) logger)
				.addInterceptorLast((HttpResponseInterceptor) logger)
				.build();
	}
}
```

### Sanitize Request / Response data

To avoid logging sensitive data into Report Portal you can use corresponding converters:

* Cookie converter
* Header converter
* URI converter
* Content prettiers

Cookie, Header and URI converters are set in the logger constructor:

```java
public class BaseTest {
	private final ReportPortalHttpLoggingInterceptor logger = new ReportPortalHttpLoggingInterceptor(LogLevel.INFO,
			SanitizingHttpHeaderConverter.INSTANCE,
			DefaultHttpHeaderConverter.INSTANCE
	);

	private HttpClient client;

	@BeforeClass
	public void setupHttpClient() {
		client = HttpClientBuilder.create()
				.addInterceptorLast((HttpRequestInterceptor) logger)
				.addInterceptorLast((HttpResponseInterceptor) logger)
				.build();
	}
}
```

You are free to implement any converter by yourself with `java.util.function.Function` interface.

Content prettier are more complex, they parse data based on its content type and apply defined transformations. Default
prettiers just pretty-print JSON, HTML and XML data. To apply a custom content prettier call
`ReportPortalHttpLoggingInterceptor.setContentPrettiers`.
E.G.:

```java
public class BaseTest {
	private static final Map<String, Function<String, String>> MY_PRETTIERS = new HashMap<String, Function<String, String>>() {{
		put(ContentType.APPLICATION_XML.getMimeType(), XmlPrettier.INSTANCE);
		put(ContentType.APPLICATION_SOAP_XML.getMimeType(), XmlPrettier.INSTANCE);
		put(ContentType.APPLICATION_ATOM_XML.getMimeType(), XmlPrettier.INSTANCE);
		put(ContentType.APPLICATION_SVG_XML.getMimeType(), XmlPrettier.INSTANCE);
		put(ContentType.APPLICATION_XHTML_XML.getMimeType(), XmlPrettier.INSTANCE);
		put(ContentType.TEXT_XML.getMimeType(), XmlPrettier.INSTANCE);
		put(ContentType.APPLICATION_JSON.getMimeType(), JsonPrettier.INSTANCE);
		put("text/json", JsonPrettier.INSTANCE);
		put(ContentType.TEXT_HTML.getMimeType(), HtmlPrettier.INSTANCE);
	}};

	private final ReportPortalHttpLoggingInterceptor logger = new ReportPortalHttpLoggingInterceptor(LogLevel.INFO).setContentPrettiers(
			MY_PRETTIERS);

	private HttpClient client;

	@BeforeClass
	public void setupHttpClient() {
		client = HttpClientBuilder.create()
				.addInterceptorLast((HttpRequestInterceptor) logger)
				.addInterceptorLast((HttpResponseInterceptor) logger)
				.build();
	}
}
```

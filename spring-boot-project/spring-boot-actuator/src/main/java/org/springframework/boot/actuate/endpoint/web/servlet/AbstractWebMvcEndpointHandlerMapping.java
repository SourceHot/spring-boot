/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.endpoint.web.servlet;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.http.ApiVersion;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvoker;
import org.springframework.boot.actuate.endpoint.web.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.MatchableHandlerMapping;
import org.springframework.web.servlet.handler.RequestMatchResult;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.UrlPathHelper;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;

/**
 * A custom {@link HandlerMapping} that makes {@link ExposableWebEndpoint web endpoints}
 * available over HTTP using Spring MVC.
 *
 * @author Andy Wilkinson
 * @author Madhura Bhave
 * @author Phillip Webb
 * @author Brian Clozel
 * @since 2.0.0
 */
public abstract class AbstractWebMvcEndpointHandlerMapping extends RequestMappingInfoHandlerMapping
		implements InitializingBean, MatchableHandlerMapping {

	/**
	 * 用于创建RequestMappingInfo对象的构造器
	 */
	private static final RequestMappingInfo.BuilderConfiguration builderConfig = getBuilderConfig();
	/**
	 * 端点映射器
	 */
	private final EndpointMapping endpointMapping;
	/**
	 * ExposableWebEndpoint集合
	 */
	private final Collection<ExposableWebEndpoint> endpoints;
	/**
	 * 端点媒体类型
	 */
	private final EndpointMediaTypes endpointMediaTypes;
	/**
	 * CORS配置
	 */
	private final CorsConfiguration corsConfiguration;
	/**
	 * 是否应该注册链接端点
	 */
	private final boolean shouldRegisterLinksMapping;
	/**
	 * 方法对象
	 */
	private final Method handleMethod = ReflectionUtils.findMethod(OperationHandler.class, "handle",
			HttpServletRequest.class, Map.class);

	/**
	 * Creates a new {@code WebEndpointHandlerMapping} that provides mappings for the
	 * operations of the given {@code webEndpoints}.
	 *
	 * @param endpointMapping            the base mapping for all endpoints
	 * @param endpoints                  the web endpoints
	 * @param endpointMediaTypes         media types consumed and produced by the endpoints
	 * @param shouldRegisterLinksMapping whether the links endpoint should be registered
	 */
	public AbstractWebMvcEndpointHandlerMapping(EndpointMapping endpointMapping,
												Collection<ExposableWebEndpoint> endpoints, EndpointMediaTypes endpointMediaTypes,
												boolean shouldRegisterLinksMapping) {
		this(endpointMapping, endpoints, endpointMediaTypes, null, shouldRegisterLinksMapping);
	}

	/**
	 * Creates a new {@code AbstractWebMvcEndpointHandlerMapping} that provides mappings
	 * for the operations of the given endpoints.
	 *
	 * @param endpointMapping            the base mapping for all endpoints
	 * @param endpoints                  the web endpoints
	 * @param endpointMediaTypes         media types consumed and produced by the endpoints
	 * @param corsConfiguration          the CORS configuration for the endpoints or {@code null}
	 * @param shouldRegisterLinksMapping whether the links endpoint should be registered
	 */
	public AbstractWebMvcEndpointHandlerMapping(EndpointMapping endpointMapping,
												Collection<ExposableWebEndpoint> endpoints, EndpointMediaTypes endpointMediaTypes,
												CorsConfiguration corsConfiguration, boolean shouldRegisterLinksMapping) {
		this.endpointMapping = endpointMapping;
		this.endpoints = endpoints;
		this.endpointMediaTypes = endpointMediaTypes;
		this.corsConfiguration = corsConfiguration;
		this.shouldRegisterLinksMapping = shouldRegisterLinksMapping;
		setOrder(-100);
	}

	@SuppressWarnings("deprecation")
	private static RequestMappingInfo.BuilderConfiguration getBuilderConfig() {
		RequestMappingInfo.BuilderConfiguration config = new RequestMappingInfo.BuilderConfiguration();
		config.setPathMatcher(null);
		config.setSuffixPatternMatch(false);
		config.setTrailingSlashMatch(true);
		return config;
	}

	@Override
	protected void initHandlerMethods() {
		// 处理ExposableWebEndpoint集合
		for (ExposableWebEndpoint endpoint : this.endpoints) {
			// 处理单个ExposableWebEndpoint中存在的WebOperation接口
			for (WebOperation operation : endpoint.getOperations()) {
				// 注册endpoint和operation
				registerMappingForOperation(endpoint, operation);
			}
		}
		// 是否应该注册链接端点
		if (this.shouldRegisterLinksMapping) {
			// 注册链接映射
			registerLinksMapping();
		}
	}

	@Override
	protected HandlerMethod createHandlerMethod(Object handler, Method method) {
		HandlerMethod handlerMethod = super.createHandlerMethod(handler, method);
		return new WebMvcEndpointHandlerMethod(handlerMethod.getBean(), handlerMethod.getMethod());
	}

	@Override
	public RequestMatchResult match(HttpServletRequest request, String pattern) {
		RequestMappingInfo info = RequestMappingInfo.paths(pattern).options(builderConfig).build();
		RequestMappingInfo matchingInfo = info.getMatchingCondition(request);
		if (matchingInfo == null) {
			return null;
		}
		Set<String> patterns = matchingInfo.getPatternsCondition().getPatterns();
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		return new RequestMatchResult(patterns.iterator().next(), lookupPath, getPathMatcher());
	}

	private void registerMappingForOperation(ExposableWebEndpoint endpoint, WebOperation operation) {
		// 获取WebOperationRequestPredicate对象
		WebOperationRequestPredicate predicate = operation.getRequestPredicate();
		// 提取path
		String path = predicate.getPath();
		// 提取matchAllRemainingPathSegmentsVariable
		String matchAllRemainingPathSegmentsVariable = predicate.getMatchAllRemainingPathSegmentsVariable();
		// 替换path中的{*matchAllRemainingPathSegmentsVariable}为**
		if (matchAllRemainingPathSegmentsVariable != null) {
			path = path.replace("{*" + matchAllRemainingPathSegmentsVariable + "}", "**");
		}
		// 创建ServletWebOperation接口实现类
		ServletWebOperation servletWebOperation = wrapServletWebOperation(endpoint, operation,
				new ServletWebOperationAdapter(operation));
		// 注册
		registerMapping(createRequestMappingInfo(predicate, path), new OperationHandler(servletWebOperation),
				this.handleMethod);
	}

	/**
	 * Hook point that allows subclasses to wrap the {@link ServletWebOperation} before
	 * it's called. Allows additional features, such as security, to be added.
	 *
	 * @param endpoint            the source endpoint
	 * @param operation           the source operation
	 * @param servletWebOperation the servlet web operation to wrap
	 * @return a wrapped servlet web operation
	 */
	protected ServletWebOperation wrapServletWebOperation(ExposableWebEndpoint endpoint, WebOperation operation,
														  ServletWebOperation servletWebOperation) {
		return servletWebOperation;
	}

	private RequestMappingInfo createRequestMappingInfo(WebOperationRequestPredicate predicate, String path) {
		// 创建RequestMappingInfo对象
		return RequestMappingInfo.paths(this.endpointMapping.createSubPath(path))
				.methods(RequestMethod.valueOf(predicate.getHttpMethod().name()))
				.consumes(predicate.getConsumes().toArray(new String[0]))
				.produces(predicate.getProduces().toArray(new String[0])).build();
	}

	private void registerLinksMapping() {
		RequestMappingInfo mapping = RequestMappingInfo.paths(this.endpointMapping.createSubPath(""))
				.methods(RequestMethod.GET).produces(this.endpointMediaTypes.getProduced().toArray(new String[0]))
				.options(builderConfig).build();
		LinksHandler linksHandler = getLinksHandler();
		registerMapping(mapping, linksHandler, ReflectionUtils.findMethod(linksHandler.getClass(), "links",
				HttpServletRequest.class, HttpServletResponse.class));
	}

	@Override
	protected boolean hasCorsConfigurationSource(Object handler) {
		return this.corsConfiguration != null;
	}

	@Override
	protected CorsConfiguration initCorsConfiguration(Object handler, Method method, RequestMappingInfo mapping) {
		return this.corsConfiguration;
	}

	@Override
	protected boolean isHandler(Class<?> beanType) {
		return false;
	}

	@Override
	protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
		return null;
	}

	@Override
	protected void extendInterceptors(List<Object> interceptors) {
		interceptors.add(new SkipPathExtensionContentNegotiation());
	}

	/**
	 * Return the Handler providing actuator links at the root endpoint.
	 *
	 * @return the links handler
	 */
	protected abstract LinksHandler getLinksHandler();

	/**
	 * Return the web endpoints being mapped.
	 *
	 * @return the endpoints
	 */
	public Collection<ExposableWebEndpoint> getEndpoints() {
		return this.endpoints;
	}

	/**
	 * Handler providing actuator links at the root endpoint.
	 */
	@FunctionalInterface
	protected interface LinksHandler {

		Object links(HttpServletRequest request, HttpServletResponse response);

	}

	/**
	 * A servlet web operation that can be handled by Spring MVC.
	 */
	@FunctionalInterface
	protected interface ServletWebOperation {

		Object handle(HttpServletRequest request, Map<String, String> body);

	}

	/**
	 * Adapter class to convert an {@link OperationInvoker} into a
	 * {@link ServletWebOperation}.
	 */
	private static class ServletWebOperationAdapter implements ServletWebOperation {

		/**
		 * 路径分割符
		 */
		private static final String PATH_SEPARATOR = AntPathMatcher.DEFAULT_PATH_SEPARATOR;

		/**
		 * web endpoint 操作接口
		 */
		private final WebOperation operation;

		ServletWebOperationAdapter(WebOperation operation) {
			this.operation = operation;
		}

		@Override
		public Object handle(HttpServletRequest request, @RequestBody(required = false) Map<String, String> body) {
			// 获取请求头
			HttpHeaders headers = new ServletServerHttpRequest(request).getHeaders();
			// 获取请求参数
			Map<String, Object> arguments = getArguments(request, body);
			try {
				// 获取api版本
				ApiVersion apiVersion = ApiVersion.fromHttpHeaders(headers);
				// 创建安全上下文
				ServletSecurityContext securityContext = new ServletSecurityContext(request);
				// 创建调用上下文
				InvocationContext invocationContext = new InvocationContext(apiVersion, securityContext, arguments);
				// 处理结果
				return handleResult(this.operation.invoke(invocationContext), HttpMethod.resolve(request.getMethod()));
			} catch (InvalidEndpointRequestException ex) {
				throw new InvalidEndpointBadRequestException(ex);
			}
		}

		@Override
		public String toString() {
			return "Actuator web endpoint '" + this.operation.getId() + "'";
		}

		private Map<String, Object> getArguments(HttpServletRequest request, Map<String, String> body) {
			Map<String, Object> arguments = new LinkedHashMap<>(getTemplateVariables(request));
			String matchAllRemainingPathSegmentsVariable = this.operation.getRequestPredicate()
					.getMatchAllRemainingPathSegmentsVariable();
			if (matchAllRemainingPathSegmentsVariable != null) {
				arguments.put(matchAllRemainingPathSegmentsVariable, getRemainingPathSegments(request));
			}
			if (body != null && HttpMethod.POST.name().equals(request.getMethod())) {
				arguments.putAll(body);
			}
			request.getParameterMap().forEach(
					(name, values) -> arguments.put(name, (values.length != 1) ? Arrays.asList(values) : values[0]));
			return arguments;
		}

		private Object getRemainingPathSegments(HttpServletRequest request) {
			String[] pathTokens = tokenize(request, UrlPathHelper.PATH_ATTRIBUTE, true);
			String[] patternTokens = tokenize(request, HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, false);
			int numberOfRemainingPathSegments = pathTokens.length - patternTokens.length + 1;
			Assert.state(numberOfRemainingPathSegments >= 0, "Unable to extract remaining path segments");
			String[] remainingPathSegments = new String[numberOfRemainingPathSegments];
			System.arraycopy(pathTokens, patternTokens.length - 1, remainingPathSegments, 0,
					numberOfRemainingPathSegments);
			return remainingPathSegments;
		}

		private String[] tokenize(HttpServletRequest request, String attributeName, boolean decode) {
			String value = (String) request.getAttribute(attributeName);
			String[] segments = StringUtils.tokenizeToStringArray(value, PATH_SEPARATOR, false, true);
			if (decode) {
				for (int i = 0; i < segments.length; i++) {
					if (segments[i].contains("%")) {
						segments[i] = StringUtils.uriDecode(segments[i], StandardCharsets.UTF_8);
					}
				}
			}
			return segments;
		}

		@SuppressWarnings("unchecked")
		private Map<String, String> getTemplateVariables(HttpServletRequest request) {
			return (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		}

		private Object handleResult(Object result, HttpMethod httpMethod) {
			if (result == null) {
				return new ResponseEntity<>(
						(httpMethod != HttpMethod.GET) ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND);
			}
			if (!(result instanceof WebEndpointResponse)) {
				return result;
			}
			WebEndpointResponse<?> response = (WebEndpointResponse<?>) result;
			return ResponseEntity.status(response.getStatus()).body(response.getBody());
		}

	}

	/**
	 * Handler for a {@link ServletWebOperation}.
	 */
	private static final class OperationHandler {

		private final ServletWebOperation operation;

		OperationHandler(ServletWebOperation operation) {
			this.operation = operation;
		}

		@ResponseBody
		Object handle(HttpServletRequest request, @RequestBody(required = false) Map<String, String> body) {
			return this.operation.handle(request, body);
		}

		@Override
		public String toString() {
			return this.operation.toString();
		}

	}

	/**
	 * {@link HandlerMethod} subclass for endpoint information logging.
	 */
	private static class WebMvcEndpointHandlerMethod extends HandlerMethod {

		WebMvcEndpointHandlerMethod(Object bean, Method method) {
			super(bean, method);
		}

		@Override
		public String toString() {
			return getBean().toString();
		}

		@Override
		public HandlerMethod createWithResolvedBean() {
			return this;
		}

	}

	/**
	 * Nested exception used to wrap an {@link InvalidEndpointRequestException} and
	 * provide a {@link HttpStatus#BAD_REQUEST} status.
	 */
	private static class InvalidEndpointBadRequestException extends ResponseStatusException {

		InvalidEndpointBadRequestException(InvalidEndpointRequestException cause) {
			super(HttpStatus.BAD_REQUEST, cause.getReason(), cause);
		}

	}

	private static final class ServletSecurityContext implements SecurityContext {

		private final HttpServletRequest request;

		private ServletSecurityContext(HttpServletRequest request) {
			this.request = request;
		}

		/**
		 * 返回当前经过身份验证的Principal
		 *
		 * @return
		 */
		@Override
		public Principal getPrincipal() {
			return this.request.getUserPrincipal();
		}

		/**
		 * 确认是否拥有role
		 *
		 * @param role name of the role
		 * @return
		 */
		@Override
		public boolean isUserInRole(String role) {
			return this.request.isUserInRole(role);
		}

	}

}

package com.eventguard.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PermissionInterceptor：注解权限校验（放行/401/403）。 */
class PermissionInterceptorTest {

    private final PermissionInterceptor interceptor = new PermissionInterceptor();

    private HandlerMethod handlerWith(RequirePermission rp, Class<?> beanType) {
        HandlerMethod hm = mock(HandlerMethod.class);
        when(hm.getMethodAnnotation(RequirePermission.class)).thenReturn(rp);
        when(hm.getBeanType()).thenAnswer(inv -> beanType);
        return hm;
    }

    @Test
    void noAnnotation_allows() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthPrincipal.REQUEST_ATTR, AuthPrincipal.user(1L, "u", Set.of()));
        boolean ok = interceptor.preHandle(req, new MockHttpServletResponse(),
                handlerWith(null, Object.class));
        assertTrue(ok);
    }

    @Test
    void hasPermission_allows() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthPrincipal.REQUEST_ATTR, AuthPrincipal.user(1L, "u", Set.of("order:read")));
        boolean ok = interceptor.preHandle(req, new MockHttpServletResponse(),
                handlerWith(permission("order:read"), Object.class));
        assertTrue(ok);
    }

    @Test
    void missingPermission_returns403() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthPrincipal.REQUEST_ATTR, AuthPrincipal.user(1L, "u", Set.of("order:read")));
        MockHttpServletResponse res = new MockHttpServletResponse();
        interceptor.preHandle(req, res, handlerWith(permission("order:write"), Object.class));
        assertEquals(403, res.getStatus());
    }

    @Test
    void noPrincipal_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        interceptor.preHandle(req, res, handlerWith(permission("order:read"), Object.class));
        assertEquals(401, res.getStatus());
    }

    @Test
    void classLevelNoAnnotation_allows() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(AuthPrincipal.REQUEST_ATTR, AuthPrincipal.user(1L, "u", Set.of()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        interceptor.preHandle(req, res, handlerWith(null, PermissionInterceptorTest.class));
        assertEquals(200, res.getStatus());
    }

    private RequirePermission permission(String code) {
        return new RequirePermission() {
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RequirePermission.class;
            }
            @Override public String value() { return code; }
        };
    }
}

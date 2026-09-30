package org.dual.replicate.config;

import org.dual.replicate.domain.AppErrorSource;
import org.dual.replicate.remote.RemoteServiceException.Kind;
import org.dual.replicate.service.AppErrorService;
import org.dual.replicate.service.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnhandledExceptionResolverTest {

    private final AppErrorService appErrors = mock(AppErrorService.class);
    private final UnhandledExceptionResolver resolver = new UnhandledExceptionResolver(appErrors);
    private final MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/generations/1/images/x.png");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    UnhandledExceptionResolverTest() {
        request.addHeader("HX-Request", "true");
        when(appErrors.record(anyString(), any(Throwable.class)))
                .thenReturn(new AppErrorService.Recorded("k", "msg", true, false));
    }

    @Test
    void anExternalServiceFailureIsRecordedWithItsOwnSourceAndIsA502() {
        StorageException failure = new StorageException("webdav giu'", null, Kind.TRANSIENT);

        resolver.resolveException(request, response, null, failure);

        verify(appErrors).record("DELETE /generations/1/images/x.png", failure);
        verify(appErrors).addToastHeader(eq(response), any());
        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(AppErrorService.sourceOf(failure)).isEqualTo(AppErrorSource.STORAGE);
    }

    @Test
    void anExpectedRejectionIsNotRecordedAndIsA422WithAToast() {
        StorageException rejected = new StorageException("file inesistente", null, Kind.REJECTED);

        resolver.resolveException(request, response, null, rejected);

        verify(appErrors, never()).record(anyString(), any(Throwable.class));
        verify(appErrors).addHxTrigger(eq(response), eq("app-error"), any());
        assertThat(response.getStatus()).isEqualTo(422);
    }

    @Test
    void anUnexpectedErrorStaysA500AndIsRecordedAsInternal() {
        RuntimeException bug = new IllegalStateException("bug");

        resolver.resolveException(request, response, null, bug);

        verify(appErrors).record("DELETE /generations/1/images/x.png", bug);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(AppErrorService.sourceOf(bug)).isEqualTo(AppErrorSource.INTERNAL);
    }
}

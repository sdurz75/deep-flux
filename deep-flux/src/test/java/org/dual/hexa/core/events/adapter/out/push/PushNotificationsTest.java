package org.dual.hexa.core.events.adapter.out.push;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.LinkedHashMap;
import java.util.Map;

import org.dual.hexa.core.push.port.in.IClientPush;
import org.junit.jupiter.api.Test;

/** Una notifica di successo e' l'evento SSE {@code notice} (non {@code system-event}: non tocca il registro eventi). */
class PushNotificationsTest {

    private final IClientPush push = mock(IClientPush.class);
    private final PushNotifications notifications = new PushNotifications(push);

    @Test
    void successEmitsTheNoticeEventWithKeyMessagePathAndSeverity() {
        notifications.success("generation-7", "Immagine pronta", "/generations/7");

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("key", "generation-7");
        expected.put("message", "Immagine pronta");
        expected.put("path", "/generations/7");
        expected.put("severity", "SUCCESS");
        verify(push).emit("notice", expected);
    }
}

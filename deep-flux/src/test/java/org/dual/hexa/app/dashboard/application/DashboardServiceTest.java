package org.dual.hexa.app.dashboard.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.dual.hexa.ai.chat.domain.ChatStats;
import org.dual.hexa.ai.chat.port.in.IChatConversations;
import org.dual.hexa.app.dashboard.port.in.IDashboard;
import org.dual.hexa.app.generation.domain.GalleryItem;
import org.dual.hexa.app.generation.domain.Generation;
import org.dual.hexa.app.generation.domain.GenerationStats;
import org.dual.hexa.app.generation.port.in.IGenerationStats;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.app.training.domain.TrainingStatus;
import org.dual.hexa.app.training.port.in.ITrainings;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.dual.hexa.core.kernel.Paged;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private final IGenerationStats generationStats = mock(IGenerationStats.class);
    private final IGenerations generations = mock(IGenerations.class);
    private final ITrainings trainings = mock(ITrainings.class);
    private final ILoraPresets loras = mock(ILoraPresets.class);
    private final IChatConversations chat = mock(IChatConversations.class);
    private final ISystemEvents events = mock(ISystemEvents.class);
    private final DashboardService service = new DashboardService(generationStats, generations, trainings, loras, chat, events);

    @Test
    void assemblesTheViewFromThePortsOfTheOtherSubsystems() {
        GenerationStats stats = new GenerationStats(5, 1, 2, 1, 0, 3, IDashboard.WINDOW_DAYS, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.TEN, List.of(), List.of());
        GalleryItem item = new GalleryItem(mock(Generation.class), "a.png");
        ISystemEvents.Unseen unseen = new ISystemEvents.Unseen(2, List.of(), true);
        when(generationStats.stats(IDashboard.WINDOW_DAYS)).thenReturn(stats);
        when(chat.stats()).thenReturn(new ChatStats(4, 40, null));
        when(trainings.countByStatus()).thenReturn(Map.of(TrainingStatus.PROCESSING, 1L, TrainingStatus.SUCCEEDED, 2L));
        when(loras.list()).thenReturn(List.of(mock(ILoraPresets.LoraView.class)));
        when(generations.galleryPage(0, IDashboard.RECENT_IMAGES)).thenReturn(new Paged<>(List.of(item), 0, IDashboard.RECENT_IMAGES, 1));
        when(events.unseen()).thenReturn(unseen);

        IDashboard.View view = service.view();

        assertThat(view.generation()).isSameAs(stats);
        assertThat(view.chat().messages()).isEqualTo(40);
        assertThat(view.loras()).isEqualTo(1);
        assertThat(view.recent()).containsExactly(item);
        assertThat(view.attention()).isSameAs(unseen);
        assertThat(view.trainingsRunning()).isEqualTo(1);
        assertThat(view.trainingsSucceeded()).isEqualTo(2);
        assertThat(view.trainingsTotal()).isEqualTo(3);
        verify(generationStats).stats(30);
    }
}

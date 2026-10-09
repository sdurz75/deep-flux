package org.dual.hexa.app.dashboard.application;

import org.dual.hexa.ai.chat.port.in.IChatConversations;
import org.dual.hexa.app.dashboard.port.in.IDashboard;
import org.dual.hexa.app.generation.port.in.IGenerationStats;
import org.dual.hexa.app.generation.port.in.IGenerations;
import org.dual.hexa.app.generation.port.in.ILoraPresets;
import org.dual.hexa.app.training.port.in.ITrainings;
import org.dual.hexa.core.events.port.in.ISystemEvents;
import org.springframework.stereotype.Service;

/** Compone la dashboard leggendo solo le porte {@code in} degli altri sottosistemi: nessuna logica propria oltre all'assemblaggio. */
@Service
public class DashboardService implements IDashboard {

    private final IGenerationStats generationStats;
    private final IGenerations generations;
    private final ITrainings trainings;
    private final ILoraPresets loras;
    private final IChatConversations chat;
    private final ISystemEvents systemEvents;

    public DashboardService(IGenerationStats generationStats, IGenerations generations, ITrainings trainings, ILoraPresets loras,
                            IChatConversations chat, ISystemEvents systemEvents) {
        this.generationStats = generationStats;
        this.generations = generations;
        this.trainings = trainings;
        this.loras = loras;
        this.chat = chat;
        this.systemEvents = systemEvents;
    }

    @Override
    public View view() {
        return new View(generationStats.stats(WINDOW_DAYS), chat.stats(), trainings.countByStatus(), loras.list().size(),
                generations.galleryPage(0, RECENT_IMAGES).content(), systemEvents.unseen());
    }
}

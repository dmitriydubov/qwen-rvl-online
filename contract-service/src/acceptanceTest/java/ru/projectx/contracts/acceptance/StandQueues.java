package ru.projectx.contracts.acceptance;

import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class StandQueues {

    private final List<StandQueue> queues;

    public StandQueues(List<StandQueue> queues) {
        this.queues = List.copyOf(queues);
    }

    static void drain(RabbitTemplate broker, String queue) {
        broker.execute(channel -> {
            int drained = 0;
            while (channel.basicGet(queue, true) != null) {
                drained++;
            }
            return drained;
        });
    }

    StandQueue called(String alias) {
        return queues.stream().filter(queue -> queue.alias().equals(alias)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("стенд не знает очереди «" + alias + "»"));
    }
}

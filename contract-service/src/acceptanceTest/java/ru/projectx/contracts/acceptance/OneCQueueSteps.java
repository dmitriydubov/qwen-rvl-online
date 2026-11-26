package ru.projectx.contracts.acceptance;

import io.cucumber.java.After;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import java.util.ArrayList;
import java.util.List;

public class OneCQueueSteps {

    private final StandQueues queues;
    private final ConsumedQueues consumers;
    private final List<OneCQueue> deleted = new ArrayList<>();

    public OneCQueueSteps(StandQueues queues, ConsumedQueues consumers) {
        this.queues = queues;
        this.consumers = consumers;
    }

    @Дано("у брокера 1С:УСК удалена очередь {string}")
    public void queueDeleted(String alias) {
        OneCQueue queue = oneC(alias);
        queue.delete();
        deleted.add(queue);
    }

    @Когда("у брокера 1С:УСК снова объявлена очередь {string}")
    public void queueRedeclared(String alias) {
        oneC(alias).declare();
    }

    @After
    public void restoreDeletedQueues() {
        if (!deleted.isEmpty()) {
            deleted.forEach(OneCQueue::declare);
            deleted.forEach(queue -> consumers.restoreConsumerOf(queue.name()));
            deleted.clear();
        }
    }

    private OneCQueue oneC(String alias) {
        if (queues.called(alias) instanceof OneCQueue queue) {
            return queue;
        }
        throw new IllegalArgumentException("очередь «" + alias + "» — не очередь брокера 1С:УСК");
    }
}

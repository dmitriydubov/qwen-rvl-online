package ru.projectx.contracts.acceptance;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class StatusQueue implements StandQueue {

    private static final boolean DURABLE = true;
    private static final boolean EXCLUSIVE = false;
    private static final boolean AUTO_DELETE = false;
    private static final boolean AUTO_ACK = true;
    private static final String ALIAS = "состояние выпуска";

    private final RabbitTemplate broker;
    private final String queue;
    private boolean declared;

    StatusQueue(ConnectionFactory connections, String queue) {
        this.broker = new RabbitTemplate(connections);
        this.queue = queue;
        declare();
    }

    StatusQueue called(String alias) {
        if (!ALIAS.equals(alias)) {
            throw new IllegalArgumentException("стенд знает только очередь «" + ALIAS + "», а не «" + alias + "»");
        }
        return this;
    }

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public String name() {
        return queue;
    }

    void declare() {
        if (!declared) {
            broker.execute(channel -> channel.queueDeclare(queue, DURABLE, EXCLUSIVE, AUTO_DELETE, null));
            declared = true;
        }
    }

    void delete() {
        broker.execute(channel -> channel.queueDelete(queue));
        declared = false;
    }

    @Override
    public void purge() {
        StandQueues.drain(broker, queue);
    }

    @Override
    public AMQP.Queue.DeclareOk state() {
        return broker.execute(channel -> channel.queueDeclarePassive(queue));
    }

    List<GetResponse> drain() {
        return broker.execute(channel -> {
            List<GetResponse> received = new ArrayList<>();
            for (GetResponse next = channel.basicGet(queue, AUTO_ACK); next != null; next = channel.basicGet(queue, AUTO_ACK)) {
                received.add(next);
            }
            return received;
        });
    }
}

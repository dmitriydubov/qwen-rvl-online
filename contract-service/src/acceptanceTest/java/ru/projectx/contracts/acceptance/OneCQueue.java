package ru.projectx.contracts.acceptance;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class OneCQueue implements StandQueue {

    private static final boolean DURABLE = true;
    private static final boolean EXCLUSIVE = false;
    private static final boolean AUTO_DELETE = false;
    private static final boolean AUTO_ACK = true;

    private final RabbitTemplate broker;
    private final String alias;
    private final String queue;

    OneCQueue(ConnectionFactory connections, String alias, String queue) {
        this.broker = new RabbitTemplate(connections);
        this.alias = alias;
        this.queue = queue;
        broker.execute(channel -> channel.queueDeclare(queue, DURABLE, EXCLUSIVE, AUTO_DELETE, null));
    }

    @Override
    public String alias() {
        return alias;
    }

    @Override
    public String name() {
        return queue;
    }

    @Override
    public AMQP.Queue.DeclareOk state() {
        return broker.execute(channel -> channel.queueDeclarePassive(queue));
    }

    void declare() {
        broker.execute(channel -> channel.queueDeclare(queue, DURABLE, EXCLUSIVE, AUTO_DELETE, null));
    }

    void delete() {
        broker.execute(channel -> channel.queueDelete(queue));
    }

    @Override
    public void purge() {
        StandQueues.drain(broker, queue);
    }

    void publish(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        broker.send("", queue, MessageBuilder.withBody(body).andProperties(properties).build());
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

package ru.projectx.contracts.acceptance;

import com.rabbitmq.client.AMQP;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class OrderEventQueue implements StandQueue {

    static final String ALIAS = "события заказов";

    private static final boolean DURABLE = true;
    private static final boolean EXCLUSIVE = false;
    private static final boolean AUTO_DELETE = false;

    private final RabbitTemplate broker;
    private final String queue;

    OrderEventQueue(ConnectionFactory connections, String queue) {
        this.broker = new RabbitTemplate(connections);
        this.queue = queue;
        declare();
    }

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public String name() {
        return queue;
    }

    @Override
    public AMQP.Queue.DeclareOk state() {
        return broker.execute(channel -> channel.queueDeclarePassive(queue));
    }

    @Override
    public void purge() {
        StandQueues.drain(broker, queue);
    }

    void declare() {
        broker.execute(channel -> channel.queueDeclare(queue, DURABLE, EXCLUSIVE, AUTO_DELETE, null));
    }

    void delete() {
        broker.execute(channel -> channel.queueDelete(queue));
    }

    void publish(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        broker.send("", queue, MessageBuilder.withBody(body).andProperties(properties).build());
    }
}

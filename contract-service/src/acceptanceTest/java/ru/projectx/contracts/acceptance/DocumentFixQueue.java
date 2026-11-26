package ru.projectx.contracts.acceptance;

import com.rabbitmq.client.AMQP;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class DocumentFixQueue implements StandQueue {

    static final String NAME = "rvl.event.order.topic";

    private static final String EXCHANGE_TYPE = "direct";
    private static final boolean DURABLE = true;
    private static final boolean EXCLUSIVE = false;
    private static final boolean AUTO_DELETE = false;
    private static final long RECEIVE_TIMEOUT_MILLIS = 500;

    private final RabbitTemplate broker;
    private final String exchange;
    private final String routingKey;

    DocumentFixQueue(ConnectionFactory connections, String exchange, String routingKey) {
        this.broker = new RabbitTemplate(connections);
        this.exchange = exchange;
        this.routingKey = routingKey;
        declare();
    }

    @Override
    public String alias() {
        return "подтверждения документов";
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AMQP.Queue.DeclareOk state() {
        return broker.execute(channel -> channel.queueDeclarePassive(NAME));
    }

    @Override
    public void purge() {
        StandQueues.drain(broker, NAME);
    }

    void declare() {
        broker.execute(channel -> {
            channel.exchangeDeclare(exchange, EXCHANGE_TYPE, DURABLE);
            channel.queueDeclare(NAME, DURABLE, EXCLUSIVE, AUTO_DELETE, null);
            return channel.queueBind(NAME, exchange, routingKey);
        });
    }

    void delete() {
        broker.execute(channel -> channel.queueDelete(NAME));
    }

    List<Message> drain() {
        List<Message> received = new ArrayList<>();
        for (@Nullable Message message = broker.receive(NAME, RECEIVE_TIMEOUT_MILLIS); message != null;
                message = broker.receive(NAME, RECEIVE_TIMEOUT_MILLIS)) {
            received.add(message);
        }
        return received;
    }
}

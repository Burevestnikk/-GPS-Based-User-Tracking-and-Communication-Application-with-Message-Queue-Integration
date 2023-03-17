package com.example.lab3_egor_lezov;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

public class RabbitMQSender {
    private final static String QUEUE_NAME = "EgorUTHQueues";
    private final static String AMQP_URL = "amqps://umxpcwev:H4-UVPDm5rZ7EiAs_M0n4z7MBBOejhNo@sparrow.rmq.cloudamqp.com/umxpcwev";

    public void sendMessage(String message) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(AMQP_URL);
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();
        channel.queueDeclare(QUEUE_NAME, false, false, false, null);
        channel.basicPublish("", QUEUE_NAME, null, message.getBytes("UTF-8"));
        channel.close();
        connection.close();
    }
}
